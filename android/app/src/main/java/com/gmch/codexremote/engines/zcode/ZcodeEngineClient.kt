package com.gmch.codexremote.engines.zcode

import com.gmch.codexremote.*
import com.gmch.codexremote.engines.contract.*
import com.gmch.codexremote.engines.engineItem
import com.gmch.codexremote.transport.RpcEndpoint
import org.json.JSONObject
import org.json.JSONArray

internal data class ZcodeSession(override val ref: EngineSessionRef, val native: ZSession) : EngineSession {
    override val title get() = native.title
    override val path get() = native.path
    override val updatedAtMillis get() = native.updatedAt
}
internal data class ZcodeProject(override val origin: EngineOrigin, val native: ZProject) : EngineProject {
    override val nativeId get() = native.id
    override val title get() = native.name
    override val roots get() = native.roots
}
internal data class ZcodeCreated(override val session: ZcodeSession) : EngineCreatedSession
internal class ZcodeEngineClient(override val origin: EngineOrigin, private val rpc: RpcEndpoint) : EngineClient {
    init { require(origin.engine == "zcode") }
    override val capabilities = EngineReadCapabilities(SessionCatalogMode.Snapshot, true)
    fun session(value: ZSession) = EngineSessionRef(origin, value.id, value.path, value.identity)
    private fun target(session: EngineSessionRef) = obj("sessionId" to session.nativeId,
        "workspacePath" to session.workspacePath, "workspaceIdentity" to session.workspaceIdentity.takeIf { it.isNotBlank() })
    override suspend fun projects(): ProjectCatalog<ZcodeProject, ZcodeSession> {
        val data = rpc.call("projects", timeout = 90_000)
        return ProjectCatalog(data.optJSONArray("projects").objects().map {
            ZcodeProject(origin, ZProject(it.getString("id"), it.getString("name"), it.optJSONArray("roots").strings()))
        }, SessionPage(data.optJSONArray("sessions").objects().map(ZSession::parse).sortedByDescending { it.updatedAt }
            .map { ZcodeSession(session(it), it) }, null, SessionCatalogMode.Snapshot))
    }
    override suspend fun sessions(query: SessionQuery): SessionPage<ZcodeSession> {
        validate(query)
        if (query.cursor != null || query.limit != null) throw UnsupportedEngineQuery("ZCode 会话目录是完整快照，不支持列表分页")
        return projects().sessions!!
    }
    fun cursor(session: EngineSessionRef, before: Double, epoch: String, revision: Long): HistoryCursor {
        validate(session, null)
        require(before.isFinite() && before >= 0 && epoch.isNotBlank() && revision >= 0) { "历史游标无效，请刷新会话" }
        return HistoryCursor(session, obj("before" to before, "epoch" to epoch, "revision" to revision).toString())
    }
    fun before(cursor: HistoryCursor): Double {
        validate(cursor.session, cursor)
        return JSONObject(cursor.token).getDouble("before")
    }
    private fun page(session: EngineSessionRef, data: JSONObject, expected: JSONObject?): HistoryPage {
        // Row-boundary pagination remains valid as the same log advances. Only a
        // different log generation or a response older than the cursor is unsafe.
        if (expected != null) check(data.string("logEpoch") == expected.getString("epoch") &&
            data.optLong("revision") >= expected.getLong("revision")) { "会话已更新，请刷新后重试" }
        val rowField = if (expected == null) "firstRowId" else "beforeRowId"
        val next = if (data.optBoolean("hasMore") && !data.isNull(rowField))
            cursor(session, data.getDouble(rowField), data.string("logEpoch"), data.optLong("revision")) else null
        return HistoryPage(session, zcodeItems(data).map { it.engineItem() }, next)
    }
    override suspend fun history(session: EngineSessionRef, cursor: HistoryCursor?, onPage: (suspend (HistoryPage) -> Unit)?): HistoryPage {
        validate(session, cursor)
        require(session.workspacePath.startsWith('/')) { "会话工作区路径无效" }
        val expected = cursor?.let { JSONObject(it.token) }
        val params = target(session)
        if (expected != null) {
            val before = expected.getDouble("before")
            // Validate opaque cursor contents before any native RPC.
            this.cursor(session, before, expected.getString("epoch"), expected.getLong("revision"))
            params.put("beforeRowId", before)
        }
        var decoded: HistoryPage? = null
        val data = rpc.call(if (cursor == null) "session/read" else "session/history", params, timeout = 90_000,
            onResult = if (onPage == null) null else { value ->
                val snapshot = page(session, value, expected); decoded = snapshot; onPage(snapshot)
            })
        return decoded ?: page(session, data, expected)
    }
    override val operations = setOf(EngineOperation.Create, EngineOperation.Send, EngineOperation.Interrupt, EngineOperation.CommandQuery)
    override suspend fun choices(): List<EngineChoice> = zcodeProviders(rpc.call("catalog", timeout = 90_000)).flatMap { provider ->
        provider.models.flatMap { model -> model.levels.ifEmpty { listOf("") }.map { level ->
            EngineChoice(origin, provider.id, model.id, level, provider.guard, provider.kind,
                "${provider.name} · ${model.id}" + if (level.isBlank()) "" else " · $level")
        } }
    }
    private fun selection(choice: EngineChoice?): JSONObject {
        require(choice != null && choice.origin == origin && choice.provider.isNotBlank() && choice.guard.isNotBlank()) { "请选择当前引擎有效的计划和模型" }
        return obj("selection" to ZSelection(choice.provider, choice.model, choice.level).json(), "guard" to choice.guard)
    }
    fun messageParams(message: EngineMessage): JSONObject {
        validate(message.session, null)
        require(message.session.workspacePath.startsWith('/')) { "会话工作区路径无效" }
        require(message.commandId.isNotBlank() && (message.text.isNotBlank() || message.attachments.isNotEmpty())) { "请输入消息或添加图片" }
        return target(message.session).apply {
            val selected = selection(message.choice)
            put("selection", selected.getJSONObject("selection")); put("guard", selected.getString("guard"))
            put("text", message.text); put("delivery", if (message.delivery == MessageDelivery.Steer) "guide" else "queue")
            put("commandId", message.commandId)
            if (message.attachments.isNotEmpty()) put("attachments", JSONArray().apply { message.attachments.forEach {
                require(it.session == message.session && it.reference.isNotBlank()) { "附件引用不属于当前会话" }
                put(obj("ref" to it.reference, "fileName" to it.fileName, "mime" to it.mime, "bytes" to it.bytes))
            } })
        }
    }
    private fun accepted(ack: JSONObject): JSONObject {
        when (val status = ack.string("status")) {
            "accepted", "duplicate", "noop" -> return ack.optJSONObject("result") ?: obj()
            "rejected", "stale", "failed" -> throw EngineRejectedException(ack.string("reasonCode", status), "ZCode 未接受命令：${ack.string("reasonCode", status)}")
            else -> throw java.io.IOException("命令状态未知，请刷新查询")
        }
    }
    private suspend fun command(method: String, params: JSONObject): JSONObject = try {
        rpc.call(method, params, timeout = 90_000).also { ack ->
            if (ack.has("commandId")) check(ack.string("commandId") == params.string("commandId")) { "回执不属于本次命令，请核对" }
        }
    } catch (e: RpcException) {
        if (e.code == -32602) throw EngineRejectedException(e.code.toString(), e.message.orEmpty())
        throw e
    }
    fun createParams(request: EngineCreateRequest): JSONObject {
        require(request.title.isBlank()) { "当前 ZCode 创建接口不支持自定义标题" }
        require(request.workspacePath.startsWith('/') && request.projectId.isNotBlank()) { "请选择单目录项目" }
        return selection(request.choice).put("workspacePath", request.workspacePath).put("projectId", request.projectId).put("commandId", request.commandId)
    }
    override suspend fun create(request: EngineCreateRequest, beforeDispatch: suspend () -> Unit): ZcodeCreated {
        val params = createParams(request)
        beforeDispatch()
        val result = accepted(command("session/create", params))
        val id = result.string("sessionId")
        check(id.isNotBlank()) { "创建结果缺少会话 ID，请核对目录；不要重复创建" }
        val native = ZSession(id, "新会话", request.workspacePath, project = request.projectId)
        return ZcodeCreated(ZcodeSession(session(native), native))
    }
    /** Compatibility extension for advanced ZCode controls. This allowlist is not
     * exposed through the engine-neutral client and cannot forward arbitrary RPC. */
    suspend fun nativeCommand(method: String, params: JSONObject): JSONObject {
        require(method in setOf("session/create", "session/send", "session/stop", "session/queue/remove", "session/queue/edit", "session/queue/promote"))
        val ack = command(method, params)
        accepted(ack)
        return ack
    }
    suspend fun readNative(session: EngineSessionRef, checkInteractionIds: List<String> = emptyList()): ZcodeSnapshot {
        validate(session, null)
        val params = target(session)
        if (checkInteractionIds.isNotEmpty()) params.put("checkInteractionIds", JSONArray(checkInteractionIds))
        return ZcodeSnapshot.parse(rpc.call("session/read", params, timeout = 90_000))
    }
    override suspend fun inspect(session: EngineSessionRef): EngineSessionSnapshot {
        val data = readNative(session)
        val next = if (data.hasMore && data.firstRowId != null) cursor(session, data.firstRowId, data.logEpoch, data.revision) else null
        val running = when (data.phase) { "running", "working" -> true; "idle", "completed", "done", "draft" -> false; else -> null }
        return EngineSessionSnapshot(session, data.title,
            HistoryPage(session, data.items.map { it.engineItem() }, next), running, data.executionId,
            data.canStop, data.queue.map { EngineQueuedMessage(it.id, it.clientId, it.text) },
            data.selection?.let { EngineChoice(origin, it.provider, it.model, it.level) })
    }
    override suspend fun send(message: EngineMessage, beforeDispatch: suspend () -> Unit): EngineReceipt {
        val params = messageParams(message)
        beforeDispatch()
        val result = accepted(command("session/send", params))
        val delivery = when (result.string("delivery")) { "queue" -> ReceiptDelivery.Queued; "guide" -> ReceiptDelivery.Steered; else -> ReceiptDelivery.Accepted }
        return EngineReceipt(message.commandId, delivery, message.session)
    }
    override suspend fun interrupt(session: EngineSessionRef, runId: String, commandId: String) {
        validate(session, null); require(runId.isNotBlank()) { "当前没有可停止的执行" }
        accepted(command("session/stop", target(session).put("executionId", runId).put("commandId", commandId)))
    }
    suspend fun query(session: EngineSessionRef, commandId: String): JSONObject? {
        validate(session, null)
        return rpc.call("command/query", target(session).put("commandId", commandId), timeout = 90_000).optJSONObject("ack")
    }

