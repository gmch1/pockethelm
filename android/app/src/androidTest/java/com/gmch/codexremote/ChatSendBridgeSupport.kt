package com.gmch.codexremote

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Shared support for ViewModel send-path instrumentation tests: an Application whose
 * getSharedPreferences lands in a per-test namespace (the user's vault is never touched),
 * and a scripted JSONL bridge reached through a REAL RpcClient over pipes, injected into
 * the ViewModel's private rpc field by reflection. No network, no real sessions.
 */
class TestPrefsApp(val namespace: String) : Application() {
    lateinit var prefsContext: Context
        private set
    fun attach(base: Context) {
        prefsContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("zcode_load_test_$namespace-$name", mode)
        }
        attachBaseContext(prefsContext)
    }
}

class FakeRpcBridge(private val vm: Any, private val rpcFieldName: String = "rpc") {
    class FakeRpcError(val code: Int, message: String) : Exception(message)

    /** Method -> response for each request of that method; a thrown FakeRpcError becomes an error reply. */
    val scripted = ConcurrentHashMap<String, (JSONObject) -> JSONObject>()
    val unexpected = CopyOnWriteArrayList<String>()
    val errors = ConcurrentLinkedQueue<Throwable>()

    private val serverThread = AtomicReference<Thread?>()
    private val toVm = AtomicReference<PipedInputStream?>()
    private val serverOut = AtomicReference<PipedOutputStream?>()
    private val fromVm = AtomicReference<PipedInputStream?>()
    private val vmOut = AtomicReference<PipedOutputStream?>()

    fun install() {
        val clientIn = PipedInputStream()
        val out = PipedOutputStream(clientIn)
        val serverIn = PipedInputStream()
        val clientOut = PipedOutputStream(serverIn)
        toVm.set(clientIn); serverOut.set(out); fromVm.set(serverIn); vmOut.set(clientOut)
        val field = vm.javaClass.getDeclaredField(rpcFieldName).apply { isAccessible = true }
        field.set(vm, RpcClient(clientIn, clientOut, {}, {}))
        val worker = thread(name = "fake-chat-bridge") {
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
                        out.write(("""{"id":"$id","result":${handler(request)}}""" + "\n").toByteArray())
                    } catch (e: FakeRpcError) {
                        out.write(("""{"id":"$id","error":{"code":${e.code},"message":${JSONObject.quote(e.message)}}}""" + "\n").toByteArray())
                    }
                    out.flush()
                }
            } catch (_: IOException) {
                // Streams were closed by the test teardown; that is the expected exit.
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        serverThread.set(worker)
    }

    /** Closes all four pipe endpoints so both readers hit EOF, then joins the worker. */
    fun close() {
        vmOut.getAndSet(null)?.close()
        serverOut.getAndSet(null)?.close()
        toVm.getAndSet(null)?.close()
        fromVm.getAndSet(null)?.close()
        serverThread.get()?.join(TimeUnit.SECONDS.toMillis(10))
    }

    fun assertClean() {
        check(serverThread.get()?.isAlive != true) { "假桥接器线程未退出" }
        check(errors.isEmpty()) { "假桥接器后台异常：$errors" }
        check(unexpected.isEmpty()) { "收到未脚本化的请求：$unexpected" }
    }
}
