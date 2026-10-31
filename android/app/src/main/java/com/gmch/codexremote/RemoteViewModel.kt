package com.gmch.codexremote

import android.app.Application
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UiState(
    val profile: ConnectionProfile = ConnectionProfile(), val saved: Boolean = false,
    val connected: Boolean = false, val busy: Boolean = false, val foreground: Boolean = false,
    val status: String = "尚未连接", val error: String? = null, val challenge: HostChallenge? = null,
    val tab: Int = 0, val sessions: List<SessionInfo> = emptyList(), val cursor: String? = null,
    val selected: SessionInfo? = null, val items: List<TimelineItem> = emptyList(), val historyCursor: String? = null,
    val outgoing: List<OutgoingMessage> = emptyList(),
    val queue: List<QueuedMessage> = emptyList(), val projects: List<String> = emptyList(),
    val draft: String = "", val uncertain: JSONObject? = null, val diff: String = "", val lastSync: Long = 0,
    val targetKeyName: String = "未导入", val jumpKeyName: String = "未导入", val version: String = "",
    val models: List<ModelOption> = emptyList(), val modelsLoading: Boolean = false, val modelsError: String? = null,
    val historyLoading: Boolean = false, val historyError: String? = null, val sending: Boolean = false,
    val modelSettings: ModelSettings? = null, val settingsNotice: String? = null,
    val quota: AccountQuota? = null, val quotaLoading: Boolean = false, val quotaError: String? = null
)

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = Vault(app)
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val actions = Mutex()
    private var transport: SshTransport? = null
    private var rpc: RpcClient? = null
    private var connectionJob: Job? = null
    private var refreshJob: Job? = null
    private var modelsJob: Job? = null
    private var quotaJob: Job? = null
    private var quotaDeferred = false
    private var quotaRefreshPending = false
    private var accountVersion = 0
    private val settingsRevisions = mutableMapOf<String, Long>()
    private var epoch = 0
    private var targetPassphrase = ""
    private var jumpPassphrase = ""
    private var selectionVersion = 0
    private val timeline = LinkedHashMap<String, TimelineItem>()
    private val statusRevisions = mutableMapOf<String, Long>()
    private val turnRevisions = mutableMapOf<String, Long>()
    // Locally-sent messages awaiting reconciliation, keyed by environment identity + session
    // so a profile switch or a conversation switch never leaks records across.
    private val outgoingBySession = LinkedHashMap<OutgoingKey, MutableList<OutgoingMessage>>()
    private var paintJob: Job? = null

    init {
        runCatching {
            // Retire only app-generated v1 credentials; imported administrator keys are untouched.
            vault.put("device-identity", null)
            var saved = vault.get("profile")
            saved?.let { raw ->
                val old = ConnectionProfile.from(JSONObject(raw))
                if (old.useDeviceKey && old.configVersion != 2) {
                    vault.put("profile", null); saved = null
                    mutable.update { it.copy(status = "旧设备凭据已停用，请重新扫码连接") }
                }
            }
            mutable.update { it.copy(profile = saved?.let { v -> ConnectionProfile.from(JSONObject(v)) } ?: ConnectionProfile(),
                saved = saved != null, tab = if (saved == null) 2 else 0,
                targetKeyName = vault.get("target-key-name") ?: "未导入", jumpKeyName = vault.get("jump-key-name") ?: "未导入") }
        }.onFailure { fail(it) }
    }

    private fun fail(t: Throwable) { mutable.update { it.copy(error = t.message ?: t.javaClass.simpleName) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun tab(index: Int) { mutable.update { it.copy(tab = index) } }
    private fun localKey(type: String, id: String) = "$type:${mutable.value.profile.identity}:$id"
    private fun client() = rpc ?: error("请先连接开发机")
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        actions.withLock {
            mutable.update { it.copy(busy = true, error = null) }
            try { block() } catch (e: CancellationException) { throw e } catch (t: Throwable) { fail(t) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun foreground() {
        mutable.update { it.copy(foreground = true) }
        if (mutable.value.saved) connect() // Existing live connections are refreshed, never replaced blindly.
    }
    fun background() {
        mutable.update { it.copy(foreground = false) }
        disconnect("已离开前台 · 执行保留在开发机")
        targetPassphrase = ""; jumpPassphrase = ""
    }
    fun disconnect(reason: String = "已断开 · 执行保留在开发机") {
        epoch++
        connectionJob?.cancel(); connectionJob = null; refreshJob?.cancel()
        modelsJob?.cancel(); quotaJob?.cancel(); quotaRefreshPending = false
        rpc?.close(); rpc = null
        val old = transport; transport = null
        viewModelScope.launch(Dispatchers.IO) { old?.close() }
        // A request still in flight loses its channel: the outcome is unknown, never a failure.
        outgoingBySession.keys.toList().forEach { markOutgoingUncertain(it) }
        mutable.update {
            it.copy(connected = false, busy = false, sending = false, historyLoading = false,
                // A session opened but never loaded must not fall back to the new-thread empty state.
                historyError = if (it.selected != null && it.items.isEmpty() && it.historyLoading)
                    "连接已断开，未能加载会话内容，重连后重试" else it.historyError,
                status = reason, challenge = null,
                modelsLoading = false, quotaLoading = false, modelSettings = it.modelSettings?.copy(confirmed = false))
        }
    }

    fun saveProfile(profile: ConnectionProfile, targetPass: String, jumpPass: String) {
        runCatching {
            profile.validate()
            disconnect()
            vault.put("profile", profile.json().toString())
            targetPassphrase = targetPass; jumpPassphrase = jumpPass
            selectionVersion++; timeline.clear()
            // A different environment owns no local records from the previous one.
            outgoingBySession.clear()
            mutable.update { it.copy(profile = profile, saved = true, sessions = emptyList(), selected = null,
                items = emptyList(), queue = emptyList(), projects = emptyList(), cursor = null, draft = "", uncertain = null, diff = "",
                models = emptyList(), modelsError = null, modelSettings = null, settingsNotice = null, quota = null, quotaError = null) }
            if (mutable.value.foreground) connect()
        }.onFailure(::fail)
    }

    fun importKey(uri: Uri, jump: Boolean) = action {
        val slot = if (jump) "jump-key" else "target-key"
        val resolver = getApplication<Application>().contentResolver
        val text = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buffer.size() <= 1_048_576) {
                    val count = stream.read(chunk, 0, minOf(chunk.size, 1_048_577 - buffer.size()))
                    if (count < 0) break
                    buffer.write(chunk, 0, count)
                }
                val bytes = buffer.toByteArray()
                require(bytes.size <= 1_048_576) { "私钥文件过大" }
                val value = bytes.toString(Charsets.UTF_8); bytes.fill(0); value
            } ?: error("无法读取私钥文件")
        }
        require(text.contains("PRIVATE KEY-----")) { "请选择 OpenSSH 或 PEM 格式的私钥" }
        vault.put(slot, text)
        vault.put("$slot-name", "已导入 · 加密保存")
        mutable.update { if (jump) it.copy(jumpKeyName = "已导入 · 加密保存") else it.copy(targetKeyName = "已导入 · 加密保存") }
    }

    fun trustHost() {
        val challenge = mutable.value.challenge ?: return
        if (challenge.changed) return
        vault.put("host:${challenge.host}", challenge.key)
        mutable.update { it.copy(challenge = null) }
        connect()
    }
    fun cancelTrust() { mutable.update { it.copy(challenge = null, status = "尚未信任主机") } }

    fun pair(raw: String) = action { pairInternal(raw) }
    fun pairImage(uri: Uri) = action {
        val raw = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取二维码图片" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("无法读取二维码图片")
            try { decodePairingQr(bitmap) } finally { bitmap.recycle() }
        }
        pairInternal(raw)
    }

    private suspend fun pairInternal(raw: String) {
        val ticket = PairingTicket.parse(raw)
        disconnect()
        mutable.update { it.copy(busy = true, status = "正在配对开发机…", error = null) }
        var committed = false
        try {
            val identity = withContext(Dispatchers.IO) {
                DeviceIdentity.read(vault) ?: DeviceIdentity.generate().also { vault.put(DeviceIdentity.SLOT, it.json()) }
            }
            val grant = PairingClient.redeem(ticket, identity.publicKey)
            mutable.update { it.copy(status = "正在验证开发机连接…") }
            val candidate = SshTransport()
            var checkRpc: RpcClient? = null
            try {
                candidate.connect(grant.profile, SecretReader { grant.pins[it] ?: vault.get(it) }, "", "")
                val c = RpcClient(candidate.input, candidate.output, {}, {}); checkRpc = c
                c.initialize()
            } finally {
                checkRpc?.close()
                withContext(NonCancellable + Dispatchers.IO) { candidate.close() }
            }
            grant.pins.forEach { (name, value) -> vault.put(name, value) }
            saveProfile(grant.profile, "", "")
            mutable.update { it.copy(tab = 0) }
            committed = true
        } finally {
            if (!committed) mutable.update { it.copy(status = "配对未完成，请重试或生成新的连接码") }
        }
    }

    fun connect() {
        if (connectionJob?.isActive == true) return
        if (mutable.value.connected) { refresh(); return }
        connectionJob = viewModelScope.launch {
            actions.withLock {
                val generation = ++epoch
                val ssh = SshTransport()
                transport = ssh
                mutable.update { it.copy(busy = true, error = null, status = "正在连接 SSH…", challenge = null) }
                try {
                    ssh.connect(mutable.value.profile, vault, targetPassphrase, jumpPassphrase)
                    ensureActive()
                    val connection = RpcClient(ssh.input, ssh.output, { event ->
                        withContext(Dispatchers.Main) { if (generation == epoch) handle(event) }
                    }, { error -> viewModelScope.launch {
                        if (generation == epoch) {
                            disconnect("连接中断 · 点击刷新重连")
                            mutable.update { it.copy(error = "${error.message}\n${ssh.diagnostics}".trim()) }
                        }
                    } })
                    rpc = connection
                    val initialized = connection.initialize()
                    ensureActive()
                    mutable.update { it.copy(connected = true, status = "已连接", version = initialized.string("userAgent")) }
                    refreshModels(); refreshQuota()
                    loadSessions(false)
                    loadProjects()
                    mutable.value.selected?.let { loadThreadGuarded(it.id) }
                    scheduleRefresh()
                } catch (e: HostIdentityException) {
                    mutable.update { it.copy(challenge = e.challenge, connected = false, status = "核对 SSH 主机指纹") }
                } catch (e: CancellationException) { throw e }
                catch (t: Throwable) { mutable.update { it.copy(connected = false, status = "连接失败") }; fail(t) }
                finally {
                    if (!mutable.value.connected || generation != epoch) withContext(NonCancellable + Dispatchers.IO) { ssh.close() }
                    if (generation == epoch) mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun refresh() {
        if (!mutable.value.connected) { connect(); return }
        refreshModels(); refreshQuota()
        action { loadSessions(false); mutable.value.selected?.let { loadThreadGuarded(it.id) }; loadProjects() }
    }

    fun refreshModels() {
        if (!mutable.value.connected || modelsJob?.isActive == true) return
        val generation = epoch; val account = accountVersion; val connection = client()
        modelsJob = viewModelScope.launch {
            mutable.update { it.copy(modelsLoading = true, modelsError = null) }
            try {
                val models = mutableListOf<ModelOption>(); val cursors = mutableSetOf<String>()
                var cursor: String? = null
                do {
                    val page = connection.call("model/list", obj("limit" to 100, "includeHidden" to false, "cursor" to cursor), timeout = 15_000)
                    models += page.optJSONArray("data").objects().filter { !it.optBoolean("hidden") }.map(ModelOption::parse).filter { it.id.isNotBlank() }
                    cursor = page.string("nextCursor").ifBlank { null }
                    check(cursor == null || cursors.add(cursor)) { "模型分页游标重复" }
                } while (cursor != null)
                if (generation == epoch && account == accountVersion) mutable.update { it.copy(models = models.distinctBy { m -> m.id }) }
            } catch (e: TimeoutCancellationException) {
                if (generation == epoch && account == accountVersion) mutable.update { it.copy(modelsError = "读取模型列表超时，请重试") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch && account == accountVersion) mutable.update { it.copy(modelsError = e.message ?: "模型列表暂不可用") } }
            finally { if (generation == epoch && account == accountVersion) mutable.update { it.copy(modelsLoading = false) } }
        }
    }

    fun refreshQuota(fromEvent: Boolean = false) {
        if (!mutable.value.connected) return
        if (quotaJob?.isActive == true) {
            if (!fromEvent && quotaDeferred) { quotaJob?.cancel(); quotaJob = null }
            else { if (fromEvent && !quotaDeferred) quotaRefreshPending = true; return }
        }
        val generation = epoch; val account = accountVersion; val connection = client()
        quotaDeferred = fromEvent
        quotaJob = viewModelScope.launch {
            // Notifications are sparse. Fetch an authoritative snapshot, coalescing rapid updates.
            if (fromEvent) delay((10_000L - (System.currentTimeMillis() - (mutable.value.quota?.updatedAt ?: 0))).coerceAtLeast(300))
            quotaDeferred = false
            mutable.update { it.copy(quotaLoading = true, quotaError = null) }
            try {
                val result = connection.call("account/rateLimits/read", obj("excludeResetCreditDetails" to true), timeout = 20_000)
                if (generation == epoch && account == accountVersion) mutable.update { it.copy(quota = AccountQuota.parse(result)) }
            } catch (e: TimeoutCancellationException) {
                if (generation == epoch && account == accountVersion) mutable.update { it.copy(quotaError = "读取额度超时，请重试") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch && account == accountVersion) mutable.update { it.copy(quotaError = e.message ?: "额度暂不可用") } }
            finally { if (generation == epoch && account == accountVersion) {
                mutable.update { it.copy(quotaLoading = false) }
                if (quotaRefreshPending) { quotaRefreshPending = false; quotaJob = null; refreshQuota(fromEvent = true) }
            } }
        }
    }

    private fun acceptSettings(id: String, settings: ModelSettings) {
        settingsRevisions[id] = (settingsRevisions[id] ?: 0) + 1
        mutable.update { state -> state.copy(
            modelSettings = if (state.selected?.id == id) settings else state.modelSettings,
            settingsNotice = if (state.selected?.id == id) null else state.settingsNotice,
            selected = state.selected?.let { if (it.id == id) it.copy(model = settings.model) else it },
            sessions = state.sessions.map { if (it.id == id) it.copy(model = settings.model) else it }) }
    }

    private suspend fun resumeSettings(id: String, connection: RpcClient = client(), yoloMode: Boolean = false): JSONObject {
        val generation = epoch; val selection = selectionVersion
        val params = (if (yoloMode) yolo() else obj()).put("threadId", id).put("excludeTurns", true)
        return connection.call("thread/resume", params, onResult = { result ->
            withContext(Dispatchers.Main) {
                if (generation == epoch && selection == selectionVersion && mutable.value.selected?.id == id)
                    acceptSettings(id, ModelSettings.parse(result))
            }
        })
    }

    fun prepareModelSettings(id: String, force: Boolean = false) {
        refreshModels()
        if (mutable.value.connected && (force || mutable.value.modelSettings?.confirmed != true)) action {
            if (mutable.value.selected?.id == id) resumeSettings(id)
        }
    }

    fun updateModelSettings(id: String, choice: ModelSettings) {
        val generation = epoch; val selection = selectionVersion
        action {
            check(generation == epoch && selection == selectionVersion && mutable.value.selected?.id == id) { "会话已切换，请重新选择" }
            val params = choice.request(id, mutable.value.models)
            if (mutable.value.modelSettings?.confirmed != true) resumeSettings(id)
            check(generation == epoch && selection == selectionVersion && mutable.value.selected?.id == id) { "会话已切换，未修改设置" }
            if (mutable.value.modelSettings == choice) return@action
            val revision = settingsRevisions[id] ?: 0
            mutable.update { it.copy(settingsNotice = null) }
            try {
                client().call("thread/settings/update", params)
                withTimeout(5000) { state.first { generation != epoch || selection != selectionVersion || (settingsRevisions[id] ?: 0) > revision } }
                if (generation == epoch && selection == selectionVersion) mutable.update { it.copy(settingsNotice = "已更新会话设置，后续轮次及排队任务生效") }
            } catch (e: RpcException) { throw e }
            catch (e: Exception) {
                if (generation == epoch && selection == selectionVersion) mutable.update {
                    it.copy(modelSettings = it.modelSettings?.copy(confirmed = false), settingsNotice = "设置结果待核对，请刷新；不会自动重试", error = "设置结果待核对，请刷新确认")
                }
                throw e
            }
        }
    }
    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(20_000)
                if (mutable.value.connected && mutable.value.foreground && !mutable.value.busy) {
                    actions.withLock {
                        runCatching { loadSessions(false) }.onFailure { fail(it) }
                    }
                }
            }
        }
    }

    private suspend fun loadSessions(more: Boolean) {
        val connection = client()
        val page = connection.call("thread/list", obj("limit" to 30, "sortKey" to "updated_at", "sortDirection" to "desc",
            "cursor" to if (more) mutable.value.cursor else null,
            "sourceKinds" to JSONArray(listOf("cli", "vscode", "appServer", "exec", "unknown"))))
        val base = page.optJSONArray("data").objects().map(SessionInfo::parse)
        val semaphore = Semaphore(4)
        val sessions = coroutineScope { base.map { session -> async {
            semaphore.withPermit {
                try {
                    val last = connection.call("thread/turns/list", obj("threadId" to session.id, "limit" to 1,
                        "sortDirection" to "desc", "itemsView" to "notLoaded")).optJSONArray("data")?.optJSONObject(0)
                    session.copy(lastTurnStatus = last?.string("status").orEmpty(),
                        activeTurnId = if (session.runtime == "active" && last?.string("status") == "inProgress") last.string("id") else "")
                } catch (e: RpcException) { session }
            }
        } }.awaitAll() }
        mutable.update { state -> state.copy(sessions = (if (more) state.sessions + sessions else sessions).distinctBy { it.id },
            cursor = page.string("nextCursor").ifBlank { null }, lastSync = System.currentTimeMillis()) }
    }
    fun moreSessions() = action { if (mutable.value.cursor != null) loadSessions(true) }

    private suspend fun loadProjects() {
        val root = mutable.value.profile.root.trimEnd('/')
        val result = client().call("fs/readDirectory", obj("path" to root))
        mutable.update { it.copy(projects = result.optJSONArray("entries").objects().filter { j -> j.optBoolean("isDirectory") && !j.string("fileName").startsWith('.') }
            .map { j -> "$root/${j.string("fileName")}" }.sorted()) }
    }

    fun openThread(session: SessionInfo) {
        selectionVersion++
        timeline.clear()
        val outKey = OutgoingKey(mutable.value.profile.identity, session.id)
        // Re-entering a thread with a request still in flight must show 发送中 again —
        // otherwise the settled-protection banner would appear for an unsettled send.
        val inFlight = outgoingBySession[outKey]?.any { it.state == OutgoingState.SENDING } == true
        // Offline there is no request in flight, so never fake a loading spinner.
        mutable.update { it.copy(selected = session, tab = 0, items = emptyList(), historyCursor = null, queue = emptyList(), diff = "", modelSettings = null, settingsNotice = null,
            historyLoading = mutable.value.connected && !inFlight,
            historyError = if (mutable.value.connected) null else "离线缓存 · 重连后加载会话内容",
            sending = inFlight,
            draft = vault.get(localKey("draft", session.id)).orEmpty(),
            uncertain = vault.get(localKey("pending", session.id))?.let(::JSONObject),
            outgoing = (outgoingBySession[outKey] ?: emptyList()).toList()) }
        // A persisted pending receipt restores its 待确认 bubble after a restart/return —
        // but never over an existing record: an in-flight SENDING, or a settled
        // ACCEPTED/FAILED receipt, must not be downgraded by replaying the old receipt.
        mutable.value.uncertain?.let { receipt ->
            val clientId = receipt.string("clientId")
            if (clientId.isNotBlank()) {
                val key = OutgoingKey(mutable.value.profile.identity, session.id)
                // Restore only when no record with this id exists (fresh map after restart);
                // an in-flight SENDING or a settled receipt must never be downgraded.
                if (outgoingBySession[key]?.any { it.clientId == clientId } != true)
                    addOutgoing(key, OutgoingMessage(session.id, clientId,
                        receipt.string("text"), OutgoingState.UNCERTAIN))
            }
        }
        if (mutable.value.connected) action { loadThreadGuarded(session.id) }
    }
    fun retryHistory() = action {
        val id = mutable.value.selected?.id ?: return@action
        loadThreadGuarded(id)
    }
    // Only the first history of an empty session reports inline loading; refreshing an
    // already-loaded session keeps its messages visible instead of flashing a spinner.
    private suspend fun loadThreadGuarded(id: String) {
        val scope = RequestScope(epoch, selectionVersion, id)
        if (mutable.value.selected?.id == id && mutable.value.items.isEmpty())
            mutable.update { it.copy(historyLoading = true, historyError = null) }
        try {
            loadThread(id)
        } catch (e: CancellationException) { throw e } catch (t: Throwable) {
            if (scope.appliesTo(epoch, selectionVersion, mutable.value.selected?.id) && mutable.value.items.isEmpty())
                mutable.update { it.copy(historyLoading = false, historyError = t.message ?: "加载会话内容失败") }
            throw t
        }
    }
    fun back() { selectionVersion++; mutable.update { it.copy(selected = null, items = emptyList(), queue = emptyList(), draft = "", uncertain = null, diff = "", historyLoading = false, historyError = null, sending = false, outgoing = emptyList()) }; timeline.clear() }

    private suspend fun loadThread(id: String) {
        val version = selectionVersion
        val generation = epoch
        val baseline = timeline.toMap()
        val statusRevision = statusRevisions[id] ?: 0L
        val turnRevision = turnRevisions[id] ?: 0L
        val connection = client()
        val metadata = connection.call("thread/read", obj("threadId" to id, "includeTurns" to false)).getJSONObject("thread")
        val session = SessionInfo.parse(metadata)
        // Subscribe to a thread already owned by this daemon. Merely viewing a disk-only thread never starts another engine.
        if (session.runtime != "notLoaded") resumeSettings(id, connection)
        else if (generation == epoch && version == selectionVersion && mutable.value.selected?.id == id)
            mutable.update { it.copy(modelSettings = ModelSettings.metadata(metadata)) }
        val turns = connection.call("thread/turns/list", obj("threadId" to id, "limit" to 1, "sortDirection" to "desc", "itemsView" to "notLoaded"))
            .optJSONArray("data").objects()
        val latest = turns.firstOrNull()
        connection.call("thread/items/list", obj("threadId" to id, "limit" to 80, "sortDirection" to "desc"), onResult = { snapshot ->
            withContext(Dispatchers.Main) {
                if (generation == epoch && mutable.value.selected?.id == id && version == selectionVersion) {
                    val list = snapshot.optJSONArray("data").objects().mapNotNull { it.optJSONObject("item") }.map(TimelineItem::parse).reversed()
                    applyHistorySnapshot(timeline, list, baseline)
                    mutable.update { it.copy(items = timeline.values.toList(), historyCursor = snapshot.string("nextCursor").ifBlank { null }) }
                }
            }
        })
        if (generation != epoch || mutable.value.selected?.id != id || version != selectionVersion) return
        var updated = session.copy(model = mutable.value.modelSettings?.model ?: session.model, lastTurnStatus = latest?.string("status").orEmpty(),
            activeTurnId = if (session.runtime == "active" && latest?.string("status") == "inProgress") latest.string("id") else "")
        if ((statusRevisions[id] ?: 0L) != statusRevision) {
            // A completion/next-turn event may arrive while history is being paged in.
            mutable.value.selected?.let { liveStatus ->
                updated = updated.copy(runtime = liveStatus.runtime)
                if ((turnRevisions[id] ?: 0L) != turnRevision) updated = updated.copy(
                    lastTurnStatus = liveStatus.lastTurnStatus, activeTurnId = liveStatus.activeTurnId)
                else if (liveStatus.runtime != "active") updated = updated.copy(activeTurnId = "")
            }
        }
        mutable.update { it.copy(selected = updated, items = timeline.values.toList(),
            historyLoading = false, historyError = null,
            sessions = it.sessions.map { old -> if (old.id == id) updated else old }, lastSync = System.currentTimeMillis()) }
        if (session.runtime != "notLoaded") loadQueue(id)
        reconcilePending(id)
        reconcileOutgoing(id)
    }

    fun moreHistory() = action {
        val id = mutable.value.selected?.id ?: return@action
        val cursor = mutable.value.historyCursor ?: return@action
        val scope = RequestScope(epoch, selectionVersion, id)
        mutable.update { it.copy(historyLoading = true) }
        try {
            val result = client().call("thread/items/list", obj("threadId" to id, "cursor" to cursor, "limit" to 80, "sortDirection" to "desc"))
            // A late success from a superseded selection must not splice into the new session.
            if (!scope.appliesTo(epoch, selectionVersion, mutable.value.selected?.id)) return@action
            val old = timeline.toMap(); timeline.clear()
            result.optJSONArray("data").objects().mapNotNull { it.optJSONObject("item") }.map(TimelineItem::parse).reversed().forEach { timeline[it.id] = it }
            timeline.putAll(old)
            mutable.update { it.copy(items = timeline.values.toList(), historyCursor = result.string("nextCursor").ifBlank { null }) }
            reconcilePending(id)
            reconcileOutgoing(id)
        } finally {
            // Never clear the loading state of a session opened while this page was in flight.
            if (scope.appliesTo(epoch, selectionVersion, mutable.value.selected?.id))
                mutable.update { it.copy(historyLoading = false) }
        }
    }

    private suspend fun loadQueue(id: String) {
        val result = client().call("thread/queue/list", obj("threadId" to id, "limit" to 100))
        if (mutable.value.selected?.id == id) mutable.update { it.copy(queue = result.optJSONArray("data").objects().map(QueuedMessage::parse)) }
        reconcileOutgoing(id)
    }

    fun createThread(cwd: String, name: String) = action {
        require(cwd.startsWith('/')) { "请选择项目绝对路径" }
        val params = yolo().put("cwd", cwd)
        val result = client().call("thread/start", params)
        assertYolo(result)
        val session = SessionInfo.parse(result.getJSONObject("thread"))
        if (name.isNotBlank()) client().call("thread/name/set", obj("threadId" to session.id, "name" to name.trim().take(120)))
        selectionVersion++; timeline.clear()
        mutable.update { it.copy(selected = session.copy(title = name.ifBlank { "新会话" }), items = emptyList(), queue = emptyList(),
            historyCursor = null, draft = "", uncertain = null, diff = "", tab = 0, modelSettings = ModelSettings.parse(result), settingsNotice = null) }
        loadSessions(false)
    }
    private fun assertYolo(result: JSONObject) {
        check(result.string("approvalPolicy") == "never" && result.optJSONObject("sandbox")?.string("type") == "dangerFullAccess") {
            "服务器未启用 YOLO，未发送任务。请检查服务器策略。"
        }
    }

    fun draft(text: String) {
        val id = mutable.value.selected?.id ?: return
        mutable.update { it.copy(draft = text) }
        runCatching { vault.put(localKey("draft", id), text) }.onFailure(::fail)
    }

    fun send(steer: Boolean) = action {
        val selected = mutable.value.selected ?: return@action
        val selection = selectionVersion
        val generation = epoch
        val env = mutable.value.profile.identity
        val key = OutgoingKey(env, selected.id)
        val pendingKey = localKey("pending", selected.id)
        val draftKey = localKey("draft", selected.id)
        val text = mutable.value.draft.trim()
        require(text.isNotBlank()) { "请先输入消息" }
        check(mutable.value.uncertain == null) { "先核对上一条消息的发送状态" }
        // A previous send may still be in flight after a switch away and back; its persisted
        // receipt must not be overwritten by a second dispatch.
        check(mutable.value.outgoing.none { it.state == OutgoingState.SENDING }) { "上一条消息仍在发送中，请稍候" }
        val clientId = UUID.randomUUID().toString()
        val receipt = obj("clientId" to clientId, "text" to text)
        // The bubble appears before any RPC: resumeSettings, the dispatch, and the first
        // streamed output all happen after the user already sees the message with 发送中.
        addOutgoing(key, OutgoingMessage(selected.id, clientId, text, OutgoingState.SENDING))
        mutable.update { it.copy(sending = true) }
        var dispatched = false
        var ackAccepted = false
        try {
            val result = resumeSettings(selected.id, yoloMode = true)
            assertYolo(result)
            check(generation == epoch && selection == selectionVersion) { "会话已切换，消息尚未发送" }
            val resumed = SessionInfo.parse(result.getJSONObject("thread"))
            if (steer) check(resumed.runtime == "active" && selected.activeTurnId.isNotBlank()) { "当前没有可介入的执行，请刷新后重试" }
            val params = obj("threadId" to selected.id, "clientUserMessageId" to clientId, "input" to textInput(text))
            // Persist before the write only. On timeout/disconnect after this point, do not
            // automatically replay a possibly accepted write.
            vault.put(pendingKey, receipt.toString())
            // Display only: the protection prompt stays owned by uncertain; sending merely hides it in flight.
            mutable.update { it.copy(uncertain = receipt) }
            dispatched = true
            when {
                steer -> {
                    check(resumed.runtime == "active" && selected.activeTurnId.isNotBlank()) { "当前没有可介入的执行，请刷新后重试" }
                    client().call("turn/steer", params.put("expectedTurnId", selected.activeTurnId))
                }
                resumed.runtime == "active" -> client().call("thread/queue/add", params)
                else -> client().call("turn/start", params.put("approvalPolicy", "never").put("sandboxPolicy", obj("type" to "dangerFullAccess")))
            }
            ackAccepted = true
            // The receipt states what the server did with the submission, not that the model
            // has read it; the bubble stays until history or the queue reconciles it by id.
            val (settled, ack) = when {
                steer -> OutgoingState.ACCEPTED to "已插入"
                resumed.runtime == "active" -> OutgoingState.QUEUED to "已排队"
                else -> OutgoingState.ACCEPTED to "已接收"
            }
            updateOutgoing(key, clientId) { it.copy(state = settled, receipt = ack) }
            vault.put(pendingKey, null)
            val clearDraft = vault.get(draftKey)?.trim() == text
            if (clearDraft) vault.put(draftKey, "")
            if (generation == epoch && mutable.value.selected?.id == selected.id)
                mutable.update { it.copy(draft = if (clearDraft) "" else it.draft, uncertain = null) }
        } catch (e: RpcException) {
            // An explicit server rejection is authoritative: 发送失败, draft kept. Before the
            // write this also means nothing was sent; afterwards the server answered for the write.
            vault.put(pendingKey, null)
            updateOutgoing(key, clientId) { it.copy(state = OutgoingState.FAILED) }
            if (generation == epoch && mutable.value.selected?.id == selected.id) mutable.update { it.copy(uncertain = null) }
            throw e
        } catch (e: CancellationException) {
            // RpcClient.call surfaces timeouts as cancellation: a dispatched write may still
            // land → 结果待确认 with pending kept. Before dispatch, nothing was sent → 发送失败.
            if (dispatched) markOutgoingUncertain(key, clientId)
            else updateOutgoing(key, clientId) { it.copy(state = OutgoingState.FAILED) }
            throw e
        } catch (e: Exception) {
            if (dispatched) {
                // The channel settled without a definitive answer: 结果待确认; the persisted
                // pending receipt drives the same protection. Never auto-replayed.
                markOutgoingUncertain(key, clientId)
            } else {
                vault.put(pendingKey, null)
                updateOutgoing(key, clientId) { it.copy(state = OutgoingState.FAILED) }
                if (generation == epoch && mutable.value.selected?.id == selected.id) mutable.update { it.copy(uncertain = null) }
            }
            throw e
        } finally {
            // Clear the in-flight marker for exactly this environment+session on the current
            // connection. Re-entering the same session (selectionVersion moved on) must still
            // see the settled state; a different session's flag is untouched.
            if (epoch == generation && mutable.value.profile.identity == env &&
                mutable.value.selected?.id == selected.id)
                mutable.update { it.copy(sending = false) }
        }
        // Refresh boundary: after the ack was accepted, a failing history read must never
        // turn the already-accepted message into 发送失败/待确认 — it surfaces as historyError.
        if (ackAccepted && generation == epoch && mutable.value.selected?.id == selected.id)
            loadThreadGuarded(selected.id)
    }

    private fun publishOutgoing(key: OutgoingKey) {
        if (mutable.value.profile.identity == key.env && mutable.value.selected?.id == key.sessionId)
            mutable.update { it.copy(outgoing = (outgoingBySession[key] ?: emptyList()).toList()) }
    }

    private fun addOutgoing(key: OutgoingKey, message: OutgoingMessage) {
        val list = outgoingBySession.getOrPut(key) { mutableListOf() }
        list.removeAll { it.clientId == message.clientId }
        list.add(message)
        // Reconciled records are dropped on arrival. Never silently drop an unconfirmed
        // (SENDING/UNCERTAIN) or awaiting-reconciliation (ACCEPTED/QUEUED) record — the
        // bound only trims settled failures.
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

    private fun removeOutgoing(key: OutgoingKey, clientId: String) {
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
    private fun reconcileOutgoing(id: String) {
        if (mutable.value.selected?.id != id) return
        val key = OutgoingKey(mutable.value.profile.identity, id)
        val list = outgoingBySession[key] ?: return
        if (list.isEmpty()) return
        val itemIds = timeline.values.filter { it.kind == "userMessage" }
            .map { it.clientId }.filter { it.isNotBlank() }.toSet()
        val queueIds = mutable.value.queue.map { it.clientId }.filter { it.isNotBlank() }.toSet()
        val remaining = reconcileOutgoingList(list, itemIds, queueIds)
        if (remaining.size != list.size) { outgoingBySession[key] = remaining.toMutableList(); publishOutgoing(key) }
    }

    private fun reconcilePending(id: String) {
        if (mutable.value.selected?.id != id) return
        val pending = mutable.value.uncertain ?: return
        val clientId = pending.string("clientId")
        // Only a real userMessage (or the matching queue entry) proves the sent message
        // landed; a tool/assistant row sharing the id must never lift the protection,
        // and equal text alone never counts.
        if (timeline.values.any { it.kind == "userMessage" && it.clientId == clientId } ||
            mutable.value.queue.any { it.clientId == clientId }) {
            vault.put(localKey("pending", id), null)
            if (mutable.value.draft.trim() == pending.string("text")) { vault.put(localKey("draft", id), ""); mutable.update { it.copy(draft = "") } }
            mutable.update { it.copy(uncertain = null) }
        }
    }
    fun clearUncertain() {
        val id = mutable.value.selected?.id ?: return
        val clientId = mutable.value.uncertain?.string("clientId")
        vault.put(localKey("pending", id), null)
        mutable.update { it.copy(uncertain = null) }
        clientId?.let { removeOutgoing(OutgoingKey(mutable.value.profile.identity, id), it) }
    }
    fun interrupt() = action {
        val session = mutable.value.selected ?: return@action
        require(session.activeTurnId.isNotBlank()) { "执行轮次尚未同步，请刷新后重试" }
        client().call("turn/interrupt", obj("threadId" to session.id, "turnId" to session.activeTurnId))
    }
    fun deleteQueued(message: QueuedMessage) = action {
        val id = mutable.value.selected?.id ?: return@action
        client().call("thread/queue/delete", obj("threadId" to id, "queuedSubmissionId" to message.id)); loadQueue(id)
    }
    fun rename(name: String) = action {
        val id = mutable.value.selected?.id ?: return@action
        require(name.isNotBlank())
        client().call("thread/name/set", obj("threadId" to id, "name" to name.trim()))
        loadThreadGuarded(id); loadSessions(false)
    }

    private fun handle(message: JSONObject) {
        val method = message.string("method")
        val p = message.optJSONObject("params") ?: obj()
        val threadId = p.string("threadId")
        if (message.has("id")) {
            // This client has no approval workflow. Do not consume another client's pending callback.
            mutable.update { it.copy(error = "服务器请求交互（$method）。当前客户端仅支持 YOLO 自动执行；请在原终端处理该请求。") }
            return
        }
        if (method == "thread/settings/updated") p.optJSONObject("threadSettings")?.let { acceptSettings(threadId, ModelSettings.parse(it)) }
        if (method == "account/rateLimits/updated") refreshQuota(fromEvent = true)
        if (method == "account/updated") {
            accountVersion++; modelsJob?.cancel(); quotaJob?.cancel(); modelsJob = null; quotaJob = null; quotaRefreshPending = false
            mutable.update { it.copy(models = emptyList(), quota = null, modelsError = null, quotaError = null, modelsLoading = false, quotaLoading = false) }
            refreshModels(); refreshQuota()
        }
        if (method == "thread/status/changed") {
            statusRevisions[threadId] = (statusRevisions[threadId] ?: 0L) + 1L
            val runtime = p.optJSONObject("status")?.string("type") ?: return
            mutable.update { it.copy(sessions = it.sessions.map { s -> if (s.id == threadId) s.copy(runtime = runtime) else s },
                selected = it.selected?.let { s -> if (s.id == threadId) s.copy(runtime = runtime) else s }) }
        }
        if (method == "turn/started" || method == "turn/completed") {
            statusRevisions[threadId] = (statusRevisions[threadId] ?: 0L) + 1L
            turnRevisions[threadId] = (turnRevisions[threadId] ?: 0L) + 1L
            val turn = p.optJSONObject("turn") ?: return
            val status = turn.string("status")
            fun update(s: SessionInfo) = if (s.id != threadId) s else s.copy(runtime = if (status == "inProgress") "active" else "idle",
                lastTurnStatus = status, activeTurnId = if (status == "inProgress") turn.string("id") else "")
            mutable.update { it.copy(sessions = it.sessions.map(::update), selected = it.selected?.let(::update)) }
            if (threadId == mutable.value.selected?.id && turn.optJSONObject("error") != null) {
                mutable.update { it.copy(error = turn.optJSONObject("error")?.string("message")) }
            }
        }
        if (threadId != mutable.value.selected?.id) return
        when (method) {
            "item/started", "item/completed" -> p.optJSONObject("item")?.let { val item = TimelineItem.parse(it); timeline[item.id] = item; paint() }
            "item/agentMessage/delta", "item/commandExecution/outputDelta", "item/plan/delta" -> {
                val id = p.string("itemId")
                val kind = if (method.contains("commandExecution")) "commandExecution" else if (method.contains("plan")) "plan" else "agentMessage"
                val old = timeline[id] ?: TimelineItem(id, kind, "")
                timeline[id] = old.copy(text = (old.text + p.string("delta")).takeLast(120_000)); paint()
            }
            "turn/diff/updated" -> mutable.update { it.copy(diff = p.string("diff")) }
            "thread/queue/changed" -> action { loadQueue(threadId); reconcilePending(threadId); reconcileOutgoing(threadId) }
        }
    }
    private fun paint() {
        if (paintJob?.isActive == true) return
        paintJob = viewModelScope.launch {
            delay(70)
            mutable.update { it.copy(items = timeline.values.toList()) }
            mutable.value.selected?.id?.let { id ->
                // Both reconciliations run only after the new items are published: dropping
                // the local bubble (or the pending protection) earlier would leave a window
                // where neither the bubble nor the formal row shows.
                reconcileOutgoing(id)
                reconcilePending(id)
            }
        }
    }
    override fun onCleared() { rpc?.close(); transport?.close(); super.onCleared() }
}
