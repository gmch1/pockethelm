package com.gmch.codexremote

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
    pairs.forEach { (k, v) -> if (v != null) put(k, v) }
}
fun JSONObject.string(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).map { optString(it) }
fun textInput(text: String) = JSONArray().put(obj("type" to "text", "text" to text))
/** Codex local-image input item: the path is App-generated and points at dev-machine /tmp. */
fun imageInput(path: String) = obj("type" to "localImage", "path" to path, "detail" to "auto")
/** Message input mixing optional text with images: no empty text item for image-only sends. */
fun messageInput(text: String, imagePaths: List<String> = emptyList()): JSONArray {
    val input = if (text.isNotBlank()) textInput(text) else JSONArray()
    imagePaths.forEach { input.put(imageInput(it)) }
    return input
}
fun yolo() = obj("approvalPolicy" to "never", "sandbox" to "danger-full-access")
fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

/** Placeholder label for image input items in timelines — never the path or data URL. */
fun imageItemLabel(item: JSONObject): String = when (item.string("type")) {
    "localImage" -> "[图片] " + item.string("path").substringAfterLast('/').takeLast(64)
    "image" -> "[图片]"
    else -> item.string("text", "[${item.string("type")}]")
}

data class SessionInfo(
    val id: String, val title: String, val cwd: String, val preview: String,
    val runtime: String, val updatedAt: Long, val model: String = "",
    val lastTurnStatus: String = "", val activeTurnId: String = ""
) {
    val project get() = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }
    val status get() = when {
        runtime == "active" -> "执行中"
        lastTurnStatus == "failed" || runtime == "systemError" -> "失败"
        lastTurnStatus == "interrupted" -> "已中断"
        lastTurnStatus == "completed" -> "已完成"
        runtime == "notLoaded" -> "未加载"
        else -> "空闲"
    }
    companion object {
        fun parse(j: JSONObject): SessionInfo {
            val turn = j.optJSONArray("turns").objects().lastOrNull()
            val name = j.string("name").ifBlank { j.string("preview").lineSequence().firstOrNull().orEmpty() }
            return SessionInfo(j.getString("id"), name.ifBlank { "新会话" }.take(120), j.string("cwd"),
                j.string("preview"), j.optJSONObject("status")?.string("type") ?: "notLoaded",
                j.optLong("updatedAt"), j.string("model"), turn?.string("status").orEmpty(),
                if (turn?.string("status") == "inProgress") turn.string("id") else "")
        }
    }
}

data class TimelineItem(val id: String, val kind: String, val text: String, val status: String = "", val clientId: String = "",
                        val entityId: String = "") {
    companion object {
        fun parse(j: JSONObject): TimelineItem {
            val type = j.string("type")
            val text = when (type) {
                "userMessage" -> j.optJSONArray("content").objects().joinToString("\n") { imageItemLabel(it) }
                "agentMessage", "plan" -> j.string("text")
                "reasoning" -> j.optJSONArray("summary").strings().joinToString("\n")
                "commandExecution" -> "$ ${j.string("command")}\n${j.string("aggregatedOutput")}"
                "fileChange" -> j.optJSONArray("changes").objects().joinToString("\n\n") { "${it.string("path")}\n${it.string("diff")}" }
                "mcpToolCall" -> "${j.string("server")} / ${j.string("tool")}\n${j.opt("result")?.takeUnless { it == JSONObject.NULL } ?: ""}${j.opt("error")?.takeUnless { it == JSONObject.NULL } ?: ""}"
                "webSearch" -> "搜索：${j.string("query")}"
                "contextCompaction" -> "正在整理上下文"
                else -> j.toString(2)
            }
            return TimelineItem(j.string("id"), type, text.takeLast(120_000), j.string("status"), j.string("clientId"))
        }
    }
}

data class QueuedMessage(val id: String, val clientId: String, val text: String) {
    companion object {
        fun parse(j: JSONObject) = QueuedMessage(j.string("id"), j.string("clientUserMessageId"),
            j.optJSONArray("input").objects().joinToString("\n") { it.string("text") })
    }
}

/**
 * A server-initiated approval request awaiting this client's answer. The request id is
 * echoed back verbatim when answering: [key] keeps number 7 and string "7" distinct, and
 * [generation] binds the card to the connection it arrived on so a stale answer can never
 * be written to a new connection.
 */
data class PendingApproval(
    val key: String, val rawId: Any, val generation: Int, val kind: String,
    val threadId: String, val turnId: String, val itemId: String,
    val body: String, val cwd: String, val grantRoot: String,
    val stringDecisions: List<String>, val hasObjectDecisions: Boolean,
    val advertised: Boolean, val sent: Boolean = false
) {
    /** Buttons this client may render: the supported set, intersected with the server's
     *  list when it advertised one. An advertised list with no renderable entries means
     *  this client cannot answer at all — not a license to show every button. */
    val decisions: List<String> get() =
        if (!advertised) DECISIONS else (DECISIONS + "cancel").filter { it in stringDecisions }

    companion object {
        val DECISIONS = listOf("accept", "acceptForSession", "decline")

        /** Type-sensitive identity: JSON number 7 and string "7" are different requests. */
        fun keyOf(id: Any?) = when (id) {
            is String -> "s:$id"
            is Int, is Long -> "n:$id"
            else -> "u:${id?.javaClass?.simpleName ?: "null"}:$id"
        }

        fun parse(message: JSONObject, generation: Int): PendingApproval? {
            val method = message.string("method")
            if (!message.has("id")) return null
            val p = message.optJSONObject("params") ?: JSONObject()
            val kind = when (method) {
                "item/commandExecution/requestApproval" ->
                    if (p.string("kind") == "writeStdin") "writeStdin" else "command"
                "item/fileChange/requestApproval" -> "fileChange"
                else -> return null
            }
            // Elements are the decisions themselves: plain strings, or object-form policy
            // amendments this client cannot render (surfaced via hasObjectDecisions).
            val advertised = p.optJSONArray("availableDecisions")
            val strings = mutableListOf<String>()
            var hasObjects = false
            if (advertised != null) (0 until advertised.length()).forEach { i ->
                when (val element = advertised.opt(i)) {
                    is String -> strings.add(element)
                    else -> if (element != null) hasObjects = true
                }
            }
            return PendingApproval(
                key = keyOf(message.opt("id")), rawId = message.opt("id")!!, generation = generation,
                kind = kind, threadId = p.string("threadId"), turnId = p.string("turnId"),
                itemId = p.string("itemId"),
                body = if (kind == "fileChange") p.string("reason") else p.string("command"),
                cwd = p.string("cwd"), grantRoot = p.string("grantRoot"),
                stringDecisions = strings.distinct(), hasObjectDecisions = hasObjects,
                advertised = advertised != null)
        }
    }
}