    override suspend fun resolve(operation: PendingOperation): CommandResolution {
        require(operation.origin == origin)
        operation.session?.let { validate(it, null) }
        val path = operation.session?.workspacePath ?: operation.workspacePath
        require(path.startsWith('/'))
        val ack = rpc.call("command/query", obj("workspacePath" to path,
            "workspaceIdentity" to operation.session?.workspaceIdentity?.takeIf { it.isNotBlank() },
            "sessionId" to operation.session?.nativeId, "commandId" to operation.id), timeout = 90_000).optJSONObject("ack")
            ?: return CommandResolution(CommandResolutionState.Unknown)
        if (ack.has("commandId") && ack.string("commandId") != operation.id) return CommandResolution(CommandResolutionState.Unknown)
        return when (ack.string("status")) {
            "accepted", "duplicate", "noop" -> {
                val result = ack.optJSONObject("result") ?: obj()
                val target = if (operation.kind == PendingKind.Create) {
                    val id = result.string("sessionId")
                    if (id.isBlank()) return CommandResolution(CommandResolutionState.Unknown)
                    EngineSessionRef(origin, id, path)
                } else operation.session
                CommandResolution(CommandResolutionState.Accepted, target,
                    when (result.string("delivery")) { "queue" -> ReceiptDelivery.Queued; "guide" -> ReceiptDelivery.Steered; else -> ReceiptDelivery.Accepted })
            }
            "rejected", "stale", "failed" -> CommandResolution(CommandResolutionState.Rejected, operation.session, reason = ack.string("reasonCode"))
            else -> CommandResolution(CommandResolutionState.Unknown)
        }
    }

}
