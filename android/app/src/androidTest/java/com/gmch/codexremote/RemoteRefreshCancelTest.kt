package com.gmch.codexremote

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Targeted regression for task 2026-11-04-connection-error-leaks: cancelling the
 * refresh tick while its loadSessions RPC is suspended used to surface
 * "StandaloneCoroutine was cancelled" through the runCatching wrapper into the error
 * banner. The fix rethrows CancellationException from refreshTick; ordinary failures
 * must keep being displayed.
 */
@RunWith(AndroidJUnit4::class)
class RemoteRefreshCancelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestPrefsApp
    private lateinit var vm: RemoteViewModel
    private lateinit var state: MutableStateFlow<UiState>
    private lateinit var bridge: FakeRpcBridge

    private fun fresh() {
        app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        instrumentation.runOnMainSync { vm = RemoteViewModel(app) }
        state = RemoteViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as MutableStateFlow<UiState>
        }
        state.value = UiState(connected = true, foreground = true,
            selected = SessionInfo("s1", "集成", "/tmp/remote", "", "idle", 0))
        bridge = FakeRpcBridge(vm)
        bridge.install()
    }

    private suspend fun await(timeoutMs: Long = 15_000, test: (UiState) -> Boolean): Boolean =
        withTimeout(timeoutMs) { while (!test(vm.state.value)) delay(20); true }

    private fun tick(): Job {
        lateinit var job: Job
        instrumentation.runOnMainSync { job = vm.viewModelScope.launch { vm.refreshTick() } }
        return job
    }

    @After
    fun tearDown() {
        try {
            if (::vm.isInitialized) instrumentation.runOnMainSync {
                vm.viewModelScope.cancel()
            }
        } finally {
            if (::bridge.isInitialized) bridge.close()
            if (::app.isInitialized)
                instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
        if (::bridge.isInitialized) bridge.assertClean()
    }

    @Test fun cancellingInFlightRefreshNeverSurfacesError() = runBlocking {
        fresh()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        bridge.scripted["thread/list"] = { _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            obj("data" to JSONArray(), "cursor" to org.json.JSONObject.NULL)
        }
        val job = tick()
        assertTrue(entered.await(15, TimeUnit.SECONDS))
        // Cancel while the RPC is suspended at the latch: the cancellation must be
        // rethrown by refreshTick, never routed into fail().
        instrumentation.runOnMainSync { job.cancel() }
        release.countDown()
        withTimeout(15_000) { while (!job.isCompleted) delay(20) }
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test fun ordinaryRefreshFailureStillDisplayed() = runBlocking {
        fresh()
        bridge.scripted["thread/list"] = { _ ->
            throw FakeRpcBridge.FakeRpcError(-32001, "列表服务不可用")
        }
        val job = tick()
        withTimeout(15_000) { while (!job.isCompleted) delay(20) }
        assertTrue(await { it.error?.contains("列表服务不可用") == true })
        assertEquals("保留普通失败的可见展示", "列表服务不可用", vm.state.value.error)
    }
}
