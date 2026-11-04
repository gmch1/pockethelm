package com.gmch.codexremote

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** Bound for unconfirmed interaction answers kept per environment (task 2026-11-02). */
private const val INTERACT_RECORDS_MAX = 128

data class ZcodeState(
    val recoveryPhase: RecoveryPhase = RecoveryPhase.Cached,
    val connected: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val providers: List<ZProvider> = emptyList(), val selection: ZSelection? = null, val selectionGuard: String = "",
    val projects: List<ZProject> = emptyList(), val sessions: List<ZSession> = emptyList(),
    val liveStatuses: List<ZLiveSessionStatus> = emptyList(),
    val sessionsFresh: Boolean = false, val historyCached: Boolean = false,
    val selected: ZSession? = null, val items: List<TimelineItem> = emptyList(),
    val historyLoading: Boolean = false, val historyError: String? = null, val sending: Boolean = false,
    val outgoing: List<OutgoingMessage> = emptyList(),
    val queue: List<QueuedMessage> = emptyList(), val phase: String = "", val executionId: String = "",
    val interactionCount: Int = 0, val draft: String = "", val delivery: String = "queue",
    val quota: String = "点击读取额度", val quotaSummary: String = "额度 ▾", val pending: Boolean = false, val pendingUnknown: Boolean = false,
    val commandInFlight: Boolean = false,
    val notice: String = "", val beforeRow: Double? = null, val logEpoch: String = "", val revision: Long = 0,
    val permission: String = "", val currentSelection: ZSelection? = null,
    val browser: ZcodeBrowserState = ZcodeBrowserState(),
    // Image attachments: entry hidden unless the bridge advertises the capability; refs
    // and bytes are connection/session-scoped memory state, never persisted.
    val attachmentsEnabled: Boolean = false,
    val attachments: List<ChatAttachment> = emptyList(),
    val attachmentsBusy: Boolean = false,
    // Interaction answering (task 2026-11-02): details only flow when the bridge advertises
    // the capability; otherwise the legacy count-only placeholder stays.
    val interactionsEnabled: Boolean = false,
    val interactions: List<ZInteraction> = emptyList(),
    val interactionsComplete: Boolean = false,
    val interactSubmitting: Set<String> = emptySet(),
    val interactReleasable: Set<String> = emptySet(),
    val interactionPresence: Map<String, Boolean> = emptyMap(),
    val interactionNotice: String = "",
    val snoozeUnconfirmed: Set<String> = emptySet(),
    // P1 (2026-11-02 zcode-p1-parity): authoritative turn badge, file-change detail and
    // queue operations only when the bridge advertises them; list metadata from the
    // sessions-index stream; diffText carries a loaded detail sheet body.
    val turn: ZTurn? = null,
    val queueOpsEnabled: Boolean = false,
    val diffEnabled: Boolean = false,
    val indexMetaEnabled: Boolean = false,
    val listMeta: Map<String, ZListMeta> = emptyMap(),
    val diffText: String? = null
)

class ZcodeViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = Vault(app)
    private val mutable = MutableStateFlow(ZcodeState())
    val state = mutable.asStateFlow()
    var active = false
        private set
    private var visible = false
    private val actions = Mutex()
    private var transport: SshTransport? = null
    private var retiringTransport: SshTransport? = null
    private var rpc: RpcClient? = null
    private var job: Job? = null
    private var poll: Job? = null
    private var supportsSessionIndex = false
    private var generation = 0
    private var browserJob: Job? = null
    private var readEpoch = 0
    private var addEpoch = 0
    private var profile: ConnectionProfile? = null
    private var pending: JSONObject? = null
    private var browserEpoch = 0
    private var older = emptyList<TimelineItem>()
    // Locally-sent messages awaiting reconciliation, keyed by environment identity + session
    // so a reconnect to another dev machine or a conversation switch never leaks records.
    private val outgoingBySession = LinkedHashMap<OutgoingKey, MutableList<OutgoingMessage>>()
    private val cache = ResumeCache(app, "zcode")
    private val restoreJob: Job
    private var backgroundJob: Job? = null
    private var listSyncedAt = 0L
    private var historySyncedAt = 0L
    internal val recovery = SessionRecovery(viewModelScope)
    private var manuallyDisconnected = false
    private val pausedAux = mutableSetOf<String>()
    private data class EntrySelection(val session: ZSession, val navigation: Int, val choiceVersion: Int)
    private var entrySelection: EntrySelection? = null
    private var choiceVersion = 0
    private var navigationVersion = 0
    // Server activity watermarks acknowledged only by successful reads, persisted to the
    // vault only on navigation/connection boundaries — never on the 2.5s poll.
    private val seenActivity = LinkedHashMap<String, Long>()
    private fun seenKey() = key("seen-server-v1")
    private fun persistSeen() {
        val bounded = seenActivity.entries.toList().takeLast(500)
        seenActivity.clear(); bounded.forEach { seenActivity[it.key] = it.value }
        runCatching {
            vault.put(seenKey(), org.json.JSONObject().apply { bounded.forEach { (id, at) -> put(id, at) } }.toString())
        }
    }
    private fun restoreSeen() {
        seenActivity.clear()
        val raw = runCatching { vault.get(seenKey()) }.getOrNull() ?: return
        val stored = runCatching { JSONObject(raw) }.getOrNull() ?: return
        stored.keys().forEach { id -> seenActivity[id] = stored.optLong(id) }
    }

    init {
        val raw = runCatching { vault.get("profile") }.getOrNull()
        profile = raw?.let { runCatching { ConnectionProfile.from(JSONObject(it)) }.getOrNull() }
        val identity = profile?.resumeIdentity
        restoreJob = viewModelScope.launch {
            if (identity == null) return@launch
            pending = vault.get(key("pending"))?.let(::JSONObject)
            mutable.update { it.copy(pending = pending != null) }
            val restored = cache.read(identity) ?: return@launch
            if (profile?.resumeIdentity != identity || navigationVersion != 0) return@launch
            mutable.update { it.copy(projects = restored.zProjects, sessions = restored.zSessions,
                selection = restored.choice, selectionGuard = restored.guard) }
            restored.zSelected?.let { session ->
                select(session)
                mutable.update { it.copy(items = restored.items, historyCached = true, historyLoading = false) }
            }
        }
        viewModelScope.launch {
            restoreJob.join()
            state.map { s -> profile?.let { ResumeSnapshot.zcode(s, it) } }.distinctUntilChanged().conflate().collect { snapshot ->
                if (snapshot != null) try { cache.write(snapshot) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                delay(500) // Bound disk writes while still checkpointing a continuous response stream.
            }
        }
    }
    private fun outgoingKey(sessionId: String) = OutgoingKey(profile?.identity.orEmpty(), sessionId)

    private fun publishOutgoing(key: OutgoingKey) {
        val selectedId = mutable.value.selected?.id ?: return
        // Publish only into the environment + session the records belong to.
        if (outgoingKey(selectedId) == key)
            mutable.update { it.copy(outgoing = (outgoingBySession[key] ?: emptyList()).toList()) }
    }

    private fun addOutgoing(key: OutgoingKey, message: OutgoingMessage) {
        val list = outgoingBySession.getOrPut(key) { mutableListOf() }
        list.removeAll { it.clientId == message.clientId }
        list.add(message)
        // Never silently drop unconfirmed or awaiting-reconciliation records; only settled
        // failures are trimmed once the bound is exceeded.
        while (list.size > 20) {
            val oldestFailed = list.indexOfFirst { it.state == OutgoingState.FAILED }
            if (oldestFailed < 0) break
            list.removeAt(oldestFailed)
        }
        publishOutgoing(key)
    }

    private fun updateOutgoing(key: OutgoingKey, clientId: String, transform: (OutgoingMessage) -> OutgoingMessage) {
        val list = outgoingBySession[key] ?: return
        val index = list.indexOfFirst { it.clientId == clientId }
        if (index >= 0) { list[index] = transform(list[index]); publishOutgoing(key) }
    }

    fun dismissFailed(message: OutgoingMessage) {
        if (mutable.value.selected?.id != message.sessionId) return
        val key = outgoingKey(message.sessionId)
        val current = outgoingBySession[key]?.find { it.clientId == message.clientId } ?: return
        if (current.state == OutgoingState.FAILED) removeOutgoing(key, current.clientId)
    }

    private fun removeOutgoing(key: OutgoingKey, clientId: String?) {
        if (clientId.isNullOrBlank()) return
        val list = outgoingBySession[key] ?: return
        if (list.removeAll { it.clientId == clientId }) publishOutgoing(key)
    }

    private fun markOutgoingUncertain(key: OutgoingKey, clientId: String? = null) {
        val list = outgoingBySession[key] ?: return
        var changed = false
        list.replaceAll { m ->
            if ((clientId == null || m.clientId == clientId) && m.state == OutgoingState.SENDING) {
                changed = true; m.copy(state = OutgoingState.UNCERTAIN)
            } else m
        }
        if (changed) publishOutgoing(key)
    }

    /** Drops bubbles whose real userMessage (or queue entry) has arrived under the same id. */
    private fun reconcileOutgoing(sessionId: String) {
        if (mutable.value.selected?.id != sessionId) return
        val key = outgoingKey(sessionId)
        val list = outgoingBySession[key] ?: return
        if (list.isEmpty()) return
        val (itemIds, queueIds) = reconcileIdsForRead(mutable.value.items, mutable.value.queue)
        val remaining = reconcileOutgoingList(list, itemIds, queueIds)
        if (remaining.size != list.size) { outgoingBySession[key] = remaining.toMutableList(); publishOutgoing(key) }
    }
    private val clientId: String by lazy {
        vault.get("zcode:clientId") ?: ("pockethelm-" + UUID.randomUUID()).also { vault.put("zcode:clientId", it) }
    }
    private fun key(type: String, id: String = "") = "zcode:$type:${if (type == "pending-interact") profile?.resumeIdentity else profile?.identity}:$id"
    private fun client() = rpc ?: error("请先连接 ZCode")
    private suspend fun call(method: String, params: JSONObject = obj()) = client().call(method, params, timeout = 90_000)
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        val epoch = generation
        actions.withLock {
            if (epoch != generation) return@withLock
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) mutable.update { it.copy(error = e.message ?: "请求失败") } }
            finally { if (epoch == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    fun enter() { active = true; foreground() }
    // A tab change is not an Activity background transition. Keep the channel, pause polling.
    fun leave() { active = false; poll?.cancel(); poll = null; pauseRecovery(all = true); pauseStatuses() }
    fun keepConnection() { visible = true; backgroundJob?.cancel(); backgroundJob = null }
    fun foreground() {
        visible = true
        backgroundJob?.cancel(); backgroundJob = null
        if (!active) return
        val next = runCatching { vault.get("profile")?.let { ConnectionProfile.from(JSONObject(it)) } }.getOrNull()
        if (next?.resumeIdentity != profile?.resumeIdentity) {
            disconnect()
            navigationVersion++
            profile = next
            entrySelection = null
            listSyncedAt = 0; historySyncedAt = 0
            older = emptyList(); outgoingBySession.clear(); pending = null
            // Environment switch: records in memory belong to the old environment (their
            // vault blob is per-environment and will not reload); drop them wholesale.
            resetInteractionEnvironment()
            snoozeStates.clear()
            seenActivity.clear()
            mutable.value = ZcodeState()
        }
        if (!mutable.value.connected) { if (!manuallyDisconnected) connect(); return }
        schedulePoll()
        resumePausedAux()
        if (mutable.value.selected != null) {
            if (mutable.value.recoveryPhase != RecoveryPhase.Ready || entrySelection != null ||
                System.currentTimeMillis() - historySyncedAt > 5_000) recoverCurrent()
        } else if (mutable.value.recoveryPhase != RecoveryPhase.Ready ||
            System.currentTimeMillis() - listSyncedAt > 60_000) recoverCurrent()
        recoverAux("statuses") { loadStatuses() }
    }

    private fun recoveryTarget() = RecoveryTarget(profile?.resumeIdentity.orEmpty(), "zcode", generation,
        mutable.value.selected?.let { "${it.path}|${it.identity}|${it.id}" } ?: "list", navigationVersion)
    private fun currentRecovery(target: RecoveryTarget) = mutable.value.connected && recoveryTarget() == target

    private fun recoverCurrent(quiet: Boolean = false): Job? {
        if (!mutable.value.connected) return null
        val target = recoveryTarget()
        val session = mutable.value.selected
        val silent = quiet && mutable.value.recoveryPhase == RecoveryPhase.Ready
        return recovery.launch(if (session == null) "list" else "detail", target, { currentRecovery(target) },
            started = { if (!silent) mutable.update { it.copy(recoveryPhase = RecoveryPhase.Restoring,
                historyLoading = session != null, historyError = null, error = null) } },
            failed = { e -> mutable.update { it.copy(recoveryPhase = RecoveryPhase.Failed, historyLoading = false,
                historyError = if (session != null) e.message else it.historyError, error = e.message) } },
            finished = { mutable.update { it.copy(historyLoading = false,
                recoveryPhase = if (it.recoveryPhase == RecoveryPhase.Failed) it.recoveryPhase else RecoveryPhase.Ready) } }) {
            if (session == null) loadProjects() else {
                readGuarded(session)
            }
        }
    }
    /** These are runtime/global receipt reads, not detail reads. Their result must
     * survive navigation, but never an environment switch or connection replacement. */
    private fun recoverAux(name: String, block: suspend () -> Unit) {
        val target = RecoveryTarget(profile?.resumeIdentity.orEmpty(), "zcode", generation, "runtime:$name")
        recovery.launch("aux:$name", target,
            { mutable.value.connected && generation == target.generation && profile?.resumeIdentity.orEmpty() == target.environment },
            failed = { e -> mutable.update { if (name == "interactions") it.copy(interactionNotice = "交互状态未核实，请刷新后核对")
                else it.copy(notice = "辅助数据更新失败：${e.message}") } }, block = block)
    }
    private fun resumePausedAux() {
        val interrupted = pausedAux.toSet(); pausedAux.clear()
        for (lane in interrupted) when (lane) {
            "aux:catalog" -> recoverAux("catalog") { loadCatalog() }
            "aux:projects" -> recoverAux("projects") { loadProjects() }
            "aux:pending" -> recoverAux("pending") { recoverPending() }
            "aux:interactions" -> recoverAux("interactions") { recoverInteractions() }
            "aux:statuses" -> recoverAux("statuses") { loadStatuses() }
            "aux:quota" -> if (entrySelection == null) quota() else pausedAux.add(lane)
        }
    }
    private fun pauseRecovery(all: Boolean = false) {
        if (all) {
            pausedAux += recovery.activeLanes().filter { it.startsWith("aux:") }
            recovery.cancelAll()
        } else { recovery.cancel("detail"); recovery.cancel("list") }
        mutable.update { it.copy(historyLoading = false,
            recoveryPhase = if (all && it.recoveryPhase == RecoveryPhase.Ready) RecoveryPhase.Ready else RecoveryPhase.Cached) }
    }
    fun background() {
        if (!visible) return
        pauseRecovery(all = true)
        visible = false
        poll?.cancel(); poll = null
        pauseStatuses()
        profile?.let { p ->
            val snapshot = ResumeSnapshot.zcode(mutable.value, p)
            viewModelScope.launch { runCatching { cache.write(snapshot) } }
        }
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch { delay(BACKGROUND_GRACE_MS); disconnect() }
    }
    fun disconnectManually() { manuallyDisconnected = true; disconnect() }
    private fun disconnect() {
        generation++
        quotaFetchedFor = null
        attachmentSerial.incrementAndGet()
        supportsSessionIndex = false
        backgroundJob?.cancel(); backgroundJob = null
        browser(ZcodeBrowserEvent.Disconnect)
        job?.cancel(); job = null; poll?.cancel(); poll = null
        pauseRecovery(all = true)
        pausedAux.clear() // connect() schedules the global reads on the new generation.
        rpc?.close(); rpc = null
        val old = transport; transport = null
        if (old != null && retiringTransport == null) retiringTransport = old
        viewModelScope.launch(Dispatchers.IO) { old?.close() }
        // In-flight requests lose their channel: outcome unknown, never a failure.
        outgoingBySession.keys.toList().forEach { markOutgoingUncertain(it) }
        // Snooze attempts are generation-scoped: an old connection's in-flight state and
        // backoff budget die with it. Answer records survive in the vault for recovery.
        snoozeStates.clear()
        interactionFlights.clear()
        persistSeen()
        // Refs are connection-scoped upstream (the bridge registry dies with the process);
        // attachment drafts are memory-only and dropped with an explicit re-pick prompt.
        attachmentRefs.clear(); attachmentUploading.clear()
        mutable.update {
            it.copy(connected = false, busy = false, sending = false, historyLoading = false, recoveryPhase = RecoveryPhase.Cached, sessionsFresh = false, liveStatuses = emptyList(),
                phase = "", executionId = "", interactionCount = 0, currentSelection = null, permission = "",
                // A session opened but never loaded must not fall back to a blank timeline.
                historyError = if (it.selected != null && it.items.isEmpty() && it.historyLoading)
                    "连接已断开，未能加载会话内容，重连后重试" else it.historyError,
                commandInFlight = false,
                interactionsEnabled = false, interactions = emptyList(), interactionsComplete = false,
                interactSubmitting = emptySet(), interactReleasable = emptySet(), interactionPresence = emptyMap(), interactionNotice = "", snoozeUnconfirmed = emptySet(),
                turn = null, queueOpsEnabled = false, diffEnabled = false, indexMetaEnabled = false,
                listMeta = emptyMap(), diffText = null,
                attachmentsEnabled = false, attachments = emptyList(), attachmentsBusy = false)
        }
    }
    fun connect() {
        manuallyDisconnected = false
        if (job?.isActive == true) return
        if (mutable.value.connected) { refresh(); return }
        job = viewModelScope.launch {
            val attempt = ++generation
            mutable.update { it.copy(busy = true, error = null) }
            try {
            restoreJob.join()
            val raw = vault.get("profile") ?: error("请先在连接页扫码配对开发机")
            val next = ConnectionProfile.from(JSONObject(raw))
            require(next.useDeviceKey) { "ZCode 需要扫码配对的受限设备连接" }
            if (profile?.resumeIdentity != next.resumeIdentity) {
                mutable.value = ZcodeState(busy = true)
                entrySelection = null
                older = emptyList()
                // A different environment owns no local records from the previous one.
                outgoingBySession.clear()
                resetInteractionEnvironment()
                seenActivity.clear()
            }
            profile = next
            pending = vault.get(key("pending"))?.let(::JSONObject)
            mutable.update { it.copy(pending = pending != null) }
            val epoch = attempt
            val ssh = SshTransport(); transport = ssh
            try {
                retiringTransport?.let { check(it.awaitClosed()) { "旧连接尚未释放，请稍后重连" }; retiringTransport = null }
                val connectionDeadline = System.nanoTime() + 45_000_000_000L
                withContext(RecoveryDeadline(connectionDeadline)) { ssh.connect(next, vault, "", "", zcode = true) }
                currentCoroutineContext().ensureActive()
                val connection = RpcClient(ssh.input, ssh.output, {}, { error ->
                    viewModelScope.launch {
                        if (generation == epoch) {
                            disconnect()
                            mutable.update { it.copy(error = "ZCode 连接中断，刷新重连。任务保留在开发机。") }
                        }
                    }
                })
                rpc = connection
                val hello = withContext(RecoveryDeadline(connectionDeadline)) { call("initialize", obj("clientId" to clientId)) }
                check(hello.string("engine") == "zcode" && hello.optInt("bridgeVersion") == 1) { "请在开发机安装 ZCode 桥接器" }
                supportsSessionIndex = hello.optBoolean("sessionsIndex")
                // Optional capability: an older bridge without it keeps the count-only placeholder.
                val capabilities = hello.optJSONObject("capabilities")
                interactSupport = capabilities?.optBoolean("interactions") == true
                attachmentsEnabled = capabilities?.optBoolean("attachments") == true
                val queueOps = capabilities?.optBoolean("queueOps") == true
                val turnSummary = capabilities?.optBoolean("turnSummary") == true
                val fileChanges = capabilities?.optBoolean("fileChanges") == true
                val indexMeta = capabilities?.optBoolean("indexMeta") == true
                restoreSeen()
                mutable.update { it.copy(connected = true, interactionsEnabled = interactSupport,
                    attachmentsEnabled = attachmentsEnabled,
                    queueOpsEnabled = queueOps, diffEnabled = turnSummary && fileChanges, indexMetaEnabled = indexMeta) }
                recoverCurrent()
                recoverAux("pending") { recoverPending() }
                recoverAux("interactions") { recoverInteractions() }
                recoverAux("catalog") { loadCatalog() }
                recoverAux("projects") { loadProjects() }
                recoverAux("statuses") { loadStatuses() }
                schedulePoll()
            } finally {
                if (!mutable.value.connected || epoch != generation) {
                    ssh.close()
                    if (epoch == generation && retiringTransport == null) retiringTransport = ssh
                }
            }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == generation) mutable.update { it.copy(connected = false,
                    error = connectionFailureMessage(e, "跳板机")) }
            } finally { if (attempt == generation) mutable.update { it.copy(busy = false) } }
        }
    }
    fun refresh() {
        if (!mutable.value.connected) { connect(); return }
        recoverCurrent()
        recoverAux("catalog") { loadCatalog() }
        recoverAux("projects") { loadProjects() }
        recoverAux("statuses") { loadStatuses() }
        recoverAux("pending") { recoverPending() }
        recoverAux("interactions") { recoverInteractions() }
        schedulePoll()
    }
    private suspend fun loadCatalog() {
        val catalog = call("catalog")
        val providers = zcodeProviders(catalog)
        val old = mutable.value.selection
        val chosen = old ?: ZSelection.parse(catalog.optJSONObject("preferred"))
        val quotaValid = chosen == old && providers.find { it.id == chosen?.provider }?.guard == mutable.value.selectionGuard
        // Keep a stale explicit selection visible; never silently switch its billing source.
        mutable.update { it.copy(providers = providers, selection = chosen,
            quota = if (quotaValid) it.quota else "点击读取额度", quotaSummary = if (quotaValid) it.quotaSummary else "额度 ▾",
            selectionGuard = it.selectionGuard.ifBlank { providers.find { p -> p.id == chosen?.provider }?.guard.orEmpty() }) }
        if (mutable.value.selected != null && entrySelection == null) autoRefreshQuota()
    }
    private suspend fun loadProjects() {
        val startGeneration = generation
        val data = call("projects")
        if (generation != startGeneration) return
        listSyncedAt = System.currentTimeMillis()
        mutable.update { it.copy(sessionsFresh = true, projects = data.optJSONArray("projects").objects().map { p ->
            ZProject(p.getString("id"), p.getString("name"), p.optJSONArray("roots").strings())
        }, sessions = data.optJSONArray("sessions").objects().map(ZSession::parse)
            // Most recently active first; stable sort keeps peers without timestamps in place.
            .sortedByDescending { it.updatedAt }) }
        publishListMeta()
        // readTree is a complete list. Removed sessions must not survive a cold-start snapshot.
        val selected = mutable.value.selected
        if (selected != null && mutable.value.historyCached && mutable.value.sessions.none { it.id == selected.id }) {
            navigationVersion++
            older = emptyList()
            mutable.update { it.copy(selected = null, items = emptyList(), queue = emptyList(), outgoing = emptyList(),
                draft = "", phase = "", executionId = "", historyLoading = false, historyCached = false,
                historyError = null, notice = "该会话已移除", turn = null, diffText = null) }
        }
    }
    // Directory picker: all transitions go through the ZcodeBrowser reducer; this wiring owns
    // RPCs. Epochs discard results from closed dialogs or superseded requests, and requested
    // is kept verbatim so a retry repeats the exact failing request (null means server home).
    private fun browser(event: ZcodeBrowserEvent) {
        when (event) {
            is ZcodeBrowserEvent.Open -> { readEpoch++; addEpoch++ }
            is ZcodeBrowserEvent.Close, ZcodeBrowserEvent.Disconnect -> {
                readEpoch++; addEpoch++; browserJob?.cancel(); browserJob = null
                mutable.update { it.copy(browser = ZcodeBrowserState()) }
                return
            }
            else -> {}
        }
        val (next, effect) = ZcodeBrowser.reduce(mutable.value.browser, event)
        mutable.update { it.copy(browser = next) }
        when (effect) {
            is ZcodeBrowserEffect.Read -> {
                val requested = effect.requested
                val epoch = ++readEpoch
                browserJob?.cancel()
                browserJob = viewModelScope.launch {
                    try {
                        val request = obj()
                        requested?.let { request.put("path", it) }
                        if (next.showHidden) request.put("includeHidden", true)
                        val data = call("directory/read", request)
                        if (epoch != readEpoch) return@launch
                        browser(ZcodeBrowserEvent.ReadOk(requested, ZDirectoryListing.parse(data)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (epoch == readEpoch) browser(ZcodeBrowserEvent.ReadFail(requested, e.message ?: "读取目录失败"))
                    }
                }
            }
            ZcodeBrowserEffect.Add -> {
                val epoch = ++addEpoch
                viewModelScope.launch {
                    try {
                        call("project/add", obj("workspacePath" to next.current))
                        if (epoch != addEpoch) return@launch
                        loadProjects()
                        if (epoch != addEpoch) return@launch
                        browser(ZcodeBrowserEvent.AddOk)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (epoch == addEpoch) browser(ZcodeBrowserEvent.AddFail(e.message ?: "添加项目失败"))
                    }
                }
            }
            ZcodeBrowserEffect.None -> {}
        }
    }
    fun openBrowser() = browser(ZcodeBrowserEvent.Open(profile?.root?.takeIf { it.isNotBlank() }))
    fun closeBrowser() = browser(ZcodeBrowserEvent.Close)
    fun browseBrowser(path: String?) = browser(ZcodeBrowserEvent.Browse(path))
    fun retryBrowser() = browseBrowser(mutable.value.browser.requested)
    fun browserFilter(value: String) = browser(ZcodeBrowserEvent.Filter(value))
    fun browserManual(value: String) = browser(ZcodeBrowserEvent.Manual(value))
    fun browserShowHidden(value: Boolean) = browser(ZcodeBrowserEvent.Hidden(value))
    fun confirmBrowserAdd() {
        if (mutable.value.connected) browser(ZcodeBrowserEvent.AddStart)
    }
    fun choose(selection: ZSelection) {
        if (mutable.value.busy || mutable.value.pending) return
        choiceVersion++
        entrySelection = null // An explicit user choice supersedes entry synchronization.
        mutable.update { it.copy(selection = selection, selectionGuard = it.providers.find { p -> p.id == selection.provider }?.guard.orEmpty(), quota = "点击读取额度", quotaSummary = "额度 ▾", notice = "下次发送使用此计划和模型") }
    }
    private fun selectionParams(): JSONObject {
        val choice = mutable.value.selection ?: error("请选择计划与模型")
        val provider = mutable.value.providers.find { it.id == choice.provider } ?: error("计划已不可用，请刷新后选择")
        check(provider.models.any { it.id == choice.model && (if (it.levels.isEmpty()) choice.level.isEmpty() else choice.level in it.levels) }) { "请选择有效模型与推理强度" }
        check(mutable.value.selectionGuard == provider.guard) { "额度来源已变化，请重新选择计划后发送" }
        return obj("selection" to choice.json(), "guard" to mutable.value.selectionGuard)
    }
    fun quota(): Job {
        val selected = mutable.value.selection
        val guard = mutable.value.selectionGuard
        val startGeneration = generation
        fun stillSelected() = generation == startGeneration && mutable.value.selection == selected && mutable.value.selectionGuard == guard
        val target = RecoveryTarget(profile?.resumeIdentity.orEmpty(), "zcode", startGeneration,
            "quota:$selected:$guard")
        return recovery.launch("aux:quota", target, ::stillSelected,
            started = { mutable.update { it.copy(quota = "正在读取…", quotaSummary = "额度 ▾") } },
            failed = { mutable.update { it.copy(quota = "额度暂不可用，请重试", quotaSummary = "额度 ▾") } }) {
        try {
            val data = call("quota", selectionParams())
            if (stillSelected()) {
                mutable.update { it.copy(quota = zcodeQuota(data), quotaSummary = zcodeQuotaSummary(data, selected?.model.orEmpty())) }
                // Success stamps the TTL watermark — including manual refreshes, so the
                // auto path judges freshness from the latest successful read (R8).
                selected?.let { markQuotaCache(it) }
            }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (stillSelected()) mutable.update { it.copy(quota = "额度暂不可用，请重试", quotaSummary = "额度 ▾") } }
        }
    }

    /** Automatic quota refresh on session entry: deduped per selection — same plan+model
     *  keeps the cached display; a different plan or reconnect re-fetches. Silently
     *  skipped while no valid selection exists (manual tap stays as the fallback). */
    private var quotaFetchedFor: ZSelection? = null
    private var quotaFetchedAt: Long = 0
    /** Successful-quota display TTL (task 2026-11-03-start-plan-daily-recovery R1/R8):
     *  a cached summary older than this re-fetches on the next session entry, so plan
     *  consumption and period resets surface without a manual tap. A failed fetch
     *  keeps the placeholder, which bypasses the cache and retries anyway. The stamp
     *  is taken on SUCCESS (including a manual refresh) and uses elapsedRealtime so a
     *  wall-clock rollback cannot extend the cache. */
    internal var quotaTtlMs: Long = 10 * 60_000L

    private fun markQuotaCache(choice: ZSelection) {
        quotaFetchedFor = choice
        quotaFetchedAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun autoRefreshQuota() {
        val choice = mutable.value.selection ?: return
        val provider = mutable.value.providers.find { it.id == choice.provider } ?: return
        if (provider.guard != mutable.value.selectionGuard || provider.models.none { it.id == choice.model }) return
        val cached = choice == quotaFetchedFor && mutable.value.quotaSummary != "额度 ▾"
        if (cached && android.os.SystemClock.elapsedRealtime() - quotaFetchedAt < quotaTtlMs) return
        quota()
    }
    fun create(project: ZProject) = action {
        check(project.roots.size == 1) { "当前仅支持单目录项目" }
        val params = selectionParams().put("workspacePath", project.roots.single()).put("projectId", project.id)
        val result = write("session/create", params)
        val id = result?.optJSONObject("result")?.string("sessionId").orEmpty()
        if (id.isNotBlank()) {
            select(ZSession(id, "新会话", project.roots.single(), project = project.id))
            readGuarded(mutable.value.selected!!)
            loadProjects()
        }
    }
    fun open(session: ZSession) {
        if (!mutable.value.connected && sameTarget(mutable.value.selected, session)) return
        select(session)
        entrySelection = EntrySelection(session, navigationVersion, choiceVersion)
        if (mutable.value.connected) recoverCurrent()
        else mutable.update { it.copy(historyLoading = false, historyError = "连接后加载会话内容") }
    }
    fun retryLoad() { recoverCurrent() }
    // Only the first history of an empty session reports inline loading; refreshing an
    // already-loaded session keeps its messages visible instead of flashing a spinner.
    private suspend fun readGuarded(session: ZSession, useSessionSelection: Boolean = false) {
        val scope = RequestScope(generation, navigationVersion, session.id)
        try {
            read(session, useSessionSelection)
            if (scope.appliesTo(generation, navigationVersion, mutable.value.selected?.id))
                mutable.update { it.copy(recoveryPhase = RecoveryPhase.Ready) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // A result from before a reconnect must not clear the new connection's loading state.
            if (scope.appliesTo(generation, navigationVersion, mutable.value.selected?.id) && mutable.value.items.isEmpty())
                mutable.update { it.copy(recoveryPhase = RecoveryPhase.Failed, historyLoading = false, historyError = e.message ?: "加载会话内容失败") }
            throw e
        }
    }
    private fun select(session: ZSession) {
        pauseRecovery()
        navigationVersion++
        entrySelection = null
        attachmentSelection++; attachmentSerial.incrementAndGet()
        mutable.value.selected?.let { vault.put(key("draft", it.id), mutable.value.draft) }
        persistSeen()
        older = emptyList()
        // Refs are bound to the uploading session upstream; a switch invalidates them all.
        attachmentRefs.clear()
        val outKey = outgoingKey(session.id)
        mutable.update { it.copy(selected = session, historyCached = false, items = emptyList(), queue = emptyList(), phase = "", executionId = "",
            historyLoading = true, historyError = null, sending = false,
            outgoing = (outgoingBySession[outKey] ?: emptyList()).toList(),
            draft = vault.get(key("draft", session.id)).orEmpty(), beforeRow = null, notice = "", currentSelection = null, permission = "",
            interactions = emptyList(), interactionsComplete = false, interactionPresence = emptyMap(),
            interactSubmitting = emptySet(), interactReleasable = emptySet(), interactionNotice = "",
            logEpoch = "", revision = 0, turn = null, diffText = null,
            attachments = emptyList(), attachmentsBusy = false) }
        // A persisted pending send restores its 待确认 bubble for exactly this session —
        // but never over an existing record: an in-flight SENDING, or a settled
        // ACCEPTED/FAILED receipt, must not be downgraded by replaying the old receipt.
        pending?.takeIf { it.string("method") == "session/send" }?.optJSONObject("params")?.let { p ->
            val id = p.string("commandId")
            // Restore only when no record with this id exists (fresh map after restart);
            // an in-flight SENDING or a settled receipt must never be downgraded.
            if (id.isNotBlank() && p.string("sessionId") == session.id &&
                outgoingBySession[outKey]?.any { it.clientId == id } != true)
                addOutgoing(outKey, OutgoingMessage(session.id, id, p.string("text"), OutgoingState.UNCERTAIN))
        }
    }
    fun back() {
        entrySelection = null
        pauseRecovery()
        attachmentSelection++; attachmentSerial.incrementAndGet()
        navigationVersion++
        if (mutable.value.selected == null) return
        mutable.value.selected?.let { vault.put(key("draft", it.id), mutable.value.draft) }
        persistSeen()
        mutable.update { it.copy(selected = null, items = emptyList(), queue = emptyList(), draft = "", historyLoading = false,
            historyError = null, outgoing = emptyList(), sessionsFresh = it.connected && it.sessionsFresh,
            interactions = emptyList(), interactionsComplete = false, interactSubmitting = emptySet(),
            interactionNotice = "", snoozeUnconfirmed = emptySet(), turn = null, diffText = null,
            attachments = emptyList(), attachmentsBusy = false) }
        older = emptyList()
        // Refs die with the session scope upstream; unsent attachment drafts are dropped.
        attachmentRefs.clear()
        if (mutable.value.connected) {
            recoverCurrent()
            recoverAux("statuses") { loadStatuses() }
        }
    }

    fun draft(value: String) {
        mutable.update { it.copy(draft = value) }
        mutable.value.selected?.let { vault.put(key("draft", it.id), value) }
    }
    fun delivery(value: String) { mutable.update { it.copy(delivery = value) } }
    private data class SnapshotVersion(val epoch: String, val revision: Long, val retired: Set<String> = emptySet())
    private val snapshotVersions = HashMap<String, SnapshotVersion>()
    private val snapshotRequests = HashMap<String, Long>()
    private val snapshotApplied = HashMap<String, Long>()
    private var snapshotSequence = 0L

    private suspend fun read(session: ZSession, useSessionSelection: Boolean = false, checkInteractionIds: List<String> = emptyList()) {
        val scope = interactionScope()
        val navigation = navigationVersion
        val choiceAtStart = choiceVersion
        val target = scope.owner + interactKey(session, "")
        val sequence = ++snapshotSequence
        snapshotRequests[target] = sequence
        val connection = client()
        // Capture before requesting content: a later index event must not mark unseen
        // content as read. Missing index data conservatively leaves the watermark alone.
        val activityBeforeRead = mutable.value.liveStatuses.firstOrNull {
            it.id == session.id && it.path == session.path && it.identity == session.identity
        }?.lastActivityAt?.takeIf { it > 0 }
        val eligibleReceipts = interactRecords.values.filter { belongs(it, session) &&
            it.commandId !in interactionFlights && it.phase != "submitting" }.map { it.commandId }.toSet()
        val request = session.target()
        val checks = (checkInteractionIds + interactRecords.values.filter { belongs(it, session) }
            .map { it.interactionId } + snoozeStates.values.filter { sameTarget(it.session, session) }
            .map { it.interactionId }).distinct().take(16)
        if (checks.isNotEmpty() && interactSupport)
            request.put("checkInteractionIds", org.json.JSONArray(checks))
        var data = connection.call("session/read", request, timeout = 90_000)
        fun isCurrent() = current(scope) && navigation == navigationVersion && (snapshotApplied[target] ?: 0L) <= sequence
        if (!isCurrent()) return
        val visible = mutable.value
        val previous = snapshotVersions[target] ?: if (sameTarget(visible.selected, session) && visible.logEpoch.isNotBlank())
            SnapshotVersion(visible.logEpoch, visible.revision) else null
        var epoch = data.string("logEpoch")
        var revision = data.optLong("revision")
        if (previous != null) {
            if (epoch == previous.epoch && revision < previous.revision) return
            if (epoch != previous.epoch) {
                if (epoch in previous.retired) return
                // Epochs have no ordering. Establish a changed epoch with a second fresh read
                // before it may revoke any answer protection or replace the visible history.
                val confirmation = connection.call("session/read", request, timeout = 90_000)
                if (!isCurrent() || confirmation.string("logEpoch") != epoch ||
                    confirmation.optLong("revision") < revision) return
                data = confirmation
                revision = data.optLong("revision")
            }
        }
        snapshotApplied[target] = sequence
        snapshotVersions[target] = SnapshotVersion(epoch, revision,
            previous?.retired.orEmpty() + if (previous != null && epoch != previous.epoch) setOf(previous.epoch) else emptySet())
        if (interactSupport) {
            reconcileInteractions(session, data, scope, eligibleReceipts)
            if (sameTarget(mutable.value.selected, session)) publishInteractState()
        }
        if (!isCurrent() || !sameTarget(mutable.value.selected, session)) return
        historySyncedAt = System.currentTimeMillis()
        val items = zcodeItems(data)
        if (epoch != mutable.value.logEpoch || revision != mutable.value.revision) older = emptyList()
        val actual = ZSelection.parse(data.optJSONObject("selection"))
        val entering = entrySelection?.takeIf { it.navigation == navigation &&
            it.choiceVersion == choiceVersion && sameTarget(it.session, session) }
        val syncChoice = entering != null || (useSessionSelection && choiceAtStart == choiceVersion)
        val queue = queueEntries(data)
        val first = if (data.isNull("firstRowId")) null else data.optDouble("firstRowId")
        val interactions = if (interactSupport) zcodeInteractions(data) else emptyList()
        activityBeforeRead?.let { seenActivity[session.id] = maxOf(seenActivity[session.id] ?: 0, it) }
        mutable.update { it.copy(selected = session.copy(title = data.string("title").ifBlank { session.title }),
            items = (older + items).distinctBy { row -> row.id }, phase = data.string("phase"), queue = queue,
            executionId = if (data.optBoolean("canStop")) data.string("executionId") else "",
            interactionCount = data.optInt("interactionCount"), currentSelection = actual,
            interactions = interactions, interactionsComplete = data.optBoolean("interactionsComplete"),
            interactionPresence = interactionPresence(data),
            selection = if (syncChoice) actual ?: it.selection else it.selection,
            selectionGuard = if (syncChoice && actual != null) it.providers.find { p -> p.id == actual.provider }?.guard.orEmpty() else it.selectionGuard,
            permission = if (data.string("mode") == "yolo") "完全访问" else data.string("mode"),
            historyLoading = false, historyError = null, historyCached = false,
            beforeRow = if (older.isNotEmpty()) it.beforeRow else first?.takeIf { _ -> data.optBoolean("hasMore") },
            logEpoch = epoch, revision = revision,
            turn = ZTurn.parse(data.optJSONObject("turn")),
            notice = noticeForPhase(data.string("phase"), data.string("lastError").ifBlank { it.notice })) }
        if (entering != null && entrySelection === entering) {
            entrySelection = null
            pausedAux.remove("aux:quota")
            autoRefreshQuota()
        }
        reconcileOutgoing(session.id)
        // Snooze intent may predate this snapshot (question arrived first), and a failed
        // earlier attempt becomes eligible again: re-arm against the fresh state.
        interactions.forEach { interaction ->
            if (interaction.id.isNotBlank()) maybeSnooze(session, interaction)
        }
    }
    fun history() = action {
        val state = mutable.value; val session = state.selected ?: return@action
        val cursor = state.beforeRow ?: return@action
        val scope = RequestScope(generation, sessionId = session.id)
        mutable.update { it.copy(historyLoading = true) }
        try {
            val data = call("session/history", session.target().put("beforeRowId", cursor))
            check(data.string("logEpoch") == state.logEpoch && data.optLong("revision") == state.revision) { "会话已更新，请刷新后重试" }
            if (!scope.appliesToSession(generation, mutable.value.selected?.id)) return@action
            older = (zcodeItems(data) + older).distinctBy { it.id }
            mutable.update { it.copy(items = (older + it.items).distinctBy { row -> row.id },
                beforeRow = if (data.optBoolean("hasMore") && !data.isNull("beforeRowId")) data.optDouble("beforeRowId") else null) }
            // An older page may contain the formal message of a local record; drop it only
            // after the paginated items are published and the scope still applies.
            reconcileOutgoing(session.id)
        } finally {
            // Never clear the loading state of a session opened while this page was in flight.
            if (scope.appliesToSession(generation, mutable.value.selected?.id))
                mutable.update { it.copy(historyLoading = false) }
        }
    }
    fun send() = action {
        val state = mutable.value; val session = state.selected ?: error("请先选择会话")
        val text = state.draft.trim()
        val attachments = state.attachments
        check(!state.attachmentsBusy) { "图片处理中，请稍候再发送" }
        check(text.isNotBlank() || attachments.isNotEmpty()) { "请输入消息或添加图片" }
        val params = session.target()
        val selection = selectionParams()
        params.put("selection", selection.get("selection")).put("guard", selection.get("guard"))
            .put("text", text).put("delivery", state.delivery)
        val ownerSelection = attachmentSelection
        val gen = generation
        val scope = RequestScope(gen, sessionId = session.id)
        val key = outgoingKey(session.id)
        val draftKey = key("draft", session.id)
        val commandId = UUID.randomUUID().toString()
        // The bubble appears before any RPC: pending persistence, the dispatch, and the ack
        // all happen after the user already sees the message with 发送中.
        addOutgoing(key, OutgoingMessage(session.id, commandId,
            listOf(text, attachments.joinToString(" ") { "[图片]" }).filter { it.isNotBlank() }.joinToString(" "),
            OutgoingState.SENDING, precedingItemIds = mutable.value.items.takeLast(32).map { it.id }))
        mutable.update { it.copy(sending = true, attachmentsBusy = true) }
        var dispatched = false
        var ackAccepted = false
        try {
            // The upload runs inside the try: its failure must flow the normal FAILED
            // receipt + finally reset like every other pre-dispatch failure. Attachment
            // params are assembled only afterwards — refs exist exclusively after upload.
            if (attachments.isNotEmpty()) {
                uploadAttachments(session)
                val sent = attachments.map { attachment ->
                    val ref = attachmentRefs[attachment.id]
                        ?: error("图片尚未上传完成：${attachment.displayName}")
                    obj("ref" to ref, "fileName" to attachment.fileName,
                        "mime" to attachment.mime, "bytes" to attachment.bytes)
                }
                params.put("attachments", org.json.JSONArray().apply { sent.forEach { put(it) } })
            }
            // dispatched flips only after the pending record was persisted and just before
            // the RPC leaves; a failed guard or vault write below this point is a definite
            // local failure (nothing was sent) and must not touch the previous pending.
            val result = write("session/send", params, commandId) { dispatched = true }
            if (result != null) {
                ackAccepted = true
                // Receipt follows the server's actual delivery; a requested guide may be
                // accepted as queue. It never claims the model has read the message.
                val delivery = result.optJSONObject("result")?.string("delivery").orEmpty()
                updateOutgoing(key, commandId) { it.copy(
                    state = if (delivery == "queue") OutgoingState.QUEUED else OutgoingState.ACCEPTED,
                    receipt = deliveryReceipt(delivery)) }
                // Only the sending session's stored draft is cleared, and the on-screen draft
                // only while that session is still open with the same text.
                if (vault.get(draftKey)?.trim() == text) vault.put(draftKey, "")
                if (scope.appliesToSession(generation, mutable.value.selected?.id) && mutable.value.draft.trim() == text)
                    mutable.update { it.copy(draft = "") }
                if (scope.appliesToSession(generation, mutable.value.selected?.id))
                    mutable.update { state -> state.copy(
                        attachments = state.attachments.filterNot { a -> attachments.any { b -> b.id == a.id } },
                        attachmentsBusy = if (ownerSelection == attachmentSelection) false else state.attachmentsBusy) }
                // Refresh only while the sending session is still the current one on the
                // current connection; a stale read must not report errors into a new session.
                if (scope.appliesToSession(generation, mutable.value.selected?.id)) read(session)
            }
        } catch (e: AckRejectedException) {
            // The server explicitly rejected the command: 发送失败, draft kept, pending cleared.
            if (!ackAccepted) updateOutgoing(key, commandId) { it.copy(state = OutgoingState.FAILED) }
            throw e
        } catch (e: RpcException) {
            if (!ackAccepted) {
                // An upload RPC may fail before session/send is dispatched. No message
                // can have landed then, regardless of the upload error code.
                // After dispatch, only definite validation failures permit retry.
                if (!dispatched || e.code == -32602) updateOutgoing(key, commandId) { it.copy(state = OutgoingState.FAILED) }
                else markOutgoingUncertain(key, commandId)
            }
            throw e
        } catch (e: CancellationException) {
            // A dispatched write whose channel settled without an ack (disconnect, request
            // timeout, or caller cancellation) may still land → 结果待确认 with pending
            // kept. Before dispatch, nothing was sent → 发送失败. Never auto-replayed.
            if (dispatched && !ackAccepted) markOutgoingUncertain(key, commandId)
            else if (!dispatched) updateOutgoing(key, commandId) { it.copy(state = OutgoingState.FAILED) }
            throw e
        } catch (e: Exception) {
            if (dispatched && !ackAccepted) {
                markOutgoingUncertain(key, commandId)
            } else if (!dispatched) {
                updateOutgoing(key, commandId) { it.copy(state = OutgoingState.FAILED) }
            }
            throw e
        } finally {
            // Compare the captured scope against the CURRENT generation: a reconnect must
            // detach this request's finally from the new connection's state.
            if (scope.appliesToSession(generation, mutable.value.selected?.id))
                mutable.update { it.copy(sending = false, attachmentsBusy = if (ownerSelection == attachmentSelection) false else it.attachmentsBusy) }
        }
    }

    // ---- Image attachments (task 2026-11-03-image-attachments) ----

    private val imageProcessor by lazy { ImageProcessor(app) }
    private var attachmentSelection = 0L
    private val attachmentSerial = java.util.concurrent.atomic.AtomicLong(0)
    /** attachmentId -> committed upstream ref, valid for this connection scope only. */
    private val attachmentRefs = LinkedHashMap<String, String>()
    private val attachmentUploading = HashSet<String>()
    private var attachmentsEnabled = false

    fun attachmentScope(): String = "${generation}:${attachmentSelection}:${mutable.value.selected?.id}"

    fun addAttachment(uri: Uri, displayName: String, mime: String?, scope: String = attachmentScope()) {
        if (scope != attachmentScope()) return
        if (mutable.value.attachmentsBusy) return
        check(mutable.value.attachments.size < ImagePolicy.MESSAGE_MAX) { "一条消息最多 ${ImagePolicy.MESSAGE_MAX} 张图片" }
        // Scope-bound pick: late results after a session switch or reconnect are dropped.
        val generation = generation
        val selection = attachmentSelection
        val sessionId = mutable.value.selected?.id ?: return
        val serial = attachmentSerial.incrementAndGet()
        mutable.update { it.copy(attachmentsBusy = true, error = null) }
        viewModelScope.launch {
            val processed = withContext(Dispatchers.IO) { imageProcessor.process(uri, displayName, mime) }
            if (attachmentSerial.get() != serial) return@launch
            mutable.update { state ->
                if (generation != this@ZcodeViewModel.generation || selection != attachmentSelection || state.selected?.id != sessionId)
                    state.copy(attachmentsBusy = false)
                else {
                    val value = processed.getOrElse { e ->
                        return@update state.copy(attachmentsBusy = false, error = e.message ?: "图片处理失败")
                    }
                    if (state.attachments.size >= ImagePolicy.MESSAGE_MAX)
                        state.copy(attachmentsBusy = false, error = "一条消息最多 ${ImagePolicy.MESSAGE_MAX} 张图片")
                    else state.copy(attachments = state.attachments + value, attachmentsBusy = false)
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        if (mutable.value.attachmentsBusy || id in attachmentUploading) return
        mutable.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }, error = null) }
        attachmentRefs.remove(id)
    }

    /** Uploads every attachment without a ref through the two-stage bridge transaction.
     *  Serial by design; any failure keeps the batch (uploaded refs stay reusable). */
    private suspend fun uploadAttachments(session: ZSession) {
        val startGeneration = generation
        val selection = attachmentSelection
        val connection = client()
        fun stillValid() = startGeneration == generation && selection == attachmentSelection &&
            mutable.value.selected?.id == session.id
        val pending = mutable.value.attachments.filter { it.id !in attachmentRefs }
        for (attachment in pending) {
            attachmentUploading.add(attachment.id)
            try {
                check(stillValid()) { "连接已切换，请重新上传图片" }
                val uploadId = "upl-${attachment.id}"
                val begin = connection.call("session/attachment/begin",
                    AttachmentUploadPlan.beginParams(session.target(), attachment), timeout = 90_000)
                check(stillValid()) { "连接已切换，请重新上传图片" }
                if (begin.string("state") != "committed") {
                    var index = begin.optInt("nextChunkIndex", 0)
                    while (true) {
                        val chunk = AttachmentUploadPlan.chunkParams(uploadId, attachment, index) ?: break
                        val ack = connection.call("session/attachment/chunk", chunk, timeout = 90_000)
                        check(stillValid()) { "连接已切换，请重新上传图片" }
                        if (ack.optInt("nextChunkIndex", index) <= index) break
                        index = ack.optInt("nextChunkIndex")
                    }
                    check(index == AttachmentUploadPlan.totalChunks(attachment.bytes)) { "图片上传未完成，请重试" }
                    val commit = connection.call("session/attachment/commit", obj("uploadId" to uploadId), timeout = 90_000)
                    check(stillValid()) { "连接已切换，请重新上传图片" }
                    check(commit.string("state") == "committed") { "图片提交未确认，请重试" }
                    if (stillValid()) attachmentRefs[attachment.id] = commit.string("ref")
                } else if (stillValid()) {
                    attachmentRefs[attachment.id] = begin.string("ref")
                }
            } finally {
                if (!stillValid()) withContext(NonCancellable) {
                    // Abort only this old transaction on its captured connection.
                    runCatching { withTimeout(3000) { connection.call("session/attachment/abort",
                        obj("uploadId" to "upl-${attachment.id}"), timeout = 2500) } }
                }
                attachmentUploading.remove(attachment.id)
            }
        }
    }
    fun stop() = action {
        val state = mutable.value; val session = state.selected ?: return@action
        check(state.executionId.isNotBlank()) { "当前没有可停止的执行" }
        write("session/stop", session.target().put("executionId", state.executionId))
        read(session)
    }

    // ---- Queue operations & turn diff (task 2026-11-02 zcode-p1-parity) ----
    // CAS discipline: baseRevision is the revision of the snapshot the user is acting on
    // (state.revision, updated only by authoritative reads). The bridge refuses to invent
    // one; a server stale surfaces as an error — we never auto-retry the command.

    data class QueueContext(val session: ZSession, val revision: Long, val logEpoch: String, val generation: Int)
    fun queueContext(state: ZcodeState): QueueContext? = state.selected?.let {
        QueueContext(it, state.revision, state.logEpoch, generation)
    }
    private fun queueWrite(method: String, queued: QueuedMessage,
        context: QueueContext? = queueContext(mutable.value), extra: JSONObject.() -> Unit = {}) = action {
        val state = mutable.value
        val captured = context ?: return@action
        check(captured.generation == generation && sameTarget(state.selected, captured.session) &&
            captured.logEpoch == state.logEpoch) { "会话已变化，请重新打开队列操作" }
        val session = captured.session
        check(state.queueOpsEnabled) { "开发机桥接器暂不支持队列操作，请更新桥接器" }
        check(state.logEpoch.isNotBlank()) { "会话尚未加载完成，请稍候重试" }
        write(method, session.target().put("queueItemId", queued.id)
            .put("baseRevision", captured.revision).apply(extra))
        read(session)
    }
    fun removeQueued(queued: QueuedMessage) = queueWrite("session/queue/remove", queued)
    fun promoteQueued(queued: QueuedMessage) = queueWrite("session/queue/promote", queued)
    fun editQueued(queued: QueuedMessage, text: String, context: QueueContext? = queueContext(mutable.value)) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { mutable.update { it.copy(error = "排队内容不能为空") }; return }
        queueWrite("session/queue/edit", queued, context) { put("text", trimmed) }
    }

    /** Loads the per-file detail for the current turn via session/diff; result opens the
     *  shared detail sheet. Row identity and version come from the same rendered snapshot. */
    fun loadTurnDiff() = action {
        val state = mutable.value
        val session = state.selected ?: return@action
        val turn = state.turn ?: return@action
        check(state.diffEnabled) { "开发机桥接器暂不支持更改明细，请更新桥接器" }
        check(turn.entityId.isNotBlank()) { "该轮次缺少行标识，请刷新会话后重试" }
        val data = call("session/diff", session.target()
            .put("rowId", turn.rowId).put("entityId", turn.entityId)
            .put("baseRevision", state.revision).put("baseLogEpoch", state.logEpoch))
        mutable.update { it.copy(diffText = formatFileChanges(data)) }
    }
    fun clearTurnDiff() { mutable.update { it.copy(diffText = null) } }
    private suspend fun write(method: String, params: JSONObject, commandId: String? = null,
                             onDispatch: () -> Unit = {}): JSONObject? {
        check(pending == null) { "上一条命令结果尚未确认，请先刷新查询" }
        val command = params.put("commandId", commandId ?: UUID.randomUUID().toString())
        // Persist before the member record and any UI state: a failed vault write must
        // leave the previous pending protection and the outgoing records untouched.
        val record = obj("method" to method, "params" to command)
        vault.put(key("pending"), record.toString())
        pending = record
        // commandInFlight hides the pending warning for any ordinary in-flight command
        // (send/stop/create) without touching the persisted pending protection itself.
        mutable.update { it.copy(pending = true, pendingUnknown = false, commandInFlight = true) }
        onDispatch()
        try {
            val ack = call(method, command)
            finishAck(ack)
            return ack
        } catch (e: RpcException) {
            // Request validation failures are definite. Transport/adapter failures can be uncertain.
            if (e.code == -32602) clearPending()
            throw e
        } finally {
            mutable.update { it.copy(commandInFlight = false) }
        }
    }
    private fun clearPending() {
        vault.put(key("pending"), null); pending = null
        mutable.update { it.copy(pending = false, pendingUnknown = false) }
    }
    private fun finishAck(ack: JSONObject) {
        val status = ack.string("status")
        check(status in setOf("accepted", "duplicate", "noop", "rejected", "stale", "failed")) { "命令状态未知，请刷新查询" }
        clearPending()
        if (status !in setOf("accepted", "duplicate", "noop"))
            throw AckRejectedException("ZCode 未接受命令：${ack.string("reasonCode", status)}")
        val delivery = ack.optJSONObject("result")?.string("delivery")
        mutable.update { it.copy(notice = when (delivery) {
            "guide" -> "已插入当前任务"; "queue" -> "ZCode 已排队，等待当前任务完成"
            "startNow" -> "已开始执行"; else -> "命令已接收"
        }) }
    }
    private suspend fun recoverPending() {
        val pending = pending ?: return
        val epoch = generation
        val navigation = navigationVersion
        val params = pending.getJSONObject("params")
        val sessionTarget = params.string("sessionId")
        val commandId = params.string("commandId")
        // Captured before the query: a late result must never be keyed with a newer profile.
        val targetKey = if (sessionTarget.isNotBlank()) outgoingKey(sessionTarget) else null
        val result = call("command/query", params).optJSONObject("ack")
        if (epoch != generation || this.pending !== pending) return
        if (result == null) {
            mutable.update { it.copy(pendingUnknown = true, notice = "上一条命令结果未知。请核对会话后再解除发送保护。") }
            return
        }
        try {
            finishAck(result)
        } catch (e: AckRejectedException) {
            // The recovered command was explicitly rejected: create or update the FAILED
            // record for exactly the recorded session (also after a restart, when the map
            // is still empty), keep the draft, and leave every other session untouched.
            if (pending.string("method") == "session/send" && targetKey != null && commandId.isNotBlank()) {
                if (outgoingBySession[targetKey]?.any { it.clientId == commandId } == true)
                    updateOutgoing(targetKey, commandId) { m ->
                        if (m.state == OutgoingState.SENDING || m.state == OutgoingState.UNCERTAIN)
                            m.copy(state = OutgoingState.FAILED) else m
                    }
                else
                    addOutgoing(targetKey, OutgoingMessage(sessionTarget, commandId,
                        params.string("text"), OutgoingState.FAILED))
            }
            throw e
        }
        if (pending.string("method") == "session/send") {
            val id = params.string("sessionId")
            if (vault.get(key("draft", id))?.trim() == params.string("text")) vault.put(key("draft", id), "")
            if (mutable.value.selected?.id == id && mutable.value.draft.trim() == params.string("text")) draft("")
            // The recovered command was accepted; keep its bubble visible until the next
            // history read reconciles it, so the return to this session has no empty gap.
            val commandId = params.string("commandId")
            if (commandId.isNotBlank()) {
                val delivery = result.optJSONObject("result")?.string("delivery").orEmpty()
                addOutgoing(outgoingKey(id), OutgoingMessage(id, commandId, params.string("text"),
                    if (delivery == "queue") OutgoingState.QUEUED else OutgoingState.ACCEPTED,
                    deliveryReceipt(delivery)))
            }
        }
        if (pending.string("method") == "session/create") {
            val id = result.optJSONObject("result")?.string("sessionId").orEmpty()
            if (id.isNotBlank() && navigation == navigationVersion) {
                select(ZSession(id, "新会话", params.string("workspacePath"), project = params.string("projectId")))
                recoverCurrent()
            }
        }
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }

    // ---- Interaction answering (task 2026-11-02-interaction-approvals) ----
    // Submit / confirm / recover runs on its own channel: it never touches the message
    // path's write()/finishAck()/pending protection, never sets the global busy flag, and
    // persists metadata only — answer text, form drafts and sensitive values stay in memory.

    /** Unconfirmed interaction answers, keyed by the full target identity. */
    private data class InteractRecord(
        val workspacePath: String, val workspaceIdentity: String, val sessionId: String,
        val interactionId: String, val commandId: String, val phase: String,
        val summary: String, val issuedAt: Long, val noticedStillPending: Boolean = false
    ) {
        fun json() = obj("workspacePath" to workspacePath, "workspaceIdentity" to workspaceIdentity,
            "sessionId" to sessionId, "interactionId" to interactionId, "commandId" to commandId,
            "phase" to phase, "summary" to summary, "issuedAt" to issuedAt)
        companion object {
            fun parse(j: JSONObject) = InteractRecord(j.string("workspacePath"), j.string("workspaceIdentity"),
                j.string("sessionId"), j.string("interactionId"), j.string("commandId"),
                j.string("phase").ifBlank { "uncertain" },
                j.string("summary").takeIf { it in setOf("option", "text", "form") } ?: "answer", j.optLong("issuedAt"))
        }
    }

    // Snooze bookkeeping is generation-scoped: intent survives snapshot rebuilds inside one
    // connection, attempts never survive a reconnect. Bounded: 3 tries per interaction.
    private class SnoozeState(val session: ZSession, val interactionId: String) {
        var userInteracted = false
        var inFlight = false
        var ackAccepted = false
        var attempts = 0
        var commandId: String? = null
        var nextEligibleAt = 0L
    }

    private val interactMutex = Mutex()
    private val interactRecords = LinkedHashMap<String, InteractRecord>()
    private val snoozeStates = HashMap<String, SnoozeState>()
    private var interactSupport = false

    private data class InteractionScope(val owner: String, val generation: Int)
    private fun interactionScope() = InteractionScope(key("pending-interact"), generation)
    private fun current(scope: InteractionScope) = scope == interactionScope()
    private var interactOwner: String? = null
    private val interactionFlights = mutableSetOf<String>()
    private fun sameTarget(a: ZSession?, b: ZSession) =
        a?.id == b.id && a.path == b.path && a.identity == b.identity
    private fun interactKey(path: String, identity: String, sessionId: String, interactionId: String) =
        org.json.JSONArray(listOf(path, identity, sessionId, interactionId)).toString()
    private fun interactKey(record: InteractRecord) =
        interactKey(record.workspacePath, record.workspaceIdentity, record.sessionId, record.interactionId)
    private fun interactKey(session: ZSession, interactionId: String) =
        interactKey(session.path, session.identity, session.id, interactionId)
    private fun belongs(record: InteractRecord, session: ZSession) = record.sessionId == session.id &&
        record.workspacePath == session.path && record.workspaceIdentity == session.identity

    private fun resetInteractionEnvironment() {
        // Never write the new environment's vault while clearing old in-memory state.
        interactOwner = null
        interactRecords.clear()
        interactionFlights.clear()
        snapshotVersions.clear()
        snapshotRequests.clear()
        snoozeStates.clear()
    }

    /** Called under the disk-only mutex. Missing storage replaces old memory with empty state. */
    private fun loadInteractRecords(scope: InteractionScope) {
        if (interactOwner == scope.owner) return
        val raw = vault.get(scope.owner)
        val restored = raw?.let { org.json.JSONArray(it).objects().map(InteractRecord::parse) }.orEmpty()
        check(restored.size <= INTERACT_RECORDS_MAX)
        interactRecords.clear()
        restored.forEach { interactRecords[interactKey(it)] = it.copy(phase = "uncertain") }
        interactOwner = scope.owner
    }

    /** Every mutation carries its original owner and generation; stale callbacks are no-ops. */
    private suspend fun updateInteractRecords(scope: InteractionScope,
        transform: (List<InteractRecord>) -> List<InteractRecord>): Boolean {
        val applied = interactMutex.withLock {
            if (!current(scope)) return@withLock false
            loadInteractRecords(scope)
            val before = interactRecords.values.toList()
            val updated = transform(before)
            check(updated.size <= INTERACT_RECORDS_MAX) { "待确认交互记录过多，请先核对处理" }
            if (updated != before) {
                vault.put(scope.owner, org.json.JSONArray().apply { updated.forEach { put(it.json()) } }.toString())
                interactRecords.clear()
                updated.forEach { interactRecords[interactKey(it)] = it }
            }
            true
        }
        if (applied) publishInteractState()
        return applied
    }

    private fun publishInteractState() {
        val selected = mutable.value.selected
        val records = if (interactOwner == key("pending-interact") && selected != null)
            interactRecords.values.filter { belongs(it, selected) } else emptyList()
        mutable.update { it.copy(interactSubmitting = records.map { r -> r.interactionId }.toSet(),
            interactReleasable = records.filter { r -> r.phase != "submitting" && r.commandId !in interactionFlights }
                .map { r -> r.interactionId }.toSet()) }
        publishSnoozeHint()
    }

    private fun publishSnoozeHint() {
        val unconfirmed = snoozeStates.values
            .filter { it.userInteracted && !it.ackAccepted && sameTarget(mutable.value.selected, it.session) }
            .map { it.interactionId }.toSet()
        mutable.update { it.copy(snoozeUnconfirmed = unconfirmed) }
    }

    /** Submit an answer for a supported interaction. Persist-before-send; uncertain outcomes
     *  stay disabled until an authoritative snapshot or command/query reconciles them. */
    fun interact(session: ZSession, interaction: ZInteraction, answer: JSONObject) {
        if (!interactSupport || !mutable.value.connected || !sameTarget(mutable.value.selected, session) ||
            !interaction.supported || interaction.id.isBlank() ||
            mutable.value.interactions.none { it === interaction && it.supported }) return
        noteInteractionTouch(session, interaction)
        val targetKey = interactKey(session, interaction.id)
        val commandId = UUID.randomUUID().toString()
        // Fixed operation names only: option IDs can themselves contain sensitive answers.
        val summary = when {
            answer.has("optionId") -> "option"
            answer.has("freeText") -> "text"
            else -> "form"
        }
        val connection = rpc ?: return
        val scope = interactionScope()
        viewModelScope.launch {
            var inserted = false
            try {
                updateInteractRecords(scope) { records ->
                    if (records.any { interactKey(it) == targetKey }) records else {
                        inserted = true
                        records + InteractRecord(session.path, session.identity, session.id, interaction.id,
                            commandId, "submitting", summary, System.currentTimeMillis())
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (current(scope)) mutable.update { it.copy(interactionNotice = "无法保存应答记录，应答尚未发送") }
                return@launch
            }
            if (!inserted || !current(scope)) return@launch
            interactionFlights.add(commandId)
            suspend fun settle(phase: String?): Boolean = try {
                updateInteractRecords(scope) { records -> records.mapNotNull { r ->
                    if (interactKey(r) != targetKey || r.commandId != commandId) r
                    else phase?.let { r.copy(phase = it) }
                } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // The original write-ahead receipt remains authoritative if saving an ACK
                // fails. Keep protection and allow query recovery after storage recovers.
                if (current(scope) && sameTarget(mutable.value.selected, session))
                    mutable.update { it.copy(interactionNotice = "无法保存确认结果，请恢复存储后刷新核对") }
                false
            }
            try {
                val params = session.target().put("interactionId", interaction.id)
                    .put("commandId", commandId).put("answer", answer)
                val ack = connection.call("session/interact", params, timeout = 90_000)
                if (!current(scope)) return@launch
                when (ack.string("status")) {
                    "accepted", "duplicate", "noop" -> {
                        if (!settle("awaiting")) return@launch
                        interactionFlights.remove(commandId)
                        publishInteractState()
                        refreshInteraction(session, interaction.id, scope.generation)
                    }
                    "rejected", "stale", "failed" -> {
                        if (!settle(null)) return@launch
                        if (sameTarget(mutable.value.selected, session))
                            mutable.update { it.copy(interactionNotice = "ZCode 未接受应答，请核对后重新填写") }
                    }
                    else -> {
                        if (!settle("uncertain")) return@launch
                        if (sameTarget(mutable.value.selected, session))
                            mutable.update { it.copy(interactionNotice = "应答结果未知，请核对会话后刷新") }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!current(scope)) return@launch
                val rejected = e is RpcException && e.code == -32602
                if (!settle(if (rejected) null else "uncertain")) return@launch
                if (sameTarget(mutable.value.selected, session)) mutable.update { it.copy(interactionNotice =
                    if (rejected) "应答格式被拒绝，请核对后重新填写" else "应答结果未知，请核对会话后刷新") }
            } finally {
                if (current(scope)) { interactionFlights.remove(commandId); publishInteractState() }
            }
        }
    }

    fun interactOption(session: ZSession, interaction: ZInteraction, optionId: String) =
        interact(session, interaction, obj("optionId" to optionId))
    fun interactFreeText(session: ZSession, interaction: ZInteraction, text: String) =
        interact(session, interaction, obj("freeText" to text))
    fun interactForm(session: ZSession, interaction: ZInteraction, questions: List<ZQuestion>, drafts: Map<String, ZFormDraft>) =
        interact(session, interaction, obj("action" to "accept", "content" to buildInteractionContent(questions, drafts)))
    fun interactDecline(session: ZSession, interaction: ZInteraction) = interact(session, interaction, obj("action" to "decline"))
    fun interactCancel(session: ZSession, interaction: ZInteraction) = interact(session, interaction, obj("action" to "cancel"))

    /** Manual reconciliation entry: drops this target's unconfirmed flag without replaying
     *  the old answer; re-answering is a fresh explicit submit (first-wins upstream). */
    fun releaseInteraction(session: ZSession, interactionId: String) {
        val scope = interactionScope()
        val record = interactRecords[interactKey(session, interactionId)] ?: return
        if (!sameTarget(mutable.value.selected, session) || record.phase == "submitting" ||
            record.commandId in interactionFlights) return
        viewModelScope.launch {
            try {
                updateInteractRecords(scope) { records -> records.filterNot {
                    it == record && it.commandId !in interactionFlights
                } }
                if (current(scope) && sameTarget(mutable.value.selected, session))
                    mutable.update { it.copy(interactionNotice = "") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }

    /** Authoritative re-read carrying checkInteractionIds so a presence=false can clear records. */
    private fun refreshInteraction(session: ZSession, interactionId: String, generation: Int) {
        viewModelScope.launch {
            actions.withLock {
                if (generation != this@ZcodeViewModel.generation) return@withLock
                try { read(session, checkInteractionIds = listOf(interactionId)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Poll keeps reconciling; the buttons stay disabled meanwhile. */ }
            }
        }
    }

    /**
     * Clear or keep records against one authoritative snapshot. Only an explicit
     * presence=false, or absence from an interactionsComplete=true snapshot of the same
     * target, proves an interaction ended — a truncated window or an unsupported kind never does.
     */
    private fun interactionPresence(data: JSONObject): Map<String, Boolean> =
        data.optJSONArray("interactionPresence")?.objects()?.mapNotNull {
            val pending = it.opt("pending") as? Boolean ?: return@mapNotNull null
            it.string("interactionId") to pending
        }?.toMap().orEmpty()

    private suspend fun reconcileInteractions(session: ZSession, data: JSONObject, scope: InteractionScope, eligibleReceipts: Set<String>) {
        val presence = interactionPresence(data)
        val complete = data.optBoolean("interactionsComplete")
        val detailIds = data.optJSONArray("interactions")?.objects()?.mapTo(HashSet()) { it.string("interactionId") }.orEmpty()
        fun ended(id: String) = presence[id] == false ||
            (presence[id] != true && complete && id !in detailIds)
        var endedAny = false
        var stillPending = false
        updateInteractRecords(scope) { records -> records.mapNotNull { record ->
            if (!belongs(record, session)) record
            // A snapshot cannot settle an RPC which was initiated after that snapshot was requested.
            else if (record.commandId in interactionFlights || record.commandId !in eligibleReceipts) record
            else if (ended(record.interactionId)) { endedAny = true; null }
            else { if (record.phase == "awaiting") stillPending = true; record }
        } }
        if (!current(scope)) return
        snoozeStates.entries.removeAll { sameTarget(it.value.session, session) && ended(it.value.interactionId) }
        publishSnoozeHint()
        if (sameTarget(mutable.value.selected, session)) {
            val notice = when {
                endedAny -> "交互已结束"
                stillPending -> "应答已接收，交互仍在等待；请核对"
                else -> null
            }
            notice?.let { text -> mutable.update { it.copy(interactionNotice = text) } }
        }
    }

    /** Recover without replay, including unknown query results: presence can still prove the end. */
    private suspend fun recoverInteractions() {
        if (!interactSupport) return
        val scope = interactionScope()
        updateInteractRecords(scope) { it }
        val connection = client()
        val records = interactRecords.values.toList()
        for (record in records) {
            if (!current(scope)) return
            if (record.commandId in interactionFlights) continue
            val session = ZSession(record.sessionId, record.sessionId, record.workspacePath, record.workspaceIdentity)
            try {
                val ack = try {
                    connection.call("command/query", session.target().put("commandId", record.commandId))
                        .optJSONObject("ack")
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (!current(scope)) return
                val status = ack?.string("status")
                updateInteractRecords(scope) { list -> list.mapNotNull { r ->
                    if (r.commandId != record.commandId || interactKey(r) != interactKey(record)) r
                    else when (status) {
                        "rejected", "stale", "failed" -> null
                        "accepted", "duplicate", "noop" -> r.copy(phase = "awaiting")
                        else -> r.copy(phase = "uncertain")
                    }
                } }
                if (status !in setOf("accepted", "duplicate", "noop", "rejected", "stale", "failed") &&
                    sameTarget(mutable.value.selected, session))
                    mutable.update { it.copy(interactionNotice = "交互应答结果未知，请核对") }
                // Read even when query is unknown or fails; never infer failure from missing ACK.
                read(session, checkInteractionIds = listOf(record.interactionId))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Receipt stays protected if authoritative confirmation fails. */ }
        }
    }

    // ---- autoResolution snooze (task R6): intent first, bounded retries, server-confirmed only ----

    /** Record the user's first touch on an interaction; autoResolution may arrive later. */
    fun noteInteractionTouch(session: ZSession, interaction: ZInteraction) {
        if (!interactSupport || !mutable.value.connected || !sameTarget(mutable.value.selected, session) ||
            interaction.id.isBlank() || mutable.value.interactions.none { it.id == interaction.id }) return
        val state = snoozeStates.getOrPut(interactKey(session, interaction.id)) { SnoozeState(session, interaction.id) }
        state.userInteracted = true
        maybeSnooze(session, interaction)
    }

    private fun maybeSnooze(session: ZSession, interaction: ZInteraction) {
        if (!interactSupport || !mutable.value.connected || !sameTarget(mutable.value.selected, session)) return
        val key = interactKey(session, interaction.id)
        val state = snoozeStates[key] ?: return
        val auto = interaction.autoResolution
            ?: return // Not arrived yet: the intent stays; the next snapshot retries.
        if (auto.state == "snoozed") { state.ackAccepted = true; publishSnoozeHint(); return }
        if (!state.userInteracted || state.ackAccepted || state.inFlight || state.attempts >= 3) return
        if (System.currentTimeMillis() < state.nextEligibleAt) return
        val connection = rpc ?: return
        val generation = this.generation
        state.inFlight = true
        state.attempts++
        if (state.commandId == null) state.commandId = UUID.randomUUID().toString()
        val commandId = state.commandId!!
        viewModelScope.launch {
            try {
                val ack = connection.call("session/interact/snooze", session.target()
                    .put("interactionId", interaction.id).put("commandId", commandId), timeout = 30_000)
                if (generation != this@ZcodeViewModel.generation || snoozeStates[key] !== state) return@launch
                val current = state
                current.inFlight = false
                if (ack.string("status") in setOf("accepted", "duplicate", "noop")) {
                    current.ackAccepted = true
                } else {
                    if (ack.string("status") in setOf("rejected", "stale", "failed")) current.commandId = null
                    current.nextEligibleAt = System.currentTimeMillis() + snoozeBackoff(current.attempts)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation != this@ZcodeViewModel.generation) return@launch
                snoozeStates[key]?.takeIf { it === state }?.let { current ->
                    current.inFlight = false
                    if (e is RpcException && e.code == -32602) current.commandId = null
                    // Timeout with unknown outcome: retry reuses this command id (idempotent upstream).
                    current.nextEligibleAt = System.currentTimeMillis() + snoozeBackoff(current.attempts)
                }
            }
            finally { if (generation == this@ZcodeViewModel.generation) publishSnoozeHint() }
        }
        publishSnoozeHint()
    }
    private fun snoozeBackoff(failedAttempts: Int) = if (failedAttempts <= 1) 1_000L else 3_000L
    fun releaseUnknown() {
        if (!mutable.value.busy && mutable.value.pendingUnknown) {
            // Manual reconciliation of the GLOBAL pending command: its bubble belongs to the
            // session recorded in the pending params, not to whichever session is on screen.
            val params = pending?.optJSONObject("params")
            val commandId = params?.string("commandId")
            val pendingSession = params?.string("sessionId")
            clearPending()
            if (!pendingSession.isNullOrBlank()) removeOutgoing(outgoingKey(pendingSession), commandId)
        }
    }
    private suspend fun loadStatuses() {
        if (!supportsSessionIndex || !active || !visible) return
        val epoch = generation
        try {
            val data = call("sessions/status")
            if (epoch == generation && active && visible) {
                val statuses = zcodeLiveStatuses(data)
                mutable.update { it.copy(liveStatuses = statuses) }
                publishListMeta()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (epoch == generation) mutable.update { it.copy(liveStatuses = emptyList(), listMeta = emptyMap()) }
        }
    }

    /** List metadata joins live statuses with local seen timestamps; rebuilt on every
     *  status read and project refresh so removed sessions drop out immediately. */
    private fun publishListMeta() {
        if (!mutable.value.indexMetaEnabled) return
        mutable.update { it.copy(listMeta = buildListMeta(it.sessions, it.liveStatuses,
            seenActivity.toMap(), it.selected?.id)) }
    }
    private fun pauseStatuses() {
        mutable.update { it.copy(liveStatuses = emptyList()) }
        if (!supportsSessionIndex) return
        val epoch = generation
        viewModelScope.launch {
            if (epoch != generation || (active && visible) || !mutable.value.connected) return@launch
            try { withContext(RecoveryDeadline(System.nanoTime() + 45_000_000_000L)) { call("sessions/pause") } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Channel close also releases native subscriptions. */ }
        }
    }

    private fun schedulePoll() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive && visible && active) {
                delay(2500)
                if (!mutable.value.busy) {
                    recoverAux("statuses") { loadStatuses() }
                    if (mutable.value.selected != null) recoverCurrent(quiet = true)
                }
            }
        }
    }
    override fun onCleared() { rpc?.close(); transport?.close(); super.onCleared() }
}
