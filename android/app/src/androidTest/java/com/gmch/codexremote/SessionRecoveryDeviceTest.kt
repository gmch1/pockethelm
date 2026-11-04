package com.gmch.codexremote

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The server keeps accepting requests while A's response is withheld. This tests
 * request ownership, not a real engine's ability to execute RPCs concurrently. */
@RunWith(AndroidJUnit4::class)
class SessionRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class Bridge(val vm: Any) : Closeable {
        val enteredA = CountDownLatch(1)
        val interrupts = java.util.concurrent.atomic.AtomicInteger()
        private val held = AtomicReference<JSONObject?>()
        private val input = PipedInputStream()
        private val output = PipedOutputStream(input)
        private val serverInput = PipedInputStream()
        private val clientOutput = PipedOutputStream(serverInput)
        val rpc = RpcClient(input, clientOutput, {}, {})
        private fun result(request: JSONObject): JSONObject {
            val p = request.getJSONObject("params")
            val id = p.string("threadId").ifBlank { p.string("sessionId") }
            return when (request.string("method")) {
                "turn/interrupt" -> { interrupts.incrementAndGet(); obj() }
                "thread/read" -> obj("thread" to obj("id" to id, "name" to "会话$id", "cwd" to "/tmp/recovery",
                    "status" to obj("type" to "notLoaded")))
                "session/read" -> obj("title" to "会话$id", "items" to JSONArray(), "queue" to JSONArray(),
                    "phase" to "idle", "logEpoch" to "e1", "revision" to 1,
                    "interactionCount" to 0, "interactions" to JSONArray(), "interactionsComplete" to true)
                else -> obj("data" to JSONArray())
            }
        }
        private fun respond(r: JSONObject) = synchronized(output) {
            output.write((obj("id" to r.get("id"), "result" to result(r)).toString() + "\n").toByteArray())
            output.flush()
        }
        private val worker = thread(name = "recovery-test-server") {
            try {
                serverInput.bufferedReader().useLines { lines -> lines.forEach { line ->
                    val r = JSONObject(line)
                    val p = r.getJSONObject("params")
                    if (r.string("method") in listOf("thread/read", "session/read") &&
                        (p.string("threadId") == "A" || p.string("sessionId") == "A")) {
                        held.set(r); enteredA.countDown()
                    } else respond(r)
                } }
            } catch (_: IOException) { }
        }
        init { vm.javaClass.getDeclaredField("rpc").apply { isAccessible = true }.set(vm, rpc) }
        fun releaseA() { held.getAndSet(null)?.let(::respond) }
        override fun close() {
            rpc.close(); clientOutput.close(); output.close(); input.close(); serverInput.close()
            worker.join(2_000); check(!worker.isAlive)
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(vm: Any) = vm.javaClass.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    @Test fun bothEnginesSwitchWhileOldResponseIsMissingAndIgnoreItWhenItArrives() = runBlocking {
        for (zcode in listOf(false, true)) {
            val app = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(instrumentation.targetContext) }
            lateinit var codex: RemoteViewModel; lateinit var z: ZcodeViewModel
            instrumentation.runOnMainSync { if (zcode) z = ZcodeViewModel(app) else codex = RemoteViewModel(app) }
            val vm: Any = if (zcode) z else codex
            val cState = if (!zcode) state<UiState>(vm) else null
            val zState = if (zcode) state<ZcodeState>(vm) else null
            if (zcode) zState!!.value = ZcodeState(connected = true) else cState!!.value = UiState(connected = true)
            Bridge(vm).use { server ->
                try {
                    fun open(id: String) = instrumentation.runOnMainSync {
                        if (zcode) z.open(ZSession(id, id, "/tmp/recovery"))
                        else codex.openThread(SessionInfo(id, id, "/tmp/recovery", "", "idle", 0))
                    }
                    open("A")
                    assertTrue(server.enteredA.await(5, TimeUnit.SECONDS))
                    if (!zcode) {
                        lateinit var interrupt: Job
                        instrumentation.runOnMainSync {
                            cState!!.value = cState.value.copy(selected = cState.value.selected!!.copy(runtime = "active", activeTurnId = "turn-A"))
                            interrupt = codex.interrupt()
                        }
                        withTimeout(2_000) { interrupt.join() }
                        assertEquals("history read must not hold the control-action lock", 1, server.interrupts.get())
                    }
                    open("B")
                    withTimeout(5_000) { while ((zState?.value?.recoveryPhase ?: cState!!.value.recoveryPhase) != RecoveryPhase.Ready) delay(10) }
                    assertEquals("B", zState?.value?.selected?.id ?: cState!!.value.selected?.id)
                    assertFalse(zState?.value?.busy ?: cState!!.value.busy)
                    server.releaseA()
                    // Fence the reader behind the late reply, avoiding a sleep assertion.
                    server.rpc.call("fence", obj(), timeout = 2_000)
                    assertEquals("B", zState?.value?.selected?.id ?: cState!!.value.selected?.id)
                    assertNull(if (zcode) zState!!.value.historyError else cState!!.value.historyError)
                } finally {
                    instrumentation.runOnMainSync {
                        if (zcode) { z.disconnectManually(); z.viewModelScope.cancel() }
                        else { codex.disconnect(); codex.viewModelScope.cancel() }
                    }
                }
            }
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
            app.prefsContext.noBackupFilesDir.deleteRecursively()
        }
    }

