package com.gmch.codexremote.engines.contract

/** Logical source identity, stable across reconnects but not across environments or
 * configured runtime instances. A client is additionally bound to one live transport. */
internal data class EngineOrigin(val environment: String, val engine: String, val instance: String) {
    init { require(environment.isNotBlank() && engine.isNotBlank() && instance.isNotBlank()) }
}
internal data class EngineSessionRef(val origin: EngineOrigin, val nativeId: String,
    val workspacePath: String = "", val workspaceIdentity: String = "") {
    init { require(nativeId.isNotBlank()) }
}
internal interface EngineSession {
    val ref: EngineSessionRef
    val title: String
    val path: String
    val updatedAtMillis: Long
}
internal interface EngineProject {
    val origin: EngineOrigin
    val nativeId: String
    val title: String
    val roots: List<String>
}
internal enum class SessionCatalogMode { Paged, Snapshot }
internal data class EngineReadCapabilities(val sessions: SessionCatalogMode, val historyPaging: Boolean)
internal data class SessionCursor(val origin: EngineOrigin, val token: String)
internal data class HistoryCursor(val session: EngineSessionRef, val token: String)
internal data class SessionQuery(val cursor: SessionCursor? = null, val limit: Int? = null) {
    init { require(limit == null || limit in 1..100) }
}
internal data class SessionPage<out S : EngineSession>(val items: List<S>, val next: SessionCursor?,
    val mode: SessionCatalogMode)
/** Some runtimes return sessions and projects in one authoritative catalog RPC. */
internal data class ProjectCatalog<out P : EngineProject, out S : EngineSession>(
    val projects: List<P>, val sessions: SessionPage<S>? = null)
internal data class EngineTimelineItem(val id: String, val kind: String, val text: String,
    val status: String = "", val clientId: String = "", val entityId: String = "")
/** Items are oldest-to-newest within this window; this is not a full-history reset. */
internal data class HistoryPage(val session: EngineSessionRef, val items: List<EngineTimelineItem>,
    val next: HistoryCursor?)
internal class UnsupportedEngineQuery(message: String) : UnsupportedOperationException(message)

/** Read-side session port only. No JSON, native ViewModel, write/retry or fabricated
 * approval capability. Engine-specific read results may add typed native metadata. */
internal interface EngineClient {
    val origin: EngineOrigin
    val capabilities: EngineReadCapabilities
    suspend fun sessions(query: SessionQuery = SessionQuery()): SessionPage<EngineSession>
    suspend fun projects(): ProjectCatalog<EngineProject, EngineSession>
    suspend fun history(session: EngineSessionRef, cursor: HistoryCursor? = null,
        onPage: (suspend (HistoryPage) -> Unit)? = null): HistoryPage
}
internal fun EngineClient.validate(session: EngineSessionRef, cursor: HistoryCursor?) {
    require(session.origin == origin) { "会话不属于当前引擎实例" }
    require(cursor == null || cursor.session == session) { "历史游标不属于当前会话" }
}
internal fun EngineClient.validate(query: SessionQuery) {
    require(query.cursor == null || query.cursor.origin == origin) { "列表游标不属于当前引擎实例" }
}
