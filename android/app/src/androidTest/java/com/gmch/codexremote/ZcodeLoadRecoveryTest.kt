package com.gmch.codexremote

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Regression coverage for the first-load error path: create()/refresh() must go through
 * readGuarded so a failed first session/read clears historyLoading and surfaces historyError.
 * The ViewModel's private rpc/mutable/pending are reflectively injected; RPCs run through a
 * real RpcClient against a scripted in-process bridge, so the production read wiring is
 * exercised end to end. No network, no real sessions, no user vault: the application's
 * getSharedPreferences is redirected to an isolated per-test namespace that is deleted after.
 */
@RunWith(AndroidJUnit4::class)
class ZcodeLoadRecoveryTest {
    private class TestPrefsContext(base: Context, private val namespace: String) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("zcode_load_test_$namespace-$name", mode)
    }

    private class TestApp(private val namespace: String) : Application() {
        lateinit var prefsContext: Context
            private set
        fun attach(base: Context) {
            prefsContext = TestPrefsContext(base, namespace)
            attachBaseContext(prefsContext)
        }
    }

    private class FakeRpcError(val code: Int, message: String) : Exception(message)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestApp
    private lateinit var vm: ZcodeViewModel
    private lateinit var state: MutableStateFlow<ZcodeState>

    // Method -> response for the next request of that method. A result is sent verbatim under
    // "result"; a thrown FakeRpcError becomes an "error" reply. Any other exception or an
    // unexpected method is recorded so the test fails even though the bridge kept answering.
    private val scripted = ConcurrentHashMap<String, () -> JSONObject>()
    private val unexpected = CopyOnWriteArrayList<String>()
    private val serverErrors = ConcurrentLinkedQueue<Throwable>()
    private val serverThread = AtomicReference<Thread?>()

    // All four pipe endpoints are kept so teardown can force EOF on both readers.
    private val toVm = AtomicReference<PipedInputStream?>()
    private val serverOut = AtomicReference<PipedOutputStream?>()
    private val fromVm = AtomicReference<PipedInputStream?>()
    private val vmOut = AtomicReference<PipedOutputStream?>()

    private fun installBridge() {
        val clientIn = PipedInputStream()
        val out = PipedOutputStream(clientIn)
        val serverIn = PipedInputStream()
        val clientOut = PipedOutputStream(serverIn)
        toVm.set(clientIn); serverOut.set(out); fromVm.set(serverIn); vmOut.set(clientOut)
        val rpcField = ZcodeViewModel::class.java.getDeclaredField("rpc").apply { isAccessible = true }
        rpcField.set(vm, RpcClient(clientIn, clientOut, {}, {}))
        val worker = thread(name = "fake-zcode-bridge") {
            try {
                val reader = BufferedReader(InputStreamReader(serverIn, Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val request = JSONObject(line)
                    val id = request.string("id")
                    val method = request.string("method")
                    try {
                        val handler = scripted[method]
                        if (handler == null) {
                            unexpected.add(method)
                            throw FakeRpcError(-32603, "意外请求：$method")
                        }
                        // Raw strings keep JSON verbatim; the newline must come from a normal
                        // string or readLine() would wait forever behind a literal "\n".
                        out.write(("""{"id":"$id","result":${handler()}}""" + "\n").toByteArray())
                    } catch (e: FakeRpcError) {
                        out.write(("""{"id":"$id","error":{"code":${e.code},"message":${JSONObject.quote(e.message)}}}""" + "\n").toByteArray())
                    }
                    out.flush()
                }
            } catch (_: IOException) {
                // Streams were closed by the test teardown; that is the expected exit.
            } catch (t: Throwable) {
                serverErrors.add(t)
            }
        }
        serverThread.set(worker)
    }

    private fun readPayload(withItems: Boolean) = obj(
        "items" to (if (withItems) JSONArray().put(obj("id" to "r1", "kind" to "assistantText",
            "text" to "POCKETHELM_LOAD_OK", "status" to "success")) else JSONArray()),
        "logEpoch" to "e1", "revision" to 1L, "title" to "加载恢复", "phase" to "idle",
        "queue" to JSONArray(), "mode" to "yolo", "hasMore" to false
    )

    private fun freshViewModel(connected: Boolean = false) {
        app = TestApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as MutableStateFlow<ZcodeState>
        }
        state.value = ZcodeState(connected = connected)
        installBridge()
    }

    private fun seedSelection() {
        state.value = state.value.copy(
            providers = listOf(ZProvider("p1", "计划一", "subscription", "guard1", listOf(ZModel("m1", emptyList())))),
            selection = ZSelection("p1", "m1"), selectionGuard = "guard1")
    }

    // busy must be false before asserting terminal values: the action's finally still owns
    // error/busy until it runs, so waiting only for the happy condition could race it.
    private fun awaitState(timeoutMs: Long = 15_000, test: (ZcodeState) -> Boolean) = runBlocking {
        withTimeout(timeoutMs) { while (!test(vm.state.value) || vm.state.value.busy) delay(20) }
    }

    @After
    fun tearDown() {
        try {
            if (::vm.isInitialized) instrumentation.runOnMainSync {
                vm.leave()
                vm.viewModelScope.cancel()
            }
        } finally {
            // close() on RpcClient does not close its streams: close our endpoints ourselves
            // so both readers hit EOF and the worker thread can exit.
            vmOut.getAndSet(null)?.close()
            serverOut.getAndSet(null)?.close()
            toVm.getAndSet(null)?.close()
            fromVm.getAndSet(null)?.close()
            serverThread.get()?.join(TimeUnit.SECONDS.toMillis(10))
            if (::app.isInitialized) {
                val ns = app.namespace()
                instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_$ns-vault")
            }
        }
        assertFalse("假桥接器线程未退出", serverThread.get()?.isAlive == true)
        assertTrue("假桥接器后台异常：$serverErrors", serverErrors.isEmpty())
        assertTrue("收到未脚本化的请求：$unexpected", unexpected.isEmpty())
    }

    private fun TestApp.namespace(): String {
        val field = TestApp::class.java.getDeclaredField("namespace").apply { isAccessible = true }
        return field.get(this) as String
    }

    @Test fun createFailsFirstReadThenRetryRecovers() = runBlocking {
        freshViewModel(connected = true)
        seedSelection()
        val reads = AtomicInteger()
        scripted["session/create"] = { obj("status" to "accepted", "result" to obj("sessionId" to "sess_create")) }
        scripted["session/read"] = {
            if (reads.incrementAndGet() == 1) throw FakeRpcError(-32000, "首次读取失败")
            readPayload(withItems = true)
        }
        instrumentation.runOnMainSync { vm.create(ZProject("proj", "测试项目", listOf("/tmp/zcode-load-test"))) }

        awaitState { it.selected?.id == "sess_create" && it.historyError != null && !it.historyLoading }
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals("首次读取失败", vm.state.value.historyError)
        assertEquals(TimelineView.Error, timelineView(vm.state.value.items.size, vm.state.value.historyLoading, vm.state.value.historyError))

        instrumentation.runOnMainSync { vm.retryLoad() }
        awaitState { it.historyError == null && !it.historyLoading && it.items.isNotEmpty() }
        assertEquals(2, reads.get())
        assertEquals("POCKETHELM_LOAD_OK", vm.state.value.items.single().text)
        assertEquals("加载恢复", vm.state.value.selected?.title)
        assertEquals(TimelineView.Content, timelineView(vm.state.value.items.size, vm.state.value.historyLoading, vm.state.value.historyError))
        assertEquals(null, vm.state.value.error)
    }

    @Test fun refreshAfterRecoveredCreateReportsHistoryError() = runBlocking {
        freshViewModel(connected = true)
        val pendingField = ZcodeViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }
        pendingField.set(vm, obj("method" to "session/create",
            "params" to obj("commandId" to "cmd-1", "workspacePath" to "/tmp/zcode-load-test", "projectId" to "proj")))
        scripted["command/query"] = { obj("ack" to obj("status" to "accepted", "result" to obj("sessionId" to "sess_recovered"))) }
        scripted["catalog"] = { obj("providers" to JSONArray()) }
        scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
        scripted["session/read"] = { throw FakeRpcError(-32000, "恢复后首次读取失败") }

        instrumentation.runOnMainSync { vm.refresh() }

        awaitState { it.selected?.id == "sess_recovered" && it.historyError != null && !it.historyLoading }
        assertEquals("恢复后首次读取失败", vm.state.value.historyError)
        assertTrue(vm.state.value.items.isEmpty())
        assertFalse(vm.state.value.pending)
        assertEquals(TimelineView.Error, timelineView(vm.state.value.items.size, vm.state.value.historyLoading, vm.state.value.historyError))
        // Once the bridge recovers, refresh must be able to load the recovered session.
        scripted["session/read"] = { readPayload(withItems = true) }
        instrumentation.runOnMainSync { vm.retryLoad() }
        awaitState { it.historyError == null && !it.historyLoading && it.items.isNotEmpty() }
        assertEquals("POCKETHELM_LOAD_OK", vm.state.value.items.single().text)
    }
}
