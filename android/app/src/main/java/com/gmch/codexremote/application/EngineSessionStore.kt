package com.gmch.codexremote.application

import com.gmch.codexremote.engines.contract.*

/** Implementations must atomically persist an operation before dispatch. Unknown
 * operations are never evicted or replayed automatically. */
internal interface EngineSessionStore {
    fun draft(session: EngineSessionRef): String
    fun saveDraft(session: EngineSessionRef, text: String)
    fun pending(origin: EngineOrigin): List<PendingOperation>
    fun savePending(operation: PendingOperation)
    fun clearPending(origin: EngineOrigin, commandId: String)
}
internal class MemoryEngineSessionStore : EngineSessionStore {
    private val drafts = mutableMapOf<EngineSessionRef, String>()
    private val operations = mutableMapOf<Pair<EngineOrigin, String>, PendingOperation>()
    override fun draft(session: EngineSessionRef) = drafts[session].orEmpty()
    override fun saveDraft(session: EngineSessionRef, text: String) { drafts[session] = text }
    override fun pending(origin: EngineOrigin) = operations.filterKeys { it.first == origin }.values.toList()
    override fun savePending(operation: PendingOperation) { operations[operation.origin to operation.id] = operation }
    override fun clearPending(origin: EngineOrigin, commandId: String) { operations.remove(origin to commandId) }
}
