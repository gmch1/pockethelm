package com.gmch.codexremote

import android.app.Application
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

data class ZcodeState(
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
    val browser: ZcodeBrowserState = ZcodeBrowserState()
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
    private var resumeJob: Job? = null
    private var navigationVersion = 0

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
    private fun key(type: String, id: String = "") = "zcode:$type:${profile?.identity}:$id"
    private fun client() = rpc ?: error("请先连接 ZCode")
    private suspend fun call(method: String, params: JSONObject = obj()) = client().call(method, params, timeout = 90_000)
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        actions.withLock {
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "请求失败") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun enter() { active = true; foreground() }
    // A tab change is not an Activity background transition. Keep the channel, pause polling.
    fun leave() { active = false; poll?.cancel(); poll = null; pauseStatuses() }
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
            listSyncedAt = 0; historySyncedAt = 0
            older = emptyList(); outgoingBySession.clear(); pending = null
            mutable.value = ZcodeState()
        }
        if (!mutable.value.connected) { connect(); return }
        schedulePoll()
        if (resumeJob?.isActive == true) return
        // Read-only revalidation must not put the whole screen into its busy/disabled state.
        val epoch = generation
        resumeJob = viewModelScope.launch {
            actions.withLock {
                if (!active || !visible || epoch != generation) return@withLock
                try {
                    if (System.currentTimeMillis() - historySyncedAt > 5_000)
                        mutable.value.selected?.let { readGuarded(it) }
                    if (mutable.value.selected == null && System.currentTimeMillis() - listSyncedAt > 60_000)
                        loadProjects()
                    loadStatuses()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(error = "更新失败：${e.message}") } }
            }
        }
    }
    fun background() {
        if (!visible) return
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
    fun disconnectManually() = disconnect()
    private fun disconnect() {
        generation++
        supportsSessionIndex = false
        backgroundJob?.cancel(); backgroundJob = null
        browser(ZcodeBrowserEvent.Disconnect)
        job?.cancel(); job = null; poll?.cancel(); poll = null
        resumeJob?.cancel(); resumeJob = null
        rpc?.close(); rpc = null
        val old = transport; transport = null
        viewModelScope.launch(Dispatchers.IO) { old?.close() }
        // In-flight requests lose their channel: outcome unknown, never a failure.
        outgoingBySession.keys.toList().forEach { markOutgoingUncertain(it) }
        mutable.update {
            it.copy(connected = false, busy = false, sending = false, historyLoading = false, sessionsFresh = false, liveStatuses = emptyList(),
                // A session opened but never loaded must not fall back to a blank timeline.
                historyError = if (it.selected != null && it.items.isEmpty() && it.historyLoading)
                    "连接已断开，未能加载会话内容，重连后重试" else it.historyError,
                commandInFlight = false)
        }
    }
    fun connect() {
        if (job?.isActive == true) return
        if (mutable.value.connected) { refresh(); return }
        job = action {
            restoreJob.join()
            val raw = vault.get("profile") ?: error("请先在连接页扫码配对开发机")
            val next = ConnectionProfile.from(JSONObject(raw))
            require(next.useDeviceKey) { "ZCode 需要扫码配对的受限设备连接" }
            if (profile?.resumeIdentity != next.resumeIdentity) {
                mutable.value = ZcodeState(busy = true)
                older = emptyList()
                // A different environment owns no local records from the previous one.
                outgoingBySession.clear()
            }
            profile = next
            pending = vault.get(key("pending"))?.let(::JSONObject)
            mutable.update { it.copy(pending = pending != null) }
            val epoch = ++generation
            val ssh = SshTransport(); transport = ssh
            try {
                ssh.connect(next, vault, "", "", zcode = true)
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
                val hello = call("initialize", obj("clientId" to clientId))
                check(hello.string("engine") == "zcode" && hello.optInt("bridgeVersion") == 1) { "请在开发机安装 ZCode 桥接器" }
                supportsSessionIndex = hello.optBoolean("sessionsIndex")
                mutable.update { it.copy(connected = true) }
                recoverPending()
                mutable.value.selected?.let { session ->
                    try { readGuarded(session) } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "更新会话失败") } }
                }
                loadProjects()
                loadStatuses()
                loadCatalog()
                schedulePoll()
            } finally {
                if (!mutable.value.connected || epoch != generation) withContext(NonCancellable + Dispatchers.IO) { ssh.close() }
            }
        }
    }
    fun refresh() {
        if (!mutable.value.connected) { connect(); return }
        action { loadCatalog(); loadProjects(); loadStatuses(); recoverPending(); mutable.value.selected?.let { readGuarded(it) }; schedulePoll() }
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
        // readTree is a complete list. Removed sessions must not survive a cold-start snapshot.
        val selected = mutable.value.selected
        if (selected != null && mutable.value.historyCached && mutable.value.sessions.none { it.id == selected.id }) {
            navigationVersion++
            older = emptyList()
            mutable.update { it.copy(selected = null, items = emptyList(), queue = emptyList(), outgoing = emptyList(),
                draft = "", phase = "", executionId = "", historyLoading = false, historyCached = false,
                historyError = null, notice = "该会话已移除") }
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
        mutable.update { it.copy(selection = selection, selectionGuard = it.providers.find { p -> p.id == selection.provider }?.guard.orEmpty(), quota = "点击读取额度", quotaSummary = "额度 ▾", notice = "下次发送使用此计划和模型") }
    }
    private fun selectionParams(): JSONObject {
        val choice = mutable.value.selection ?: error("请选择计划与模型")
        val provider = mutable.value.providers.find { it.id == choice.provider } ?: error("计划已不可用，请刷新后选择")
        check(provider.models.any { it.id == choice.model && (if (it.levels.isEmpty()) choice.level.isEmpty() else choice.level in it.levels) }) { "请选择有效模型与推理强度" }
        check(mutable.value.selectionGuard == provider.guard) { "额度来源已变化，请重新选择计划后发送" }
        return obj("selection" to choice.json(), "guard" to mutable.value.selectionGuard)
    }
    fun quota() = action {
        val selected = mutable.value.selection
        val guard = mutable.value.selectionGuard
        val startGeneration = generation
        fun stillSelected() = generation == startGeneration && mutable.value.selection == selected && mutable.value.selectionGuard == guard
        mutable.update { it.copy(quota = "正在读取…", quotaSummary = "额度 ▾") }
        try {
            val data = call("quota", selectionParams())
            if (stillSelected()) mutable.update { it.copy(quota = zcodeQuota(data), quotaSummary = zcodeQuotaSummary(data, selected?.model.orEmpty())) }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (stillSelected()) mutable.update { it.copy(quota = "额度暂不可用，请重试", quotaSummary = "额度 ▾") } }
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
    fun open(session: ZSession) = action {
        navigationVersion++
        if (!mutable.value.connected && mutable.value.selected?.id == session.id) return@action
        select(session)
        if (mutable.value.connected) readGuarded(session, useSessionSelection = true)
        else mutable.update { it.copy(historyLoading = false, historyError = "连接后加载会话内容") }
    }
    fun retryLoad() = action {
        val session = mutable.value.selected ?: return@action
        if (mutable.value.items.isEmpty()) mutable.update { it.copy(historyError = null, historyLoading = true) }
        readGuarded(session)
    }
    // Only the first history of an empty session reports inline loading; refreshing an
    // already-loaded session keeps its messages visible instead of flashing a spinner.
    private suspend fun readGuarded(session: ZSession, useSessionSelection: Boolean = false) {
        val scope = RequestScope(generation, sessionId = session.id)
        try {
            read(session, useSessionSelection)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // A result from before a reconnect must not clear the new connection's loading state.
            if (scope.appliesToSession(generation, mutable.value.selected?.id) && mutable.value.items.isEmpty())
                mutable.update { it.copy(historyLoading = false, historyError = e.message ?: "加载会话内容失败") }
            throw e
        }
    }
    private fun select(session: ZSession) {
        mutable.value.selected?.let { vault.put(key("draft", it.id), mutable.value.draft) }
        older = emptyList()
        val outKey = outgoingKey(session.id)
        mutable.update { it.copy(selected = session, historyCached = false, items = emptyList(), queue = emptyList(), phase = "", executionId = "",
            quota = "点击读取额度", quotaSummary = "额度 ▾",
            historyLoading = true, historyError = null, sending = false,
            outgoing = (outgoingBySession[outKey] ?: emptyList()).toList(),
            draft = vault.get(key("draft", session.id)).orEmpty(), beforeRow = null, notice = "", currentSelection = null, permission = "") }
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
        if (mutable.value.busy) return
        navigationVersion++
        if (mutable.value.selected == null) return
        mutable.value.selected?.let { vault.put(key("draft", it.id), mutable.value.draft) }
        mutable.update { it.copy(selected = null, items = emptyList(), queue = emptyList(), draft = "", historyLoading = false,
            historyError = null, outgoing = emptyList(), sessionsFresh = it.connected && it.sessionsFresh) }
        older = emptyList()
        val epoch = generation
        viewModelScope.launch {
            actions.withLock {
                if (epoch != generation || !mutable.value.connected) return@withLock
                try {
                    if (!supportsSessionIndex || System.currentTimeMillis() - listSyncedAt > 60_000) loadProjects()
                    loadStatuses()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(sessionsFresh = false, liveStatuses = emptyList(), error = "更新列表失败：${e.message}") } }
            }
        }
    }

    fun draft(value: String) {
        mutable.update { it.copy(draft = value) }
        mutable.value.selected?.let { vault.put(key("draft", it.id), value) }
    }
    fun delivery(value: String) { mutable.update { it.copy(delivery = value) } }
    private suspend fun read(session: ZSession, useSessionSelection: Boolean = false) {
        val startGeneration = generation
        val data = call("session/read", session.target())
        // A read from before a reconnect must not clobber the new connection's state.
        if (generation != startGeneration || mutable.value.selected?.id != session.id) return
        historySyncedAt = System.currentTimeMillis()
        val items = zcodeItems(data)
        val revision = data.optLong("revision")
        val epoch = data.string("logEpoch")
        if (epoch != mutable.value.logEpoch || revision != mutable.value.revision) older = emptyList()
        val actual = ZSelection.parse(data.optJSONObject("selection"))
        val queue = queueEntries(data)
        val first = if (data.isNull("firstRowId")) null else data.optDouble("firstRowId")
        mutable.update { it.copy(selected = session.copy(title = data.string("title").ifBlank { session.title }),
            items = (older + items).distinctBy { row -> row.id }, phase = data.string("phase"), queue = queue,
            executionId = if (data.optBoolean("canStop")) data.string("executionId") else "",
            interactionCount = data.optInt("interactionCount"), currentSelection = actual,
            selection = if (useSessionSelection) actual ?: it.selection else it.selection,
            selectionGuard = if (useSessionSelection && actual != null) it.providers.find { p -> p.id == actual.provider }?.guard.orEmpty() else it.selectionGuard,
            permission = if (data.string("mode") == "yolo") "完全访问" else data.string("mode"),
            historyLoading = false, historyError = null, historyCached = false,
            beforeRow = if (older.isNotEmpty()) it.beforeRow else first?.takeIf { _ -> data.optBoolean("hasMore") },
            logEpoch = epoch, revision = revision,
            notice = noticeForPhase(data.string("phase"), data.string("lastError").ifBlank { it.notice })) }
        reconcileOutgoing(session.id)
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
        val text = state.draft.trim(); check(text.isNotBlank()) { "请输入消息" }
        val params = session.target()
        val selection = selectionParams()
        params.put("selection", selection.get("selection")).put("guard", selection.get("guard"))
            .put("text", text).put("delivery", state.delivery)
        val gen = generation
        val scope = RequestScope(gen, sessionId = session.id)
        val key = outgoingKey(session.id)
        val draftKey = key("draft", session.id)
        val commandId = UUID.randomUUID().toString()
        // The bubble appears before any RPC: pending persistence, the dispatch, and the ack
        // all happen after the user already sees the message with 发送中.
        addOutgoing(key, OutgoingMessage(session.id, commandId, text, OutgoingState.SENDING))
        mutable.update { it.copy(sending = true) }
        var dispatched = false
        var ackAccepted = false
        try {
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
                // -32602 is a definite validation failure; other bridge/transport errors keep
                // the pending protection and stay 结果待确认 — never 发送失败 with pending.
                if (e.code == -32602) updateOutgoing(key, commandId) { it.copy(state = OutgoingState.FAILED) }
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
            if (scope.appliesToSession(generation, mutable.value.selected?.id)) mutable.update { it.copy(sending = false) }
        }
    }
    fun stop() = action {
        val state = mutable.value; val session = state.selected ?: return@action
        check(state.executionId.isNotBlank()) { "当前没有可停止的执行" }
        write("session/stop", session.target().put("executionId", state.executionId))
        read(session)
    }
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
        val params = pending.getJSONObject("params")
        val sessionTarget = params.string("sessionId")
        val commandId = params.string("commandId")
        // Captured before the query: a late result must never be keyed with a newer profile.
        val targetKey = if (sessionTarget.isNotBlank()) outgoingKey(sessionTarget) else null
        val result = call("command/query", params).optJSONObject("ack")
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
            if (id.isNotBlank()) select(ZSession(id, "新会话", params.string("workspacePath"), project = params.string("projectId")))
        }
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
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
            if (epoch == generation && active && visible)
                mutable.update { it.copy(liveStatuses = zcodeLiveStatuses(data)) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (epoch == generation) mutable.update { it.copy(liveStatuses = emptyList()) }
        }
    }
    private fun pauseStatuses() {
        mutable.update { it.copy(liveStatuses = emptyList()) }
        if (!supportsSessionIndex) return
        val epoch = generation
        viewModelScope.launch {
            actions.withLock {
                if (epoch != generation || (active && visible) || !mutable.value.connected) return@withLock
                try { call("sessions/pause") }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Channel close also releases native subscriptions. */ }
            }
        }
    }
    private fun schedulePoll() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive && visible && active) {
                delay(2500)
                if (actions.tryLock()) {
                    try {
                        loadStatuses()
                        mutable.value.selected?.let { read(it) }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { mutable.update { it.copy(error = "刷新会话失败：${e.message}") } }
                    finally { actions.unlock() }
                }
            }
        }
    }
    override fun onCleared() { rpc?.close(); transport?.close(); super.onCleared() }
}
