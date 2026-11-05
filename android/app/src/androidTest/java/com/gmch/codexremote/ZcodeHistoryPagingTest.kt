package com.gmch.codexremote

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ZcodeHistoryPagingTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun field(name: String) = ZcodeViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    private suspend fun fixture(block: suspend (ZcodeViewModel, MutableStateFlow<ZcodeState>, FakeRpcBridge) -> Unit) {
        val app = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var vm: ZcodeViewModel
        inst.runOnMainSync { vm = ZcodeViewModel(app) }
        (field("restoreJob").get(vm) as Job).join()
        @Suppress("UNCHECKED_CAST") val state = field("mutable").get(vm) as MutableStateFlow<ZcodeState>
        val bridge = FakeRpcBridge(vm).also { it.install() }
        inst.runOnMainSync { state.value = ZcodeState(connected = true, selected = ZSession("s", "test", "/work"),
            phase = "running", logEpoch = "e", revision = 1, beforeRow = 20.0,
            recoveryPhase = RecoveryPhase.Ready, items = listOf(TimelineItem("new", "agentMessage", "latest"))) }
        try { block(vm, state, bridge) }
        finally {
            inst.runOnMainSync { vm.viewModelScope.cancel() }; bridge.close(); bridge.assertClean()
            app.noBackupFilesDir.deleteRecursively()
            inst.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }
    private suspend fun page(vm: ZcodeViewModel) {
        lateinit var job: Job
        inst.runOnMainSync { job = vm.history() }
        withTimeout(10_000) { job.join() }
    }
    private fun older(epoch: String = "e") = obj("logEpoch" to epoch, "revision" to 2, "hasMore" to true, "beforeRowId" to 10.0,
        "items" to JSONArray().put(obj("id" to "old", "kind" to "assistantText", "text" to "older")))
    @Test fun runningRevisionAdvanceLoadsAndKeepsOlderPageAfterNewSnapshot() = runBlocking {
        fixture { vm, state, bridge ->
            bridge.scripted["session/history"] = { older() }
            page(vm)
            assertNull(state.value.error); assertEquals(listOf("old", "new"), state.value.items.map { it.id })
            bridge.scripted["session/read"] = { obj("logEpoch" to "e", "revision" to 3, "phase" to "running", "firstRowId" to 20,
                "hasMore" to true, "items" to JSONArray().put(obj("id" to "new", "kind" to "assistantText", "text" to "updated"))) }
            bridge.scripted["catalog"] = { obj("providers" to JSONArray()) }
            bridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray().put(obj("taskId" to "s", "workspacePath" to "/work"))) }
            inst.runOnMainSync { vm.refresh() }
            withTimeout(10_000) { while (state.value.revision != 3L || state.value.historyLoading) delay(20) }
            assertEquals(listOf("old", "new"), state.value.items.map { it.id })
            assertEquals("updated", state.value.items.last().text)
            assertEquals(10.0, state.value.beforeRow!!, 0.0)
        }
    }
    @Test fun anotherLogGenerationIsRejected() = runBlocking {
        fixture { vm, state, bridge ->
            bridge.scripted["session/history"] = { older("other") }
            page(vm)
            assertNotNull(state.value.error); assertEquals(listOf("new"), state.value.items.map { it.id })
        }
    }
    @Test fun pageFromOldGenerationCannotMergeAfterVisibleLogChanges() = runBlocking {
        fixture { vm, state, bridge ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            bridge.scripted["session/history"] = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); older() }
            lateinit var job: Job
            inst.runOnMainSync { job = vm.history() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                inst.runOnMainSync { state.value = state.value.copy(logEpoch = "replacement", revision = 5) }
            } finally { release.countDown() }
            withTimeout(10_000) { job.join() }
            assertEquals(listOf("new"), state.value.items.map { it.id })
        }
    }
}