    @Test fun explicitDisconnectDoesNotReconnectOnForeground(): Unit = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(instrumentation.targetContext) }
        lateinit var c: RemoteViewModel
        lateinit var z: ZcodeViewModel
        instrumentation.runOnMainSync {
            c = RemoteViewModel(app); z = ZcodeViewModel(app)
            state<UiState>(c).value = UiState(saved = true, connected = true)
            state<ZcodeState>(z).value = ZcodeState(connected = true)
            ZcodeViewModel::class.java.getDeclaredField("active").apply { isAccessible = true }.set(z, true)
            c.disconnectManually(); z.disconnectManually()
            c.foreground(); z.foreground()
            assertFalse(c.state.value.connected); assertFalse(z.state.value.connected)
            assertNull(RemoteViewModel::class.java.getDeclaredField("connectionJob").apply { isAccessible = true }.get(c))
            assertNull(ZcodeViewModel::class.java.getDeclaredField("job").apply { isAccessible = true }.get(z))
            c.viewModelScope.cancel(); z.viewModelScope.cancel()
        }
        instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        app.prefsContext.noBackupFilesDir.deleteRecursively()
    }

    @Test fun detailTimeoutFinishesWithoutErrorOnNextSuccessfulRecovery(): Unit = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(instrumentation.targetContext) }
        lateinit var vm: RemoteViewModel
        instrumentation.runOnMainSync { vm = RemoteViewModel(app); vm.recovery.detailBudgetMs = 120 }
        val state = state<UiState>(vm); state.value = UiState(connected = true)
        Bridge(vm).use {
            try {
                instrumentation.runOnMainSync { vm.openThread(SessionInfo("A", "A", "/tmp/recovery", "", "idle", 0)) }
                withTimeout(5_000) { while (state.value.recoveryPhase != RecoveryPhase.Failed) delay(10) }
                assertFalse(state.value.historyLoading)
                assertFalse(state.value.busy)
                instrumentation.runOnMainSync { vm.openThread(SessionInfo("B", "B", "/tmp/recovery", "", "idle", 0)) }
                withTimeout(5_000) { while (state.value.recoveryPhase != RecoveryPhase.Ready) delay(10) }
                assertNull(state.value.historyError); assertNull(state.value.error)
            } finally { instrumentation.runOnMainSync { vm.disconnect(); vm.viewModelScope.cancel() } }
        }
        instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        app.prefsContext.noBackupFilesDir.deleteRecursively()
    }
}
