package com.gmch.codexremote

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ZcodeListRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestPrefsApp
    private lateinit var vm: ZcodeViewModel
    private lateinit var state: MutableStateFlow<ZcodeState>
    private lateinit var bridge: FakeRpcBridge

    private fun setup(status: String = "completed", connected: Boolean = true) {
        app = TestPrefsApp(UUID.randomUUID().toString()).apply { attach(instrumentation.targetContext) }
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        @Suppress("UNCHECKED_CAST")
        state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as MutableStateFlow<ZcodeState>
        }
        val a = ZSession("a", "旧会话", "/test", status = status, updatedAt = 10)
        state.value = ZcodeState(connected = connected, selected = a, sessionsFresh = true,
            sessions = listOf(ZSession("b", "较新会话", "/test", updatedAt = 20), a))
        bridge = FakeRpcBridge(vm).also { it.install() }
    }

    private fun refreshTo(status: String) {
        bridge.scripted["projects"] = {
            obj("projects" to JSONArray(), "sessions" to JSONArray()
                .put(obj("taskId" to "b", "updatedAt" to 1_800_000_000_000L))
                .put(obj("taskId" to "a", "status" to status, "updatedAt" to 1_900_000_000_000L)))
        }
    }

    private suspend fun await(test: (ZcodeState) -> Boolean) = withTimeout(10_000) {
        while (!test(state.value)) delay(20)
    }

    @Test fun completedSessionReturnsRunningAndMovesToTop() = runBlocking {
        setup(); refreshTo("running")
        state.value = state.value.copy(phase = "running")
        instrumentation.runOnMainSync { vm.back() }
        await { it.selected == null && it.sessionsFresh && it.sessions.firstOrNull()?.status == "running" }
        assertEquals(listOf("a", "b"), state.value.sessions.map { it.id })
        assertEquals("running", state.value.sessions.first().status)
    }

    @Test fun runningSessionReturnsCompleted() = runBlocking {
        setup("running"); refreshTo("completed")
        state.value = state.value.copy(phase = "completedSuccess")
        instrumentation.runOnMainSync { vm.back() }
        await { it.sessionsFresh && it.selected == null && it.sessions.firstOrNull()?.status == "completed" }
        assertEquals("completed", state.value.sessions.first().status)
    }

    @Test fun failedRefreshKeepsOldListExplicitlyStale() = runBlocking {
        setup()
        val before = state.value.sessions
        bridge.scripted["projects"] = { throw FakeRpcBridge.FakeRpcError(-32000, "离线测试") }
        instrumentation.runOnMainSync { vm.back() }
        await { it.error != null && !it.busy }
        assertFalse(state.value.sessionsFresh)
        assertEquals(before, state.value.sessions)
        assertNull(state.value.selected)
    }

    @Test fun offlineBackDoesNotRequestAndInvalidatesSnapshot() {
        setup(connected = false)
        instrumentation.runOnMainSync { vm.back() }
        assertFalse(state.value.sessionsFresh)
        assertEquals(2, state.value.sessions.size)
        assertNull(state.value.selected)
    }

    @After fun cleanup() {
        if (::vm.isInitialized) instrumentation.runOnMainSync { vm.viewModelScope.cancel() }
        if (::bridge.isInitialized) { bridge.close(); bridge.assertClean() }
        if (::app.isInitialized) {
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }
}
