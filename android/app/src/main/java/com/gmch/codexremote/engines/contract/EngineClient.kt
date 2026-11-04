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

/** Session port with explicit read/write capabilities. Native adapters own protocol
 * and acknowledgement semantics; callers must not automatically replay unknown writes. */
internal interface EngineClient {
    val origin: EngineOrigin
    val capabilities: EngineReadCapabilities
    val operations: Set<EngineOperation> get() = emptySet()
    suspend fun choices(): List<EngineChoice> = emptyList()
    suspend fun resolve(operation: PendingOperation): CommandResolution =
        throw UnsupportedEngineQuery("该引擎没有命令回执查询能力")
    suspend fun inspect(session: EngineSessionRef): EngineSessionSnapshot {
        validate(session, null)
        return EngineSessionSnapshot(session, session.nativeId, history(session), running = null)
    }
    suspend fun create(request: EngineCreateRequest, beforeDispatch: suspend () -> Unit = {}): EngineCreatedSession =
        throw UnsupportedEngineQuery("该引擎不支持创建会话")
    suspend fun send(message: EngineMessage, beforeDispatch: suspend () -> Unit = {}): EngineReceipt =
        throw UnsupportedEngineQuery("该引擎不支持发送消息")
    suspend fun interrupt(session: EngineSessionRef, runId: String, commandId: String) {
        throw UnsupportedEngineQuery("该引擎不支持打断")
    }
    suspend fun rename(session: EngineSessionRef, title: String) {
        throw UnsupportedEngineQuery("该引擎不支持重命名")
    }
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
