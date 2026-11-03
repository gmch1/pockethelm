package com.gmch.codexremote

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Entering a session fetches quota automatically (task 2026-11-03 quota auto-refresh). */
@RunWith(AndroidJUnit4::class)
class QuotaAutoRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestPrefsApp
    private lateinit var vm: ZcodeViewModel
    private lateinit var bridge: FakeRpcBridge

    private fun fresh() {
        app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        val state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState> }
        state.value = ZcodeState(connected = true,
            selected = ZSession("s1", "集成", "/w", "idw"),
            selection = ZSelection("p", "m"), selectionGuard = "g",
            providers = listOf(ZProvider("p", "计划", "individual-coding-plan", "g",
                listOf(ZModel("m", listOf("low"))))))
        bridge = FakeRpcBridge(vm)
    }

    private fun readResult() = obj("id" to "s1", "title" to "集成", "revision" to 1L, "logEpoch" to "e1",
        "selection" to obj("providerId" to "p", "modelId" to "m", "options" to obj("reasoningLevel" to "low")),
        "mode" to "yolo", "phase" to "idle", "canStop" to false,
        "items" to JSONArray(), "totalRows" to 0, "queue" to JSONArray(),
        "interactionCount" to 0, "interactions" to JSONArray(), "interactionsComplete" to true)

    @Test fun openSessionFetchesQuotaInBackground() = runBlocking {
        fresh()
        val quotaCalls = ConcurrentLinkedQueue<JSONObject>()
        bridge.scripted["session/read"] = { readResult() }
        bridge.scripted["quota"] = { req ->
            quotaCalls.add(req.optJSONObject("params")!!)
            obj("planKind" to "individual-coding-plan", "limits" to JSONArray().put(
                obj("unit" to 3, "number" to 5, "percentage" to 30.0, "currentValue" to 21.0))) }
        bridge.install()
        instrumentation.runOnMainSync { vm.open(ZSession("s1", "集成", "/w", "idw")) }
        withTimeout(15_000) { while (quotaCalls.isEmpty()) delay(20) }
        // The summary bar reflects the fetched quota, not the placeholder.
        withTimeout(10_000) { while (vm.state.value.quotaSummary == "额度 ▾") delay(20) }
        assertTrue(vm.state.value.quotaSummary.contains("剩余"))
        // Dedup: re-opening within the window does not fire a second quota RPC.
        val count = quotaCalls.size
        instrumentation.runOnMainSync { vm.open(ZSession("s1", "集成", "/w", "idw")) }
        delay(1_500)
        assertEquals("30s 内不重复拉取", count, quotaCalls.size)
        instrumentation.runOnMainSync { vm.viewModelScope.cancel() }
        bridge.close()
    }

    @Test fun openWithoutValidSelectionSkipsQuotaSilently() = runBlocking {
        fresh()
        val state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState> }
        state.value = state.value.copy(selection = null, providers = emptyList())
        var quotaCalled = false
        bridge.scripted["session/read"] = { readResult() }
        bridge.scripted["quota"] = { quotaCalled = true; obj("planKind" to "x", "limits" to JSONArray()) }
        bridge.install()
        instrumentation.runOnMainSync { vm.open(ZSession("s1", "集成", "/w", "idw")) }
        delay(1_500)
        assertTrue("无有效选择时静默跳过", !quotaCalled)
        assertEquals("额度 ▾", vm.state.value.quotaSummary)
        instrumentation.runOnMainSync { vm.viewModelScope.cancel() }
        bridge.close()
        bridge.assertClean()
    }

    @After
    fun tearDown() {
        try { if (::vm.isInitialized) instrumentation.runOnMainSync { vm.viewModelScope.cancel() } }
        finally {
            if (::bridge.isInitialized) bridge.close()
            if (::app.isInitialized)
                instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }
}
