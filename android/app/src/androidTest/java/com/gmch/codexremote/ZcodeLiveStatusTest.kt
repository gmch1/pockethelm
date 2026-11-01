package com.gmch.codexremote

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ZcodeLiveStatusTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestPrefsApp
    private lateinit var vm: ZcodeViewModel
    private lateinit var state: MutableStateFlow<ZcodeState>
    private lateinit var bridge: FakeRpcBridge
    private val phase = AtomicReference("running")
    private val reads = AtomicInteger()
    private val pauses = AtomicInteger()
    private fun field(name: String) = ZcodeViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun setup() {
        app = TestPrefsApp(UUID.randomUUID().toString()).apply { attach(instrumentation.targetContext) }
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        runBlocking { (field("restoreJob").get(vm) as Job).join() }
        @Suppress("UNCHECKED_CAST")
        state = field("mutable").get(vm) as MutableStateFlow<ZcodeState>
        state.value = ZcodeState(connected = true, sessionsFresh = true,
            sessions = listOf(ZSession("s", "未打开的任务", "/work", "host-a", status = "completed")))
        bridge = FakeRpcBridge(vm).also { it.install() }
        bridge.scripted["sessions/status"] = {
            reads.incrementAndGet()
            obj("workspaces" to JSONArray().put(obj("workspacePath" to "/work", "workspaceIdentity" to "host-a", "fresh" to true,
                "sessions" to JSONArray().put(obj("sessionId" to "s", "phase" to phase.get())))))
        }
        bridge.scripted["sessions/pause"] = { pauses.incrementAndGet(); obj() }
        instrumentation.runOnMainSync {
            field("supportsSessionIndex").set(vm, true)
            field("listSyncedAt").set(vm, System.currentTimeMillis())
            vm.enter()
        }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(12_000) { while (!predicate()) delay(30) }
    private fun label() = zcodeListStatus(state.value.sessions.single(), state.value.connected && state.value.sessionsFresh, state.value.liveStatuses)

    @Test fun unopenedTaskChangesWithoutTreeRefreshAndReleasesOnEngineSwitch() = runBlocking {
        setup()
        await { label() == "执行中" }
        assertNull(state.value.selected); assertFalse(state.value.busy)
        phase.set("waiting"); await { label() == "等待中" }
        phase.set("completedInterrupted"); await { label() == "已中断" }
        instrumentation.runOnMainSync { vm.leave() }
        await { pauses.get() == 1 }
        val before = reads.get(); delay(3000)
        assertEquals(before, reads.get()); assertTrue(state.value.liveStatuses.isEmpty())
        phase.set("error")
        instrumentation.runOnMainSync { vm.enter() }
        await { label() == "失败" }
        assertFalse(state.value.busy)
    }
    @Test fun backgroundAndDisconnectCannotKeepRunningBadge() = runBlocking {
        setup(); await { label() == "执行中" }
        instrumentation.runOnMainSync { vm.background() }
        await { pauses.get() == 1 }
        assertTrue(state.value.liveStatuses.isEmpty())
        instrumentation.runOnMainSync { vm.disconnectManually() }
        assertEquals("", label()); assertFalse(state.value.connected)
    }
    @Test fun statusFailureClearsActiveClaimAndNextPollRecovers() = runBlocking {
        setup(); await { label() == "执行中" }
        bridge.scripted["sessions/status"] = { throw FakeRpcBridge.FakeRpcError(-32001, "测试断流") }
        await { state.value.liveStatuses.isEmpty() }
        assertEquals("上次已完成", label()); assertTrue(state.value.connected)
        bridge.scripted["sessions/status"] = { obj("workspaces" to JSONArray().put(obj(
            "workspacePath" to "/work", "workspaceIdentity" to "host-a", "fresh" to true,
            "sessions" to JSONArray().put(obj("sessionId" to "s", "phase" to "completedSuccess"))))) }
        await { label() == "已完成" }
    }
    @After fun cleanup() {
        if (::vm.isInitialized) instrumentation.runOnMainSync { vm.viewModelScope.cancel() }
        if (::bridge.isInitialized) { bridge.close(); bridge.assertClean() }
        if (::app.isInitialized) {
            app.noBackupFilesDir.deleteRecursively()
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }
}
