package com.gmch.codexremote.engines.zcode

import com.gmch.codexremote.*
import com.gmch.codexremote.engines.contract.*
import com.gmch.codexremote.engines.engineItem
import com.gmch.codexremote.transport.RpcEndpoint
import org.json.JSONObject

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
        if (expected != null) check(data.string("logEpoch") == expected.getString("epoch") &&
            data.optLong("revision") == expected.getLong("revision")) { "会话已更新，请刷新后重试" }
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
}