/** Called at the snapshot's position in the RPC stream, before subsequent deltas. */
internal fun applyHistorySnapshot(
    timeline: MutableMap<String, TimelineItem>, snapshot: List<TimelineItem>, baseline: Map<String, TimelineItem>
) {
    val snapshotIds = snapshot.mapTo(HashSet()) { it.id }
    val unlisted = timeline.filter { (id, item) -> id !in snapshotIds && baseline[id] != item }
    timeline.clear()
    snapshot.forEach { timeline[it.id] = it }
    // Keep new live items that were not included in this history page.
    timeline.putAll(unlisted)
}

class RpcException(val code: Int, message: String) : Exception(message)

/** The server answered with a definite command rejection (rejected/stale/failed). */
class AckRejectedException(message: String) : Exception(message)

/** JSONL only: no terminal, no shell output scraping, no retry of write requests. */
class RpcClient(
    input: InputStream, private val output: OutputStream,
    private val onEvent: suspend (JSONObject) -> Unit,
    private val onClosed: (Throwable) -> Unit
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Request(
        val result: CompletableDeferred<JSONObject>, val onResult: (suspend (JSONObject) -> Unit)?
    )
    private val pending = ConcurrentHashMap<String, Request>()
    private val serial = AtomicLong(0)
    private val writes = Mutex()
    @Volatile private var closed = false
    private val reader = input.bufferedReader(Charsets.UTF_8)

    init {
        scope.launch {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: throw java.io.EOFException("SSH 通道已关闭")
                    if (line.isBlank()) continue
                    val message = JSONObject(line)
                    if (message.has("method")) onEvent(message)
                    else pending[message.string("id")]?.let { request ->
                        try {
                            // Apply snapshots in wire order, not in the caller's later continuation.
                            // The reader does not dispatch another delta until this callback returns.
                            if (!message.has("error") && request.result.isActive)
                                request.onResult?.invoke(message.optJSONObject("result") ?: obj())
                            request.result.complete(message)
                        } catch (e: CancellationException) { request.result.completeExceptionally(e); throw e }
                        catch (t: Throwable) { request.result.completeExceptionally(t) }
                    }
                }
            } catch (t: Throwable) {
                if (!closed) { fail(t); onClosed(t) }
            }
        }
    }

    suspend fun call(
        method: String, params: JSONObject = obj(), timeout: Long = 45_000,
        onResult: (suspend (JSONObject) -> Unit)? = null
    ): JSONObject {
        check(!closed) { "连接已关闭" }
        val id = serial.incrementAndGet().toString()
        val result = CompletableDeferred<JSONObject>()
        pending[id] = Request(result, onResult)
        try {
            write(obj("id" to id, "method" to method, "params" to params))
            val response = try {
                withTimeout(timeout) { result.await() }
            } catch (e: TimeoutCancellationException) {
                // A single-request timeout is a transport failure, not caller cancellation:
                // reads must surface as a normal failure and writes must keep their pending
                // protection (never auto-retried here). If the caller itself was cancelled,
                // that cancellation propagates as cancellation instead.
                if (!currentCoroutineContext().isActive) throw e
                throw java.io.IOException("请求超时（${timeout / 1000} 秒），请检查开发机连接")
            }
            response.optJSONObject("error")?.let { throw RpcException(it.optInt("code"), it.string("message")) }
            return response.optJSONObject("result") ?: obj()
        } finally { pending.remove(id); result.cancel() }
    }

    suspend fun initialize(): JSONObject {
        val result = call("initialize", obj(
            "clientInfo" to obj("name" to "codex_remote_android", "title" to "PocketHelm", "version" to "0.3.1"),
            "capabilities" to obj("experimentalApi" to true)))
        write(obj("method" to "initialized"))
        return result
    }

    /** Answer a server-initiated request. The id must be echoed back verbatim, including type. */
    suspend fun respond(id: Any, result: JSONObject) = write(obj("id" to id, "result" to result))

    suspend fun write(message: JSONObject) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(!closed) { "连接已关闭" }
            output.write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun fail(t: Throwable) {
        closed = true
        pending.values.forEach { it.result.completeExceptionally(t) }
        pending.clear()
    }
    override fun close() {
        fail(java.io.EOFException("连接已断开"))
        scope.cancel()
        // The transport owner closes streams/sockets, releasing a blocking read.
    }
}
