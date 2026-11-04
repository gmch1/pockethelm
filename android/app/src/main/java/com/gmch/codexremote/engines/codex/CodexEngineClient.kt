package com.gmch.codexremote.engines.codex

import com.gmch.codexremote.*
import com.gmch.codexremote.engines.contract.*
import com.gmch.codexremote.engines.engineItem
import com.gmch.codexremote.transport.RpcEndpoint
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
}
