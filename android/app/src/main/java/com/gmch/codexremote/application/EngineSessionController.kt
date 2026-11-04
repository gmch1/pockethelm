package com.gmch.codexremote.application

import com.gmch.codexremote.*
import com.gmch.codexremote.engines.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class EngineSessionState(val origin: EngineOrigin? = null, val connected: Boolean = false,
    val sessions: List<EngineSession> = emptyList(), val sessionCursor: SessionCursor? = null, val projects: List<EngineProject> = emptyList(),
    val selected: EngineSessionRef? = null, val snapshot: EngineSessionSnapshot? = null,
    val choices: List<EngineChoice> = emptyList(), val choice: EngineChoice? = null, val choicesLoaded: Boolean = false,
    val draft: String = "", val readingOlder: Boolean = false, val loading: Boolean = false, val working: Boolean = false,
    val pending: List<PendingOperation> = emptyList(), val outgoing: List<OutgoingMessage> = emptyList(),
    val error: String? = null, val storeHealthy: Boolean = true)

/** Engine-neutral conversation workflows. Native adapters own commands and their
 * acknowledgement semantics. This controller never resends an unknown operation. */
internal class EngineSessionController(private val scope: CoroutineScope, private val store: EngineSessionStore) {
    private val mutable = MutableStateFlow(EngineSessionState())
    val state = mutable.asStateFlow()
    private var client: EngineClient? = null
    private var epoch = 0
    private var navigation = 0
    private var refreshVersion = 0
    private var entryChoicePending = false
    private var readJob: Job? = null
    private var reconciliation: Job? = null
    private val writes = Mutex()
    private val actions = mutableSetOf<Job>()
    private val unsavedDrafts = mutableMapOf<EngineSessionRef, String>()
    private val ledger = OutgoingLedger { publishOutgoing() }
    private fun parts(vararg values: String) = values.joinToString("") { "${it.length}:$it" }
    private fun key(ref: EngineSessionRef) = OutgoingKey(parts(ref.origin.environment, ref.origin.engine, ref.origin.instance),
        parts(ref.nativeId, ref.workspacePath, ref.workspaceIdentity))
    private fun current(generation: Int, selected: EngineSessionRef? = mutable.value.selected) = generation == epoch && mutable.value.selected == selected
    private fun publishOutgoing() { mutable.value.selected?.let { ref -> mutable.update { it.copy(outgoing = ledger.records[key(ref)].orEmpty().toList()) } } }
    private fun publishPending(origin: EngineOrigin) {
        val records = store.pending(origin)
        if (mutable.value.origin == origin) {
            for (record in records) if (record.kind == PendingKind.Send && record.session != null && ledger.records[key(record.session)]?.any { it.clientId == record.id } != true)
                ledger.add(key(record.session), OutgoingMessage(record.session.nativeId, record.id, record.text, OutgoingState.UNCERTAIN))
            mutable.update { it.copy(pending = records, storeHealthy = it.selected !in unsavedDrafts) }
        }
    }
    fun bind(next: EngineClient?) {
        if (client === next) return
        epoch++; readJob?.cancel(); reconciliation?.cancel(); actions.toList().forEach { it.cancel() }
        mutable.value.selected?.let { ledger.uncertain(key(it)) }
        val old = mutable.value
        client = next
        if (next == null) { mutable.update { it.copy(connected = false, loading = false, working = false) }; return }
        if (old.origin != next.origin) {
            navigation++; entryChoicePending = false; ledger.records.clear()
            mutable.value = EngineSessionState(origin = next.origin, connected = true)
        } else mutable.update { it.copy(connected = true, loading = false, working = false, choicesLoaded = false) }
        try { publishPending(next.origin) }
        catch (e: Exception) { mutable.update { it.copy(storeHealthy = false, error = "本地回执无法读取，已停止写操作：${e.message}") } }
    }
    fun draft(text: String) {
        val ref = mutable.value.selected ?: return
        unsavedDrafts[ref] = text
        mutable.update { it.copy(draft = text) }
        try { store.saveDraft(ref, text); unsavedDrafts.remove(ref); mutable.update { it.copy(storeHealthy = true) } }
        catch (e: Exception) { mutable.update { it.copy(error = e.message, storeHealthy = false) } }
    }
    fun choose(choice: EngineChoice) {
        if (choice !in mutable.value.choices || choice.origin != mutable.value.origin) return
        entryChoicePending = false
        mutable.update { it.copy(choice = choice) }
    }
    fun open(session: EngineSessionRef) {
        if (session.origin != mutable.value.origin) return
        navigation++; readJob?.cancel(); entryChoicePending = true
        try {
            mutable.update { it.copy(selected = session, snapshot = null, readingOlder = false, choice = null, draft = unsavedDrafts[session] ?: store.draft(session), storeHealthy = session !in unsavedDrafts, error = null,
                outgoing = ledger.records[key(session)].orEmpty().toList()) }
        } catch (e: Exception) { mutable.update { it.copy(error = e.message, storeHealthy = false) }; return }
        refresh()
    }
    fun back() { navigation++; entryChoicePending = false; readJob?.cancel(); mutable.update { it.copy(selected = null, snapshot = null, readingOlder = false, draft = "", outgoing = emptyList(), loading = false) } }
    fun refresh(refreshChoices: Boolean = true): Job? {
        if (!refreshChoices && mutable.value.readingOlder) return null
        val connection = client ?: return null
        val generation = epoch; val selection = navigation; val selected = mutable.value.selected
        val request = ++refreshVersion
        fun valid() = current(generation, selected) && navigation == selection && refreshVersion == request
        reconcileCommands(connection, generation)
        readJob?.cancel()
        return scope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                if (selected != null && selected in unsavedDrafts) {
                    store.saveDraft(selected, unsavedDrafts.getValue(selected)); unsavedDrafts.remove(selected)
                    mutable.update { it.copy(storeHealthy = true) }
                }
                withTimeout(60_000) {
                    withContext(RecoveryDeadline(System.nanoTime() + 60_000_000_000L)) {
                        if (selected == null) {
                            val projects = connection.projects()
                            val sessions = projects.sessions ?: connection.sessions()
                            if (valid()) mutable.update { it.copy(projects = projects.projects, sessions = sessions.items, sessionCursor = sessions.next) }
                        } else {
                            val snapshot = connection.inspect(selected)
                            require(snapshot.session == selected) { "会话快照来源不一致" }
                            if (valid()) {
                                mutable.update { it.copy(snapshot = snapshot, readingOlder = false) }
                                val messages = snapshot.history.items.filter { it.kind == "userMessage" }.map { it.clientId }.filter { it.isNotBlank() }.toSet()
                                val queued = snapshot.queue.map { it.clientId }.filter { it.isNotBlank() }.toSet()
                                ledger.reconcile(key(selected), messages, queued)
                                store.pending(connection.origin).filter { it.kind == PendingKind.Send && it.session == selected && it.id in messages + queued }.forEach {
                                    clearConfirmedDraft(it)
                                    store.clearPending(connection.origin, it.id)
                                }
                                publishPending(connection.origin)
                            }
                        }
                        if (refreshChoices || !mutable.value.choicesLoaded || entryChoicePending) {
                        val choices = connection.choices()
                        if (valid()) mutable.update { s ->
                            val actual = s.snapshot?.selection
                            val selectedChoice = if (entryChoicePending && actual != null)
                                choices.find { it.sameSelection(actual, checkGuard = false) }
                            else s.choice?.let { old -> choices.find { it.sameSelection(old) } }
                            entryChoicePending = false
                            s.copy(choices = choices, choicesLoaded = true, choice = selectedChoice)
                        }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                if (currentCoroutineContext().isActive && valid()) mutable.update { it.copy(error = "读取超时，请重试") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (valid()) mutable.update { it.copy(error = e.message) } }
            finally { if (valid()) mutable.update { it.copy(loading = false) } }
        }.also { readJob = it }
    }
    private fun write(block: suspend (EngineClient, Int) -> Unit): Job {
        val connection = client ?: return scope.launch { mutable.update { it.copy(error = "请先连接引擎") } }
        val generation = epoch
        val selected = mutable.value.selected; val selection = navigation
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { writes.withLock {
                if (generation != epoch || client !== connection || navigation != selection || mutable.value.selected != selected) return@withLock
                check(mutable.value.storeHealthy) { "本地回执不可用，不能发送" }
                mutable.update { it.copy(working = true, error = null) }
                try { block(connection, generation) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (generation == epoch) mutable.update { it.copy(error = e.message) } }
                finally { if (generation == epoch) mutable.update { it.copy(working = false) } }
            } } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch) mutable.update { it.copy(error = e.message) } }
            finally { actions.remove(job) }
        }
        actions += job; job.start(); return job
    }
    private fun requireChoice() {
        check(!entryChoicePending && mutable.value.choicesLoaded) { "请先读取模型配置" }
        check(mutable.value.choices.isEmpty() || mutable.value.choice != null) { "请选择模型及来源" }
    }
    fun create(project: EngineProject, title: String = ""): Job = write { connection, generation ->
        check(EngineOperation.Create in connection.operations)
        require(project.origin == connection.origin && project.roots.size == 1)
        requireChoice()
        check(store.pending(connection.origin).none { it.kind == PendingKind.Create }) { "上一次创建结果尚未确认，请先核对" }
        val requestedNavigation = navigation
        val request = EngineCreateRequest(UUID.randomUUID().toString(), project.roots.single(), title, project.nativeId, mutable.value.choice)
        val pending = PendingOperation(connection.origin, request.commandId, PendingKind.Create, workspacePath = request.workspacePath, projectId = project.nativeId, text = title)
        var dispatched = false
        try {
            val result = connection.create(request) { store.savePending(pending); dispatched = true; publishPending(connection.origin) }
            require(result.session.ref.origin == connection.origin) { "创建结果来源不一致，请核对目录" }
            store.clearPending(connection.origin, request.commandId)
            if (generation == epoch) {
                publishPending(connection.origin)
                if (navigation == requestedNavigation) open(result.session.ref)
                else mutable.update { it.copy(sessions = (it.sessions + result.session).distinctBy { row -> row.ref }) }
            }
        } catch (e: EngineRejectedException) { store.clearPending(connection.origin, request.commandId); publishPending(connection.origin); throw e }
        catch (e: Exception) {
            if (dispatched && e is EngineCreationUnconfirmed && e.session != null) {
                store.savePending(pending.copy(session = e.session)); publishPending(connection.origin)
            }
            if (dispatched && generation == epoch) mutable.update { it.copy(error = "创建结果待确认，请核对目录；不会自动重试") }
            if (e is CancellationException) throw e
            if (!dispatched) throw e
        }
    }
    fun send(delivery: MessageDelivery = MessageDelivery.Auto): Job = write { connection, generation ->
        val ref = mutable.value.selected ?: error("请先选择会话")
        val text = mutable.value.draft.trim(); require(text.isNotEmpty()) { "请输入消息" }; requireChoice()
        check(EngineOperation.Send in connection.operations)
        check(store.pending(connection.origin).none { it.session == ref }) { "上一条操作结果待确认，请先核对" }
        val message = EngineMessage(ref, UUID.randomUUID().toString(), text, delivery, mutable.value.snapshot?.runId.orEmpty(), mutable.value.choice)
        val record = PendingOperation(connection.origin, message.commandId, PendingKind.Send, ref, text = text)
        ledger.add(key(ref), OutgoingMessage(ref.nativeId, message.commandId, text, OutgoingState.SENDING))
        var dispatched = false
        try {
            val receipt = connection.send(message) { store.savePending(record); dispatched = true; publishPending(connection.origin) }
            check(receipt.commandId == message.commandId && receipt.session == ref) { "回执与当前请求不一致，请核对" }
            ledger.update(key(ref), message.commandId) { it.copy(state = if (receipt.delivery == ReceiptDelivery.Queued) OutgoingState.QUEUED else OutgoingState.ACCEPTED,
                receipt = when (receipt.delivery) { ReceiptDelivery.Queued -> "已排队"; ReceiptDelivery.Steered -> "已插入"; ReceiptDelivery.Accepted -> "已接收" }) }
            store.clearPending(connection.origin, message.commandId)
            if (store.draft(ref).trim() == text) store.saveDraft(ref, "")
            if (current(generation, ref)) { mutable.update { it.copy(draft = unsavedDrafts[ref] ?: store.draft(ref)) }; publishPending(connection.origin); refresh() }
        } catch (e: EngineRejectedException) {
            store.clearPending(connection.origin, message.commandId)
            ledger.update(key(ref), message.commandId) { it.copy(state = OutgoingState.FAILED) }
            publishPending(connection.origin); throw e
        } catch (e: Exception) {
            if (dispatched) ledger.uncertain(key(ref), message.commandId)
            else ledger.update(key(ref), message.commandId) { it.copy(state = OutgoingState.FAILED) }
            if (e is CancellationException) throw e
            throw e
        }
    }
    fun interrupt(): Job = write { connection, generation ->
        val snapshot = mutable.value.snapshot ?: error("请先核实运行状态")
        check(snapshot.canInterrupt && EngineOperation.Interrupt in connection.operations)
        val pending = PendingOperation(connection.origin, UUID.randomUUID().toString(), PendingKind.Interrupt, snapshot.session)
        // Stop is not replayed if its outcome is lost. It has no model billing side effect.
        store.savePending(pending); publishPending(connection.origin)
        try {
            connection.interrupt(snapshot.session, snapshot.runId, pending.id)
            store.clearPending(connection.origin, pending.id)
            if (generation == epoch) { publishPending(connection.origin); refresh() }
        } catch (e: EngineRejectedException) { store.clearPending(connection.origin, pending.id); publishPending(connection.origin); throw e }
    }
    fun moreSessions(): Job? {
        val connection = client ?: return null
        val cursor = mutable.value.sessionCursor ?: return null
        val generation = epoch; val selection = navigation; val request = refreshVersion
        if (mutable.value.selected != null) return null
        return scope.launch {
            try {
                val page = connection.sessions(SessionQuery(cursor))
                if (!current(generation, null) || navigation != selection || refreshVersion != request || mutable.value.sessionCursor != cursor) return@launch
                mutable.update { it.copy(sessions = (it.sessions + page.items).distinctBy { row -> row.ref }, sessionCursor = page.next) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(generation, null)) mutable.update { it.copy(error = e.message) } }
        }
    }

    fun moreHistory(): Job? {
        val connection = client ?: return null
        val snapshot = mutable.value.snapshot ?: return null
        val cursor = snapshot.history.next ?: return null
        val generation = epoch; val selection = navigation; val request = ++refreshVersion
        readJob?.cancel()
        mutable.update { it.copy(readingOlder = true, loading = false) }
        return scope.launch {
            try {
                val page = connection.history(snapshot.session, cursor)
                if (!current(generation, snapshot.session) || navigation != selection || refreshVersion != request ||
                    mutable.value.snapshot?.history?.next != cursor) return@launch
                val latest = mutable.value.snapshot ?: return@launch
                val merged = linkedMapOf<String, EngineTimelineItem>()
                page.items.forEach { merged[it.id] = it }
                latest.history.items.forEach { merged[it.id] = it }
                mutable.update { it.copy(snapshot = latest.copy(history = latest.history.copy(items = merged.values.toList(), next = page.next))) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(generation, snapshot.session) && navigation == selection && refreshVersion == request) mutable.update { it.copy(error = e.message) } }
        }
    }

    private fun reconcileCommands(connection: EngineClient, generation: Int) {
        if (mutable.value.working || EngineOperation.CommandQuery !in connection.operations || reconciliation?.isActive == true) return
        reconciliation = scope.launch {
            try {
                withTimeout(45_000) { withContext(RecoveryDeadline(System.nanoTime() + 45_000_000_000L)) {
                    for (record in store.pending(connection.origin)) {
                        val answer = connection.resolve(record)
                        if (generation != epoch || client !== connection) return@withContext
                        if (mutable.value.working || answer.state == CommandResolutionState.Unknown) continue
                        store.clearPending(connection.origin, record.id)
                        if (record.kind == PendingKind.Send && record.session != null) {
                            if (answer.state == CommandResolutionState.Accepted) {
                                ledger.update(key(record.session), record.id) { it.copy(state = if (answer.delivery == ReceiptDelivery.Queued) OutgoingState.QUEUED else OutgoingState.ACCEPTED) }
                                if (store.draft(record.session).trim() == record.text) store.saveDraft(record.session, "")
                                if (mutable.value.selected == record.session) mutable.update { it.copy(draft = unsavedDrafts[record.session] ?: store.draft(record.session)) }
                            } else ledger.update(key(record.session), record.id) { it.copy(state = OutgoingState.FAILED) }
                        }
                        publishPending(connection.origin)
                    }
                } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch) mutable.update { it.copy(error = "回执核对未完成：${e.message}") } }
        }
    }

    private fun clearConfirmedDraft(record: PendingOperation) {
        val ref = record.session ?: return
        if (store.draft(ref).trim() == record.text) store.saveDraft(ref, "")
        if (mutable.value.selected == ref) mutable.update { it.copy(draft = unsavedDrafts[ref] ?: store.draft(ref)) }
    }

    /** Explicit user confirmation, not inference from missing rows or matching text. */
    fun acknowledgeUnknown(id: String) {
        val origin = mutable.value.origin ?: return
        if (mutable.value.working) return
        try {
            val record = store.pending(origin).find { it.id == id } ?: return
            store.clearPending(origin, id)
            record.session?.let { ledger.remove(key(it), id) }
            publishPending(origin)
        } catch (e: Exception) { mutable.update { it.copy(error = e.message, storeHealthy = false) } }
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun pause() { refreshVersion++; readJob?.cancel(); reconciliation?.cancel(); mutable.update { it.copy(loading = false) } }
}
