package com.gmch.codexremote.engines.codex

import com.gmch.codexremote.*
import com.gmch.codexremote.engines.contract.*
import com.gmch.codexremote.engines.engineItem
import com.gmch.codexremote.transport.RpcEndpoint
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class CodexSession(override val ref: EngineSessionRef, val native: SessionInfo) : EngineSession {
    override val title get() = native.title
    override val path get() = native.cwd
    override val updatedAtMillis get() = native.updatedAt * 1000
}
internal data class CodexProject(override val origin: EngineOrigin, val path: String) : EngineProject {
    override val nativeId get() = path
    override val title get() = path.substringAfterLast('/')
    override val roots get() = listOf(path)
}
internal data class CodexTurnHead(val id: String, val status: String)
internal data class CodexAttachment(val response: JSONObject, val unmaterialized: Boolean, val session: EngineSessionRef, internal val owner: RpcEndpoint)
internal data class CodexCreated(override val session: CodexSession, val settings: ModelSettings) : EngineCreatedSession
internal class CodexEngineClient(override val origin: EngineOrigin, private val rpc: RpcEndpoint,
    private val root: String) : EngineClient {
    init { require(origin.engine == "codex") }
    override val capabilities = EngineReadCapabilities(SessionCatalogMode.Paged, true)
    fun session(id: String) = EngineSessionRef(origin, id)
    private fun validateTarget(session: EngineSessionRef, cursor: HistoryCursor?) {
        validate(session, cursor)
        require(session.workspacePath.isEmpty() && session.workspaceIdentity.isEmpty()) { "Codex 会话通过原生 ID 定位" }
    }
    fun cursor(token: String?) = token?.takeIf { it.isNotBlank() }?.let { SessionCursor(origin, it) }
    override suspend fun sessions(query: SessionQuery): SessionPage<CodexSession> {
        validate(query)
        val page = rpc.call("thread/list", obj("limit" to (query.limit ?: 30), "sortKey" to "updated_at",
            "sortDirection" to "desc", "cursor" to query.cursor?.token,
            "sourceKinds" to JSONArray(listOf("cli", "vscode", "appServer", "exec", "unknown"))))
        return SessionPage(page.optJSONArray("data").objects().map(SessionInfo::parse).map { CodexSession(session(it.id), it) },
            cursor(page.string("nextCursor")), SessionCatalogMode.Paged)
    }
    override suspend fun projects(): ProjectCatalog<CodexProject, Nothing> {
        // Preserve the configured root, including the filesystem root itself.
        val path = root.trimEnd('/').ifEmpty { if (root.startsWith('/')) "/" else "" }
        val data = rpc.call("fs/readDirectory", obj("path" to path))
        val projects = data.optJSONArray("entries").objects().filter { it.optBoolean("isDirectory") && !it.string("fileName").startsWith('.') }
            .map { CodexProject(origin, path.trimEnd('/') + "/" + it.string("fileName")) }.sortedBy { it.path }
        return ProjectCatalog(projects)
    }
    suspend fun latestTurn(session: EngineSessionRef, timeout: Long = 45_000): CodexTurnHead? {
        validateTarget(session, null)
        val turn = rpc.call("thread/turns/list", obj("threadId" to session.nativeId, "limit" to 1,
            "sortDirection" to "desc", "itemsView" to "notLoaded"), timeout = timeout).optJSONArray("data")?.optJSONObject(0)
        return turn?.let { CodexTurnHead(it.string("id"), it.string("status")) }
    }
    private fun page(session: EngineSessionRef, value: JSONObject) = HistoryPage(session,
        value.optJSONArray("data").objects().mapNotNull { it.optJSONObject("item") }.map(TimelineItem::parse).reversed().map { it.engineItem() },
        value.string("nextCursor").takeIf { it.isNotBlank() }?.let { HistoryCursor(session, it) })
    override suspend fun history(session: EngineSessionRef, cursor: HistoryCursor?, onPage: (suspend (HistoryPage) -> Unit)?): HistoryPage {
        validateTarget(session, cursor)
        // Preserve receive-order snapshot application; do not move this callback to
        // the caller's continuation, which could run after newer streaming events.
        var decoded: HistoryPage? = null
        val result = rpc.call("thread/items/list", obj("threadId" to session.nativeId, "cursor" to cursor?.token,
            "limit" to 80, "sortDirection" to "desc"), onResult = if (onPage == null) null else { value ->
                val snapshot = page(session, value); decoded = snapshot; onPage(snapshot)
            })
        return decoded ?: page(session, result)
    }
    override val operations = setOf(EngineOperation.Create, EngineOperation.Send, EngineOperation.Interrupt, EngineOperation.Rename)
    override suspend fun choices(): List<EngineChoice> {
        val result = mutableListOf<EngineChoice>(); val seen = mutableSetOf<String>()
        var next: String? = null
        do {
            val page = rpc.call("model/list", obj("limit" to 100, "includeHidden" to false, "cursor" to next))
            page.optJSONArray("data").objects().filter { !it.optBoolean("hidden") }.map(ModelOption::parse).filter { it.id.isNotBlank() }.forEach {
                val model = it
                (model.efforts + listOfNotNull(model.defaultEffort.takeIf(String::isNotBlank))).distinct().ifEmpty { listOf("") }.forEach { level ->
                    result += EngineChoice(origin, model = model.id, level = level, label = model.name + if (level.isBlank()) "" else " · $level")
                }
            }
            next = page.string("nextCursor").ifBlank { null }
            check(next == null || seen.add(next)) { "模型分页游标重复" }
        } while (next != null)
        return result.distinctBy { it.model to it.level }
    }
    private fun checkChoice(choice: EngineChoice?) {
        require(choice == null || choice.origin == origin && choice.provider.isEmpty()) { "模型选择不属于当前引擎" }
    }
    private fun fullAccess(data: JSONObject) {
        check(data.string("approvalPolicy") == "never" && data.optJSONObject("sandbox")?.string("type") == "dangerFullAccess") {
            "服务器未启用 YOLO，未发送任务。请检查服务器策略。"
        }
    }
    suspend fun attach(ref: EngineSessionRef, fullAccess: Boolean = false,
        onSettings: (suspend (ModelSettings) -> Unit)? = null): CodexAttachment {
        validateTarget(ref, null)
        try {
            return CodexAttachment(rpc.call("thread/resume", (if (fullAccess) yolo() else obj())
                .put("threadId", ref.nativeId).put("excludeTurns", true), onResult = onSettings?.let { apply -> { value -> apply(ModelSettings.parse(value)) } }), false, ref, rpc)
        } catch (e: RpcException) {
            val id = ref.nativeId
            if (e.code != -32600 || e.message != "no rollout found for thread id $id") throw e
            val metadata = rpc.call("thread/read", obj("threadId" to id, "includeTurns" to false)).getJSONObject("thread")
            if (metadata.string("id") != id || metadata.optJSONObject("status")?.string("type") !in setOf("idle", "active")) throw e
            try { latestTurn(ref) }
            catch (history: RpcException) {
                if (history.code == -32600 && history.message == "thread $id is not materialized yet; thread/turns/list is unavailable before first user message")
                    return CodexAttachment(obj("thread" to metadata), true, ref, rpc)
                throw e
            }
            throw e
        }
    }
    override suspend fun create(request: EngineCreateRequest, beforeDispatch: suspend () -> Unit): CodexCreated {
        require(request.workspacePath.startsWith('/')) { "请选择项目绝对路径" }
        checkChoice(request.choice)
        beforeDispatch()
        val data = try { rpc.call("thread/start", yolo().put("cwd", request.workspacePath).put("historyMode", "paginated").apply {
            request.choice?.let { put("model", it.model) }
        }) } catch (e: RpcException) { throw EngineRejectedException(e.code.toString(), e.message.orEmpty()) }
        fullAccess(data)
        val native = SessionInfo.parse(data.getJSONObject("thread"))
        val ref = session(native.id)
        val title = request.title.trim().take(120).ifBlank { "新会话" }
        try {
            rpc.call("thread/name/set", obj("threadId" to ref.nativeId, "name" to title))
            request.choice?.level?.takeIf { it.isNotBlank() }?.let {
                rpc.call("thread/settings/update", obj("threadId" to ref.nativeId, "effort" to it))
            }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw EngineCreationUnconfirmed(ref, e) }
        return CodexCreated(CodexSession(ref, native.copy(title = title)), ModelSettings.parse(data))
    }
    override suspend fun inspect(session: EngineSessionRef): EngineSessionSnapshot {
        validateTarget(session, null)
        val attached = attach(session)
        val metadata = SessionInfo.parse(attached.response.getJSONObject("thread"))
        val empty = attached.unmaterialized
        val turn = if (empty) null else latestTurn(session)
        val history = if (empty) HistoryPage(session, emptyList(), null) else history(session)
        val running = when (metadata.runtime) { "active" -> true; "idle" -> false; else -> null }
        val runId = if (running == true && turn?.status == "inProgress") turn.id else ""
        val queue = if (metadata.runtime == "notLoaded" || empty) emptyList() else
            rpc.call("thread/queue/list", obj("threadId" to session.nativeId, "limit" to 100)).optJSONArray("data").objects().map(QueuedMessage::parse)
                .map { EngineQueuedMessage(it.id, it.clientId, it.text) }
        val settings = ModelSettings.parse(attached.response)
        val choice = settings.model?.takeIf { it.isNotBlank() }?.let { EngineChoice(origin, model = it, level = settings.effort.orEmpty()) }
        return EngineSessionSnapshot(session, metadata.title, history, running, runId, runId.isNotBlank(), queue, choice)
    }
    override suspend fun send(message: EngineMessage, beforeDispatch: suspend () -> Unit): EngineReceipt {
        validateTarget(message.session, null); checkChoice(message.choice)
        val attached = attach(message.session, fullAccess = true)
        return sendAttached(message, attached, beforeDispatch)
    }
    suspend fun sendAttached(message: EngineMessage, attached: CodexAttachment, beforeDispatch: suspend () -> Unit): EngineReceipt {
        validateTarget(message.session, null); checkChoice(message.choice)
        require(attached.owner === rpc && attached.session == message.session) { "会话连接已变化，请重新准备发送" }
        require(message.commandId.isNotBlank() && (message.text.isNotBlank() || message.attachments.isNotEmpty())) { "请输入消息或添加图片" }
        if (!attached.unmaterialized) fullAccess(attached.response)
        val metadata = SessionInfo.parse(attached.response.getJSONObject("thread"))
        require(metadata.id == message.session.nativeId) { "恢复目标与发送会话不一致" }
        if (attached.unmaterialized) check(metadata.runtime == "idle") { "会话正在初始化，请稍后刷新再发送；草稿已保留" }
        val steering = message.delivery == MessageDelivery.Steer
        if (steering) check(metadata.runtime == "active" && message.expectedRunId.isNotBlank()) { "当前没有可介入的执行，请刷新后重试" }
        check(!steering || message.attachments.isEmpty()) { "带图片的消息暂不支持介入执行，请关闭插入模式" }
        val paths = message.attachments.map {
            require(it.session == message.session && it.reference.matches(Regex("/tmp/pockethelm-img-[A-Za-z0-9_-]+\\.jpg"))) { "图片引用不属于当前会话" }
            it.reference
        }
        if (metadata.runtime == "active" && message.choice != null) {
            val settings = ModelSettings.parse(attached.response)
            require(settings.model == message.choice.model && (message.choice.level.isBlank() || settings.effort == message.choice.level)) { "执行期间请先核对模型设置" }
        }
        val params = obj("threadId" to message.session.nativeId, "clientUserMessageId" to message.commandId,
            "input" to messageInput(message.text, paths))
        val delivery = when { steering -> ReceiptDelivery.Steered; metadata.runtime == "active" -> ReceiptDelivery.Queued; else -> ReceiptDelivery.Accepted }
        beforeDispatch()
        try {
            when (delivery) {
                ReceiptDelivery.Steered -> rpc.call("turn/steer", params.put("expectedTurnId", message.expectedRunId))
                ReceiptDelivery.Queued -> rpc.call("thread/queue/add", params)
                ReceiptDelivery.Accepted -> rpc.call("turn/start", params.put("approvalPolicy", "never").put("sandboxPolicy", obj("type" to "dangerFullAccess")).apply {
                    message.choice?.let { put("model", it.model); if (it.level.isNotBlank()) put("effort", it.level) }
                })
            }
        } catch (e: RpcException) { throw EngineRejectedException(e.code.toString(), e.message.orEmpty()) }
        return EngineReceipt(message.commandId, delivery, message.session)
    }
    override suspend fun interrupt(session: EngineSessionRef, runId: String, commandId: String) {
        validateTarget(session, null); require(runId.isNotBlank()) { "执行轮次尚未同步，请刷新后重试" }
        try { rpc.call("turn/interrupt", obj("threadId" to session.nativeId, "turnId" to runId)) }
        catch (e: RpcException) { throw EngineRejectedException(e.code.toString(), e.message.orEmpty()) }
    }
    override suspend fun rename(session: EngineSessionRef, title: String) {
        validateTarget(session, null); require(title.isNotBlank())
        rpc.call("thread/name/set", obj("threadId" to session.nativeId, "name" to title.trim().take(120)))
    }

}
