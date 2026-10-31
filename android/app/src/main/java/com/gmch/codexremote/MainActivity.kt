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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

private val Green = Color(0xff226f50)
private val Ink = Color(0xff182720)
private val Paper = Color(0xfffbfcf8)
private val Muted = Color(0xff778575)
private val Line = Color(0xffe4e9e2)

class MainActivity : ComponentActivity() {
    private val model: RemoteViewModel by viewModels()
    private val zcode: ZcodeViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White,
                background = Paper, surface = Paper, onSurface = Ink, onBackground = Ink,
                primaryContainer = Color(0xffe7f0df), secondaryContainer = Color(0xffeaf1e3), outlineVariant = Line)) {
                EngineApp(model, zcode)
            }
        }
    }
    override fun onStart() { super.onStart(); if (zcode.active) zcode.foreground() else model.foreground() }
    override fun onStop() { model.background(); zcode.background(); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteApp(vm: RemoteViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var newThread by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Pair<String, String>?>(null) }
    var rename by remember { mutableStateOf(false) }
    val chat = state.selected != null && state.tab == 0
    BackHandler(chat) { vm.back() }
    Scaffold(containerColor = Paper,
        topBar = {
            Column {
                val compactScreen = LocalConfiguration.current.screenHeightDp < 480
                TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = {
                    Column {
                        // Decorative location label: dropped on short screens to give chat room.
                        if (!(chat && compactScreen)) Text(if (chat) state.selected!!.project else listOf("WORKSPACE", "ACTIVITY", "ENVIRONMENTS")[state.tab], fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                        Text(if (chat) state.selected!!.title else listOf("会话", "任务中心", "连接")[state.tab],
                            fontSize = if (chat) 18.sp else 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }, navigationIcon = { if (chat) IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回会话") } },
                    actions = {
                        IconButton(onClick = vm::refresh, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "刷新") }
                        if (chat) IconButton(onClick = { rename = true }, enabled = state.connected && !state.busy) { Icon(Icons.Outlined.Edit, "重命名") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green)
            }
        },
        bottomBar = {
            if (!chat) NavigationBar(containerColor = Paper) {
                listOf("会话" to Icons.Outlined.Forum, "任务" to Icons.Outlined.Checklist, "连接" to Icons.Outlined.Dns).forEachIndexed { i, pair ->
                    NavigationBarItem(state.tab == i, onClick = { vm.tab(i) }, icon = { Icon(pair.second, pair.first) }, label = { Text(pair.first) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.error != null) {
                Surface(color = Color(0xfff9ebe5), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error!!, Modifier.weight(1f), fontSize = 12.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { details = "连接与执行信息" to state.error!! }) { Icon(Icons.Outlined.Info, "详细信息") }
                        IconButton(onClick = vm::dismissError) { Icon(Icons.Outlined.Close, "关闭错误") }
                    }
                }
            }
            when {
                chat -> key(state.selected!!.id) {
                    ChatScreen(state, vm) { title, text -> details = title to text }
                }
                state.tab == 0 -> SessionScreen(state, vm) { newThread = true }
                state.tab == 1 -> TasksScreen(state, vm)
                else -> ConnectionScreen(state, vm)
            }
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

@Composable
private fun ConnectionStrip(state: UiState, vm: RemoteViewModel) {
    Surface(shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line), color = Color.White,
        modifier = Modifier.fillMaxWidth().clickable { vm.tab(2) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Computer, null, tint = if (state.connected) Green else Muted, modifier = Modifier.size(18.dp))
            Text(state.profile.name, Modifier.weight(1f).padding(start = 9.dp), fontSize = 12.sp)
            Text(if (state.connected) "已连接" else "离线 · 缓存", fontSize = 10.sp, color = Muted)
        }
    }
}

@Composable
private fun SessionScreen(state: UiState, vm: RemoteViewModel, onNew: () -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 480
    var search by rememberSaveable { mutableStateOf("") }
    var project by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(search, project, compact) { listState.scrollToItem(0) }
    val projects = state.sessions.map { it.cwd }.distinct()
    val sessions = state.sessions.filter { (compact || project.isEmpty() || it.cwd == project) && (it.title.contains(search, true) || it.cwd.contains(search, true)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        if (compact) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(search, { search = it }, Modifier.weight(1f), placeholder = { Text("搜索会话或项目", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp))
                Button(onNew, enabled = state.connected && !state.busy) { Text("新建会话") }
            }
        } else {
        ConnectionStrip(state, vm)
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(top = 12.dp), placeholder = { Text("搜索会话或项目", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(12.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(project.isEmpty(), { project = "" }, label = { Text("全部项目") })
            projects.forEach { path -> FilterChip(project == path, { project = path }, label = { Text(path.trimEnd('/').substringAfterLast('/')) }) }
        }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (compact) "最近会话 · ${if (state.connected) "已连接" else "离线缓存"}" else "最近会话", fontSize = 12.sp, color = Muted)
            Text("${sessions.size} 个 · ${if (state.connected) syncTime(state.lastSync) else "状态待刷新"}", fontSize = 10.sp, color = Muted)
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sessions.isEmpty()) item {
                when {
                    // In-flight first load must not read as an empty workspace.
                    state.sessions.isEmpty() && state.busy -> LoadingState("正在加载会话…", "首次连接会拉取最近的会话列表。")
                    !state.saved -> EmptyState("连接你的开发机", "在连接页扫描开发机的连接码，自动完成配对。")
                    !state.connected -> EmptyState("连接后加载会话", "点击顶部刷新或到连接页重连。")
                    state.sessions.isEmpty() && state.error != null -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("会话加载失败：${state.error}", fontSize = 12.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Button(vm::refresh, enabled = state.connected && !state.busy) { Text("重试") }
                    }
                    else -> EmptyState("暂无匹配的会话", "可刷新列表或选择其他项目。")
                }
            }
            items(sessions, key = { it.id }) { SessionCard(it) { vm.openThread(it) } }
            if (state.cursor != null) item { TextButton(vm::moreSessions, Modifier.fillMaxWidth(), enabled = state.connected && !state.busy) { Text("加载更多会话") } }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (!compact) Button(onNew, Modifier.fillMaxWidth().padding(vertical = 12.dp), enabled = state.connected && !state.busy, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(14.dp)) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(7.dp)); Text("新建会话")
        }
    }
}

@Composable
private fun SessionCard(session: SessionInfo, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = Muted)
                Text(session.project, Modifier.weight(1f).padding(start = 6.dp), fontSize = 11.sp, color = Muted, maxLines = 1)
                StatusBadge(session.status)
            }
            Text(session.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(session.cwd, fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatTime(session.updatedAt), fontSize = 10.sp, color = Muted)
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    val bad = text == "失败"
    Surface(shape = RoundedCornerShape(6.dp), color = if (bad) Color(0xfff8e9e3) else Color(0xffeaf2e7)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, color = if (bad) Color(0xffa75544) else Green)
    }
}

@Composable
private fun TasksScreen(state: UiState, vm: RemoteViewModel) {
    val compact = LocalConfiguration.current.screenHeightDp < 480
    var filter by rememberSaveable { mutableStateOf("全部") }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text(if (state.connected) "YOLO 默认 · 前台自动同步" else "离线快照 · 刷新后确认状态", fontSize = 11.sp, color = Muted)
        if (compact) Text(listOf("执行中", "已完成", "失败").joinToString(" · ") { label -> "$label ${state.sessions.count { it.status == label }}" }, fontSize = 12.sp, color = Green)
        else Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("执行中", "已完成", "失败").forEach { label ->
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                    Column(Modifier.padding(13.dp)) {
                        Text(state.sessions.count { it.status == label }.toString(), fontSize = 27.sp)
                        Text(label, fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "执行中", "已完成", "失败", "已中断").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
        }
        Text("当前已加载会话的最近执行", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val list = state.sessions.filter { filter == "全部" || it.status == filter }
            if (list.isEmpty()) item {
                if (state.sessions.isEmpty() && state.busy) LoadingState("正在加载会话…", "首次连接会拉取最近的会话列表。")
                else EmptyState("暂无任务", "任务状态来自开发机，点击顶部刷新。")
            }
            items(list, key = { it.id }) { SessionCard(it) { vm.openThread(it) } }
            if (state.cursor != null) item { TextButton(vm::moreSessions, enabled = state.connected && !state.busy) { Text("加载更多") } }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun ChatScreen(state: UiState, vm: RemoteViewModel, detail: (String, String) -> Unit) {
    val session = state.selected!!
    // The compact composer also kicks in while the keyboard is up, so chat and send stay visible.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val compact = imeBottom > 0 || LocalConfiguration.current.screenHeightDp < 480
    var steer by rememberSaveable(session.id) { mutableStateOf(false) }
    var confirmUncertain by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var jumpToMessage by remember { mutableStateOf<String?>(null) }
    // Server history merged with local outgoing bubbles and queue entries; the bubble for a
    // just-sent message exists here immediately, without faking server history.
    val rows = remember(state.items, state.queue, state.outgoing) { buildChatRows(state.items, state.outgoing, state.queue) }
    val userRows = remember(rows) { rows.filter { it.isUserRow } }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followLatest = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    withFrameNanos { }
                    snapshotFlow { list.isScrollInProgress }.first { !it }
                    followLatest = !list.canScrollForward
                }
            }
        }
    }
    // The first history snapshot can arrive before LazyColumn has measured it.
    // Target a tail item using the new data count, including when the last reply is tall.
    val tailIndex = (if (state.historyCursor != null) 1 else 0) +
        maxOf(1, rows.size) + (if (state.diff.isNotBlank()) 1 else 0)
    LaunchedEffect(followLatest, tailIndex, rows.lastOrNull(), imeBottom) {
        if (followLatest && rows.isNotEmpty()) list.scrollToItem(tailIndex)
    }
    LaunchedEffect(jumpToMessage, rows, state.historyCursor) {
        val id = jumpToMessage ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            list.scrollToItem(index + if (state.historyCursor != null) 1 else 0)
            jumpToMessage = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        val run = codexAgentRun(state.connected, session.runtime)
        if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge("YOLO · 默认发送模式")
            Spacer(Modifier.weight(1f)); RunStatusRow(run, agentRunLabel(run, session.status))
        } else if (run != AgentRun.Idle) {
            RunStatusRow(run, agentRunLabel(run, session.status), Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        }
        if (userRows.isNotEmpty() || state.historyCursor != null) UserMessageBar(
            messages = userRows, hasMore = state.historyCursor != null, loading = state.historyLoading,
            canLoad = state.connected, compact = compact,
            onLoadMore = { followLatest = false; vm.moreHistory() },
            onJump = { id -> followLatest = false; jumpToMessage = id }
        )
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            // Compact screens: tight vertical padding and gaps so the tail is real content, not spacing.
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (compact) 4.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 14.dp)) {
            if (state.historyCursor != null) item { TextButton({ followLatest = false; vm.moreHistory() }, Modifier.fillMaxWidth(), enabled = state.connected && !state.busy) { Text(if (state.historyLoading) "加载中…" else "加载更早的消息") } }
            when (timelineView(rows.size, state.historyLoading, state.historyError)) {
                TimelineView.Loading -> item(key = "timeline-loading") { Text("正在加载会话内容…", fontSize = 12.sp, color = Muted, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                TimelineView.Error -> item(key = "timeline-error") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("会话内容加载失败：${state.historyError}", fontSize = 12.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Button(vm::retryHistory, enabled = state.connected && !state.busy) { Text("重试") }
                    }
                }
                TimelineView.Empty -> item { EmptyState("开始这次工作", "输入任务后在开发机上按 YOLO 执行。") }
                TimelineView.Content -> {}
            }
            items(rows, key = { it.key }) { row ->
                when {
                    row.item != null -> TimelineCard(row.item, detail = detail)
                    row.outgoing != null -> OutgoingCard(row.outgoing, detail)
                    row.queued != null -> {
                        val queued = row.queued
                        Surface(color = Color(0xfff0f3ec), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("已在开发机排队", fontSize = 10.sp, color = Green)
                                Text(queued.text, fontSize = 13.sp)
                                TextButton({ vm.deleteQueued(queued) }, enabled = state.connected && !state.busy) { Text("移出队列") }
                            }
                        }
                    }
                }
            }
            if (state.diff.isNotBlank()) item { OutlinedButton({ detail("当前执行差异", state.diff) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Difference, null); Text("查看本轮修改") } }
            item(key = "chat-tail") { Spacer(Modifier.height(1.dp)) }
        }
        if (!followLatest) TextButton({ jumpToMessage = null; followLatest = true }, Modifier.align(Alignment.CenterHorizontally).height(if (compact) 32.dp else 40.dp), contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text("回到最新")
        }
        ExecutionControls(state, vm)
        // One stable TextField across compact/noncompact: branching into two separate fields
        // destroyed the editing one when the keyboard toggled compact, dropping focus mid-input.
        // Compact keeps a single row (short-screen message area); extra controls are only ever
        // appended AFTER the TextField so its slot in the composition never moves.
        Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (compact) 2.dp else 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    TextField(state.draft, vm::draft, Modifier.weight(1f).heightIn(max = if (compact) 88.dp else 160.dp),
                        placeholder = { Text("描述任务，或补充要求…", fontSize = 13.sp) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        maxLines = if (compact) 2 else 5)
                    if (compact && session.runtime == "active") {
                        FilterChip(steer, { steer = !steer }, label = { Text(if (steer) "介入" else "排队", fontSize = 11.sp) })
                        IconButton(vm::interrupt, enabled = state.connected && !state.busy && session.activeTurnId.isNotBlank()) {
                            Icon(Icons.Outlined.Stop, "中断", tint = Color(0xffb65446))
                        }
                    }
                    FilledIconButton({ vm.send(steer && session.runtime == "active") }, enabled = state.connected && !state.busy && !state.sending && state.draft.isNotBlank() && state.uncertain == null) {
                        if (state.sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(Icons.AutoMirrored.Outlined.Send, "发送消息")
                    }
                }
                if (!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (session.runtime == "active") {
                        FilterChip(steer, { steer = !steer }, label = { Text(if (steer) "介入当前任务" else "排队发送", fontSize = 11.sp) })
                        TextButton(vm::interrupt, enabled = state.connected && !state.busy && session.activeTurnId.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("中断", fontSize = 11.sp, color = Color(0xffb65446)) }
                    } else Text("发送并执行", fontSize = 11.sp, color = Muted)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserMessageBar(
    messages: List<ChatRow>, hasMore: Boolean, loading: Boolean, canLoad: Boolean, compact: Boolean,
    onLoadMore: () -> Unit, onJump: (String) -> Unit
) {
    var showMessages by remember { mutableStateOf(false) }
    val latest = messages.lastOrNull()
    Surface(color = Color(0xffeaf0e4), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable {
                if (latest != null) onJump(latest.id) else showMessages = true
            }.padding(horizontal = 18.dp, vertical = if (compact) 4.dp else 8.dp)) {
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
private fun ConnectionScreen(state: UiState, vm: RemoteViewModel) {
    var profile by remember(state.profile) { mutableStateOf(state.profile) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(vm::pair) }
    val qrImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::pairImage) }
    var targetPass by remember { mutableStateOf("") }
    var jumpPass by remember { mutableStateOf("") }
    val importTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importKey(uri, false) } }
    val importJump = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importKey(uri, true) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = Color(0xffeaf1e3), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            OutlinedButton(vm::connect, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(if (state.connected) "刷新连接" else "连接已配对的开发机") }
        }
        if (state.connected) OutlinedButton({ vm.disconnect() }, Modifier.fillMaxWidth()) { Text("断开连接") }
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
