package com.gmch.codexremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xff226f50)
private val Ink = Color(0xff182720)
private val Paper = Color(0xfffbfcf8)
private val Muted = Color(0xff58685e)
private val Line = Color(0xffd8e2da)

class MainActivity : ComponentActivity() {
    private val model: RemoteViewModel by viewModels()
    private val zcode: ZcodeViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketHelmTheme {
                EngineApp(model, zcode)
            }
        }
    }
    override fun onStart() { super.onStart(); model.keepConnection(); zcode.keepConnection(); if (model.state.value.engine == "zcode") zcode.enter() else model.foreground() }
    override fun onStop() { model.background(); zcode.background(); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteApp(vm: RemoteViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var newThread by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Pair<String, String>?>(null) }
    var rename by remember { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    val chat = state.selected != null && state.tab == 0
    BackHandler(chat) { vm.back() }
    pages.SaveableStateProvider("${state.profile.resumeIdentity}:${if (chat) "chat:${state.selected!!.id}" else "tab:${if (state.tab == 2) 2 else 0}"}") {
        when {
            chat -> key(state.selected!!.id) {
                WorkspaceScaffold(title = state.selected!!.title, subtitle = state.selected!!.project, chat = true,
                    busy = state.busy, back = vm::back, refresh = vm::refresh,
                    actions = { IconButton({ rename = true }, enabled = state.connected && !state.busy) { Icon(Icons.Outlined.Edit, "重命名") } }) {
                    WorkspaceError(state.error, vm::dismissError) { title, text -> details = title to text }
                    ChatScreen(state, vm) { title, text -> details = title to text }
                }
            }
            state.tab == 2 -> WorkspaceScaffold("连接", tab = state.tab, busy = state.busy, refresh = vm::refresh, selectTab = vm::tab,
                engine = "codex" to { vm.engine(it) }) {
                WorkspaceError(state.error, vm::dismissError) { title, text -> details = title to text }
                ConnectionScreen(state, vm)
            }
            else -> WorkspaceListPage(engine = "codex", onEngine = { vm.engine(it) },
                sessions = state.sessions.map { it.workspace(state.sessionsCached || !state.connected) },
                connected = state.connected, busy = state.busy,
                canCreate = state.connected && !state.busy, canOpen = state.connected && !state.busy,
                open = { id -> state.sessions.find { it.id == id }?.let(vm::openThread) }, create = { newThread = true },
                reconnect = vm::connect, refresh = vm::refresh, addProject = null,
                error = state.error, dismissError = vm::dismissError, detail = { title, text -> details = title to text },
                selectTab = vm::tab, more = state.cursor?.takeIf { it.isNotBlank() }?.let { { vm.moreSessions(); Unit } })
        }
    }
    if (newThread) NewThreadDialog(state, onClose = { newThread = false }) { cwd, title -> newThread = false; vm.createThread(cwd, title) }
    if (rename) {
        var value by remember { mutableStateOf(state.selected?.title.orEmpty()) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("重命名会话") }, text = {
            OutlinedTextField(value, { value = it }, label = { Text("名称") }, singleLine = true)
        }, confirmButton = { TextButton({ vm.rename(value); rename = false }, enabled = value.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton({ rename = false }) { Text("取消") } })
    }
    details?.let { (title, text) ->
        ModalBottomSheet(onDismissRequest = { details = null }, containerColor = Paper) {
            Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                SelectionContainer { Text(text.ifBlank { "暂无内容" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
internal fun HostTrustDialog(state: UiState, vm: RemoteViewModel) {
    state.challenge?.let { challenge ->
        AlertDialog(onDismissRequest = vm::cancelTrust, title = { Text(if (challenge.changed) "主机身份已改变" else "首次连接主机") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(challenge.host, fontSize = 13.sp)
                Text(challenge.algorithm, color = Muted)
                SelectionContainer { Text(challenge.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text(if (challenge.changed) "当前指纹与已保存记录不符，连接已停止。请从可信渠道核对服务器身份。" else "核对服务器指纹后保存。之后每次连接会自动验证该身份。", fontSize = 13.sp)
            } }, confirmButton = { if (!challenge.changed) TextButton(onClick = vm::trustHost) { Text("指纹一致，保存并连接") } },
            dismissButton = { TextButton(onClick = vm::cancelTrust) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(state: UiState, vm: RemoteViewModel, detail: (String, String) -> Unit) {
    val session = state.selected!!
    var steer by rememberSaveable(session.id) { mutableStateOf(false) }
    var confirmUncertain by remember { mutableStateOf(false) }
    // Only this thread's approvals render here; other threads keep their cards in state.
    val approvals = state.approvals.filter { it.threadId == session.id }
    var approvalSheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ChatRunHeader(codexAgentRun(state.connected, session.runtime, approvals.size), session.status, state.historyCached, state.connected,
            session.runtime == "active", state.connected && !state.busy && session.activeTurnId.isNotBlank(), vm::interrupt)
        if (approvals.isNotEmpty()) InteractionBanner(approvalLabel(approvals), approvals.size) { approvalSheet = true }
        if (pendingProtectionVisible(state.uncertain != null, state.sending)) Surface(color = Color(0xfffaf0dc)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("上一条消息的接收结果待核对，草稿已保留。刷新会自动核对最近历史和队列。", fontSize = 12.sp)
                Row {
                    TextButton(vm::refresh, enabled = state.connected && !state.busy) { Text("刷新核对") }
                    TextButton({ confirmUncertain = true }) { Text("我已手动核对") }
                }
            }
        }
        if (confirmUncertain) AlertDialog(onDismissRequest = { confirmUncertain = false }, title = { Text("解除待核对状态") },
            text = { Text("确认已检查会话历史和队列。解除后仍保留草稿，由你决定是否再次发送；不会自动重发。") },
            confirmButton = { TextButton({ vm.clearUncertain(); confirmUncertain = false }) { Text("已核对，解除") } },
            dismissButton = { TextButton({ confirmUncertain = false }) { Text("返回") } })
        ChatTimeline(session.id, "Codex", state.items, state.outgoing, state.queue, state.historyCursor != null,
            state.historyLoading, state.historyError, state.connected, state.busy, Modifier.weight(1f),
            vm::moreHistory, vm::retryHistory, detail, state.diff, vm::deleteQueued, dismissFailed = vm::dismissFailed)
        ExecutionControls(state, vm)
        val context = androidx.compose.ui.platform.LocalContext.current
        val processor = remember(context) { ImageProcessor(context) }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                val (name, mime) = processor.sourceInfo(uri)
                vm.addAttachment(uri, name, mime)
            }
        }
        ChatComposer(state.draft, vm::draft, state.sending,
            state.connected && !state.busy && !state.sending && !state.attachmentsBusy && (state.draft.isNotBlank() || state.attachments.isNotEmpty()) && state.uncertain == null,
            steer, { steer = it }, { vm.send(steer && session.runtime == "active") },
            attachments = state.attachments, attachmentsBusy = state.attachmentsBusy,
            canAttach = !state.attachmentsBusy && state.attachments.size < ImagePolicy.MESSAGE_MAX && !state.sending,
            attach = { pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            removeAttachment = vm::removeAttachment)
    }
    if (approvalSheet && approvals.isNotEmpty()) {
        CodexApprovalSheet(approvals, state.items, state.connected && !state.busy, vm::decide) { approvalSheet = false }
    }
}

private fun approvalLabel(approvals: List<PendingApproval>): String {
    val titles = approvals.map { approvalTitle(it.kind) }.distinct()
    return titles.singleOrNull() ?: "审批"
}

private fun approvalTitle(kind: String) = when (kind) {
    "writeStdin" -> "向运行中进程输入"; "fileChange" -> "文件改动审批"; else -> "命令审批"
}

private val decisionLabels = linkedMapOf("accept" to "允许", "acceptForSession" to "本会话允许", "decline" to "拒绝", "cancel" to "取消本次执行")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CodexApprovalSheet(
    approvals: List<PendingApproval>, items: List<TimelineItem>, enabled: Boolean,
    decide: (PendingApproval, String) -> Unit, onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            approvals.forEach { approval ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(approvalTitle(approval.kind), style = MaterialTheme.typography.titleLarge)
                    if (approval.cwd.isNotBlank()) Text("目录：${approval.cwd}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (approval.kind == "fileChange" && approval.grantRoot.isNotBlank())
                        Text("授权目录：${approval.grantRoot}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    // Compact by default, expandable in full: never authorize against a clipped command.
                    ApprovalBody(approval, items)
                    when {
                        approval.sent -> Text("已发送，等待服务器确认", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        approval.decisions.isEmpty() -> Text(
                            if (approval.hasObjectDecisions) "该审批包含当前客户端不支持的决策类型（如策略修改），请在原终端处理。"
                            else "该审批没有当前客户端可用的决策选项，请在原终端处理。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            approval.decisions.forEach { decision ->
                                TextButton({ decide(approval, decision) }, enabled = enabled) {
                                    Text(decisionLabels[decision] ?: decision, color = if (decision == "decline") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Monospace body, collapsed to a few lines by default with an explicit expand toggle.
 *  File changes append the timeline item's own text (same thread only, by itemId). */
@Composable
private fun ApprovalBody(approval: PendingApproval, items: List<TimelineItem>) {
    val itemText = if (approval.kind == "fileChange") items.firstOrNull { it.id == approval.itemId }?.text.orEmpty() else ""
    val body = listOf(approval.body, itemText).filter { it.isNotBlank() }.joinToString("\n\n")
    if (body.isBlank()) return
    var expanded by remember(approval.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .clickable { expanded = !expanded }.padding(10.dp)) {
        SelectionContainer {
            Text(body, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
        }
        Text(if (expanded) "收起" else "展开完整内容", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserMessageBar(
    messages: List<ChatRow>, hasMore: Boolean, loading: Boolean, canLoad: Boolean, compact: Boolean,
    onLoadMore: () -> Unit, onJump: (String) -> Unit
) {
    var showMessages by remember { mutableStateOf(false) }
    val latest = messages.lastOrNull()
    Surface(color = Color(0xffeaf0e4), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable {
                if (latest != null) onJump(latest.id) else showMessages = true
            }.padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp)) {
                if (!compact) Text("你的消息 · 点击定位", fontSize = 10.sp, color = Green)
                Text(latest?.text?.take(300)?.ifBlank { "查看消息" } ?: "加载更早的消息", fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ showMessages = true }, Modifier.size(if (compact) 36.dp else 48.dp)) { Icon(Icons.Outlined.ExpandMore, "选择要跳转的消息") }
        }
    }
    if (showMessages) ModalBottomSheet(onDismissRequest = { showMessages = false }) {
        Text("你的消息", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Text("已加载 ${messages.size} 条 · 点击跳转", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(messages.asReversed(), key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth().clickable { showMessages = false; onJump(message.id) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(message.text.take(1200).ifBlank { "查看消息" }, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                }
                HorizontalDivider(color = Line)
            }
            if (hasMore) item {
                TextButton(onLoadMore, Modifier.fillMaxWidth(), enabled = canLoad && !loading) {
                    Text(if (loading) "加载中…" else "加载更早的消息")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivitySummary(items: List<TimelineItem>, detail: (String, String) -> Unit) {
    val failed = activityHasFailure(items)
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Icon(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.MoreHoriz,
            null, Modifier.size(18.dp), tint = if (failed) MaterialTheme.colorScheme.error else Muted)
        Text(activityLabel(items), Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 12.sp,
            color = if (failed) MaterialTheme.colorScheme.error else Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        Icon(Icons.Outlined.ChevronRight, "展开工作过程", Modifier.size(18.dp), tint = Muted)
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }, containerColor = Paper,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("工作过程", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                TimelineCard(item) { title, text -> expanded = false; detail(title, text) }
            }
        }
    }
}

@Composable
internal fun TimelineCard(item: TimelineItem, agentName: String = "Codex", detail: (String, String) -> Unit) {
    if (item.kind == "reasoning" && item.text.isBlank()) return
    val user = item.kind == "userMessage"
    val message = user || item.kind == "agentMessage" || item.kind == "plan"
    val title = when (item.kind) {
        "userMessage" -> "你"; "agentMessage" -> agentName; "plan" -> "计划"; "reasoning" -> "思考摘要"
        "commandExecution" -> "执行命令"; "fileChange" -> "文件修改"
        "mcpToolCall", "dynamicToolCall" -> "工具调用"; "webSearch" -> "搜索"
        "collabAgentToolCall" -> "子任务"; "contextCompaction" -> "整理上下文"; else -> item.kind
    }
    if (!message && item.kind != "reasoning") {
        val summary = remember(item.text) {
            val firstLine = item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            (if (firstLine == "{") runCatching {
                org.json.JSONObject(item.text).let { it.string("tool", it.string("name")) }
            }.getOrDefault("") else firstLine).take(300)
        }
        val status = timelineStatusLabel(item.status)
        Surface(onClick = { detail(title, item.text) }, color = Color(0xfff0f3ec), shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title + if (summary.isNotBlank()) " · $summary" else "", Modifier.weight(1f),
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (status.isNotBlank()) Text(status, Modifier.widthIn(max = 72.dp), fontSize = 10.sp,
                    color = if (item.status == "failed" || item.status == "declined") MaterialTheme.colorScheme.error else Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ChevronRight, "查看完整内容", Modifier.size(16.dp), tint = Muted)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(start = if (user) 24.dp else 0.dp)) {
        val statusLabel = timelineStatusLabel(item.status)
        Text(title + if (statusLabel.isNotBlank()) " · $statusLabel" else "", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        Surface(color = if (user) Color(0xffeaf0e4) else if (message) Paper else Color(0xfff0f3ec), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(if (message && !user) 0.dp else 12.dp)) {
                SelectionContainer {
                    Text(item.text.ifBlank { "执行中…" }.take(if (message) 24_000 else 800), fontSize = if (message) 14.sp else 11.sp,
                        lineHeight = if (message) 23.sp else 18.sp, fontFamily = if (message) FontFamily.Default else FontFamily.Monospace,
                        maxLines = if (message) Int.MAX_VALUE else 8, overflow = TextOverflow.Ellipsis)
                }
                if (!message || item.text.length > 24_000) TextButton({ detail(title, item.text) }, contentPadding = PaddingValues(top = 4.dp)) {
                    Text("展开完整内容", fontSize = 11.sp)
                }
            }
        }
    }
}

/** Local bubble for a message sent from this device; the label states the real receipt. */
@Composable
internal fun OutgoingCard(message: OutgoingMessage, detail: (String, String) -> Unit) {
    val label = outgoingStatusLabel(message)
    val labelColor = when (message.state) {
        OutgoingState.FAILED, OutgoingState.UNCERTAIN -> Color(0xffb65446)
        else -> Muted
    }
    Column(Modifier.fillMaxWidth().padding(start = 24.dp)) {
        Text("你 · $label", fontSize = 10.sp, color = labelColor, modifier = Modifier.padding(bottom = 6.dp))
        Surface(color = Color(0xffeaf0e4), shape = RoundedCornerShape(12.dp)) {
            // Same completeness as a settled user message: full text, with a detail sheet
            // only when the hard 24k cap truncates it.
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                SelectionContainer {
                    Text(message.text.take(24_000), fontSize = 14.sp, lineHeight = 23.sp)
                }
                if (message.text.length > 24_000)
                    TextButton({ detail("你", message.text) }, contentPadding = PaddingValues(top = 4.dp)) {
                        Text("展开完整内容", fontSize = 11.sp)
                    }
            }
        }
    }
}

@Composable
internal fun ConnectionScreen(state: UiState, vm: RemoteViewModel,
    reconnect: () -> Unit = { vm.connect() }, disconnect: () -> Unit = { vm.disconnect() }) {
    var profile by remember(state.profile) { mutableStateOf(state.profile) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(vm::pair) }
    val qrImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::pairImage) }
    var targetPass by remember { mutableStateOf("") }
    var jumpPass by remember { mutableStateOf("") }
    val importTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importKey(uri, false) } }
    val importJump = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importKey(uri, true) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = Color(0xffeaf1e3), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Computer, null, tint = Green)
                Text(state.profile.name, style = MaterialTheme.typography.titleLarge)
                Text(state.status, color = Muted, fontSize = 12.sp)
                Text("进入前台刷新 · 默认 YOLO", color = Green, fontSize = 11.sp)
            }
        }
        Text("扫码连接开发机", style = MaterialTheme.typography.titleLarge)
        Text("扫描电脑生成的连接码，自动完成连接配置。以后打开 App 即可使用。", color = Muted, fontSize = 13.sp)
        Button({
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描电脑上的 PocketHelm 连接码").setBeepEnabled(false).setOrientationLocked(false))
        }, Modifier.fillMaxWidth(), enabled = !state.busy, contentPadding = PaddingValues(15.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("扫码连接")
        }
        OutlinedButton({ qrImage.launch("image/*") }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("从二维码图片连接") }
        if (state.saved) {
            OutlinedButton(reconnect, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(if (state.connected) "刷新连接" else "连接已配对的开发机") }
        }
        if (state.connected) OutlinedButton(disconnect, Modifier.fillMaxWidth()) { Text("断开连接") }
        Text("连接码由开发机生成，5 分钟有效；请只扫描你信任的电脑提供的连接码。", color = Muted, fontSize = 11.sp)
        TextButton({ advanced = !advanced }) { Text(if (advanced) "收起手动连接设置" else "手动连接设置") }
        if (advanced) {
        Field("环境名称", profile.name) { profile = profile.copy(name = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("使用扫码配对的设备密钥", Modifier.weight(1f))
            Switch(profile.useDeviceKey, { profile = profile.copy(useDeviceKey = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("通过 SSH 跳板连接", Modifier.weight(1f)); Switch(profile.useJump, { profile = profile.copy(useJump = it) })
        }
        if (profile.useJump) {
            Field("跳板主机", profile.jumpHost) { profile = profile.copy(jumpHost = it.trim()) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { Field("跳板端口", profile.jumpPort.toString()) { profile = profile.copy(jumpPort = it.toIntOrNull() ?: 0) } }
                Box(Modifier.weight(1f)) { Field("跳板用户", profile.jumpUser) { profile = profile.copy(jumpUser = it.trim()) } }
            }
            if (!profile.useDeviceKey) {
            OutlinedButton({ importJump.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Key, null); Text("跳板私钥 · ${state.jumpKeyName}") }
            OutlinedTextField(jumpPass, { jumpPass = it }, Modifier.fillMaxWidth(), label = { Text("跳板私钥口令（如有，仅本次连接）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        }
        HorizontalDivider()
        Field(if (profile.useJump) "目标主机（从跳板访问）" else "开发机地址", profile.host) { profile = profile.copy(host = it.trim()) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Field("目标端口", profile.port.toString()) { profile = profile.copy(port = it.toIntOrNull() ?: 0) } }
            Box(Modifier.weight(1f)) { Field("开发机用户", profile.user) { profile = profile.copy(user = it.trim()) } }
        }
        if (!profile.useDeviceKey) {
        OutlinedButton({ importTarget.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Key, null); Text("开发机私钥 · ${state.targetKeyName}") }
        OutlinedTextField(targetPass, { targetPass = it }, Modifier.fillMaxWidth(), label = { Text("开发机私钥口令（如有，仅本次连接）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
        Field("项目根目录", profile.root) { profile = profile.copy(root = it) }
        Field("Codex 命令或绝对路径", profile.binary) { profile = profile.copy(binary = it) }
        Field("自定义 socket（可留空）", profile.socket) { profile = profile.copy(socket = it) }
        Text("留空时使用 CLI 自带共享服务。两台主机分别校验指纹，私钥加密保存在本机。", fontSize = 11.sp, color = Muted)
        Button({ vm.saveProfile(profile, targetPass, jumpPass); targetPass = ""; jumpPass = "" }, Modifier.fillMaxWidth(), enabled = !state.busy, contentPadding = PaddingValues(14.dp)) { Text("保存并连接") }
        if (state.version.isNotBlank()) Text(state.version, fontSize = 10.sp, color = Muted)
        }
        Text("PocketHelm ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label, fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(10.dp))
}

@Composable
private fun NewThreadDialog(state: UiState, onClose: () -> Unit, onCreate: (String, String) -> Unit) {
    var path by remember { mutableStateOf(state.projects.firstOrNull() ?: state.profile.root) }
    var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("新建会话") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${state.profile.name} · YOLO 自动执行", fontSize = 12.sp, color = Green)
            Field("会话名称（可选）", title) { title = it }
            Field("项目绝对路径", path) { path = it }
            state.projects.take(30).forEach { project ->
                Row(Modifier.fillMaxWidth().clickable { path = project }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (path == project) Icons.Outlined.CheckCircle else Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = Green)
                    Text(project.substringAfterLast('/'), Modifier.padding(start = 8.dp), fontSize = 13.sp)
                }
            }
        }
    }, confirmButton = { TextButton({ onCreate(path.trim(), title) }, enabled = path.startsWith('/')) { Text("创建会话") } },
        dismissButton = { TextButton(onClose) { Text("取消") } })
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Terminal, null, tint = Muted, modifier = Modifier.size(32.dp))
        Text(title, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun LoadingState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Green)
        Text(title, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 12.sp, color = Muted)
    }
}
private fun formatTime(seconds: Long) = if (seconds <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(seconds * 1000))
private fun syncTime(millis: Long) = if (millis <= 0) "待刷新" else SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(millis))
