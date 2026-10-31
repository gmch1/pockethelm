package com.gmch.codexremote

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun EngineApp(codex: RemoteViewModel, zcode: ZcodeViewModel) {
    var engine by rememberSaveable { mutableStateOf("codex") }
    LaunchedEffect(engine) {
        if (engine == "zcode") { codex.background(); zcode.enter() }
        else { zcode.leave(); codex.foreground() }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(engine == "codex", { engine = "codex" }, label = { Text("Codex") })
            FilterChip(engine == "zcode", { engine = "zcode" }, label = { Text("ZCode · 实验") })
        }
        Box(Modifier.weight(1f)) {
            if (engine == "codex") RemoteApp(codex)
            else ZcodeScreen(zcode) { engine = "codex"; codex.tab(2) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZcodeScreen(vm: ZcodeViewModel, connection: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var newSession by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var unlock by remember { mutableStateOf(false) }
    BackHandler(state.selected != null) { vm.back() }
    Scaffold(topBar = { TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = {
        Text(state.selected?.title ?: "ZCode 会话", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }, navigationIcon = { if (state.selected != null) TextButton(vm::back, enabled = !state.busy) { Text("返回") } },
        actions = {
            TextButton(vm::refresh, enabled = !state.busy) { Text(if (state.connected) "刷新" else "连接") }
            if (state.selected == null) TextButton(connection) { Text("配对") }
        }) }, contentWindowInsets = WindowInsets.navigationBars) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.connected) Text("连接开发机上的 ZCode 服务 · 离线后任务继续运行", fontSize = 12.sp)
            state.error?.let { err ->
                var expanded by remember { mutableStateOf(false) }
                Surface(color = Color(0xfff9ebe5), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(err, Modifier.weight(1f), fontSize = 12.sp, maxLines = if (expanded) 10 else 2, overflow = TextOverflow.Ellipsis)
                        IconButton({ expanded = !expanded }, Modifier.size(32.dp)) {
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起详情" else "展开详情", Modifier.size(16.dp))
                        }
                        if (!state.connected) TextButton(vm::connect, enabled = !state.busy) { Text("重连") }
                        IconButton(vm::dismissError, Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "关闭错误", Modifier.size(16.dp)) }
                    }
                }
            }
            if (pendingProtectionVisible(state.pending, state.commandInFlight)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("有一条提交尚待确认，请刷新查询。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.pendingUnknown) TextButton({ unlock = true }, enabled = !state.busy) { Text("已核对会话，解除发送保护") }
                }
            }
            if (state.selected == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(vm::openBrowser, enabled = state.connected && !state.busy) { Text("添加项目目录") }
                    Button({ newSession = true }, enabled = state.connected && !state.busy && !state.pending) { Text("新建会话") }
                }
                Text("进入会话后选择计划、模型和推理强度。", fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.sessions.isEmpty()) item {
                        // Distinguish connecting / loading / failed / truly empty.
                        Text(when {
                            state.busy && state.connected -> "正在加载会话…"
                            state.busy -> "正在连接开发机…"
                            !state.connected -> "连接开发机后加载会话。"
                            state.error != null -> "会话加载失败，可通过上方提示重连或点击刷新重试。"
                            else -> "暂无会话，连接后刷新或新建。"
                        }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(state.sessions, key = { it.path + ":" + it.identity + ":" + it.id }) { session ->
                        Surface(onClick = { vm.open(session) }, enabled = state.connected && !state.busy,
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(session.path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                                Text("状态进入后刷新", fontSize = 10.sp)
                            }
                        }
                    }
                }
            } else {
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val compact = imeVisible || LocalConfiguration.current.screenHeightDp < 480
                var showSettings by remember { mutableStateOf(false) }
                val run = zcodeAgentRun(state.connected, state.phase, state.interactionCount)
                // Run status and stop stay pinned; only the settings summary scrolls.
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RunStatusRow(run, "${state.permission.ifBlank { "读取中" }} · ${agentRunLabel(run, phaseLabel(state.phase))}", Modifier.weight(1f))
                    if (state.executionId.isNotBlank()) TextButton(onClick = vm::stop,
                        enabled = state.connected && !state.busy && !state.pending && !state.commandInFlight, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("停止") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ showSettings = true }, label = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.Tune, "打开计划与模型设置", Modifier.size(14.dp))
                            Text(zcodeSettingsSummary(state), Modifier.widthIn(max = 200.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    })
                    AssistChip({ showSettings = true; vm.quota() }, label = {
                        Text(if (state.quota.startsWith("剩余")) state.quota.take(24) else "额度", Modifier.widthIn(max = 140.dp),
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    })
                    if (state.providers.find { it.id == state.selection?.provider }?.let { it.guard != state.selectionGuard } == true)
                        Text("额度来源已变化", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
                if (state.interactionCount > 0) Text("ZCode 正在等待交互；本实验版尚未接入该交互类型。",
                    color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.notice.isNotBlank()) Text(state.notice, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                key(state.selected!!.id) { ZcodeTimeline(state, Modifier.weight(1f), vm::history, vm::retryLoad) { a, b -> detail = a to b } }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(state.delivery == "queue", { vm.delivery("queue") }, label = { Text("排队", fontSize = 11.sp) })
                    FilterChip(state.delivery == "guide", { vm.delivery("guide") }, label = { Text("插入当前任务", fontSize = 11.sp) })
                }
                ZcodeComposer(state, vm, compact)
                if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("计划与模型", style = MaterialTheme.typography.titleLarge)
                        ZcodeControls(state, vm)
                        Text("切换仅影响下次发送；会话当前使用的模型不变。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("新会话默认完全访问（Full access）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (newSession) AlertDialog(onDismissRequest = { newSession = false }, title = { Text("选择项目") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ZcodeControls(state, vm)
            Text("新会话默认完全访问（Full access）", fontSize = 12.sp)
            state.projects.filter { it.roots.size == 1 }.forEach { p ->
                TextButton({ newSession = false; vm.create(p) }, enabled = state.selection != null) { Text(p.name + "\n" + p.roots.single()) }
            }
            if (state.projects.isEmpty()) Text("先添加开发机上已有的项目目录。")
        }
    }, confirmButton = { TextButton({ newSession = false }) { Text("取消") } })
    ZcodeDirectoryPicker(state.browser, vm)
    if (unlock) AlertDialog(onDismissRequest = { unlock = false }, title = { Text("解除发送保护") },
        text = { Text("服务端未能确认上一条命令。请先核对会话是否已执行；再次发送可能造成重复任务。解除保护不会自动重发。") },
        confirmButton = { TextButton({ vm.releaseUnknown(); unlock = false }) { Text("已核对，解除保护") } },
        dismissButton = { TextButton({ unlock = false }) { Text("返回") } })
    detail?.let { (title, text) ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                SelectionContainer { Text(text, modifier = Modifier.padding(vertical = 16.dp)) }
            }
        }
    }
}

/** Compact summary of the current plan/model/level; details and switching live in the sheet. */
private fun zcodeSettingsSummary(state: ZcodeState): String {
    val selection = state.selection ?: return "选择计划与模型"
    val provider = state.providers.find { it.id == selection.provider }
    val name = when (provider?.kind) {
        "individual-coding-plan" -> "个人 Coding Plan"
        "team-coding-plan" -> "团队 Coding Plan"
        "start-plan" -> "Start Plan"
        else -> provider?.name ?: selection.provider.substringAfterLast(':')
    }
    return listOfNotNull(name, selection.model, selection.level.ifBlank { null }).joinToString(" · ")
}

/** One row in both modes: borderless input + send; only the input max height adapts. */
@Composable
private fun ZcodeComposer(state: ZcodeState, vm: ZcodeViewModel, compact: Boolean) {
    val canSend = state.connected && !state.busy && !state.pending && !state.sending && state.draft.isNotBlank() && state.selection != null
    Surface(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Bottom) {
            TextField(state.draft, vm::draft, Modifier.weight(1f).heightIn(max = if (compact) 88.dp else 160.dp),
                placeholder = { Text("发消息给 ZCode", fontSize = 13.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                maxLines = if (compact) 2 else 5)
            Button(onClick = vm::send, enabled = canSend, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                if (state.sending) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                Text(if (state.sending) "发送中" else "发送")
            }
        }
    }
}

private fun phaseLabel(value: String) = when (value) {
    "draft", "idle" -> "空闲"; "running" -> "执行中"; "waiting" -> "等待中"
    "prewarming" -> "准备中"; "completedSuccess" -> "已完成"; "completedInterrupted" -> "已中断"; "error", "failed" -> "失败"; else -> value
}

@Composable
private fun ZcodeControls(state: ZcodeState, vm: ZcodeViewModel) {
    val selection = state.selection
    val provider = state.providers.find { it.id == selection?.provider }
    val model = provider?.models?.find { it.id == selection?.model }
    val enabled = state.connected && !state.busy && !state.pending
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZChoice(when (provider?.kind) {
                "individual-coding-plan" -> "个人 Coding Plan"
                "team-coding-plan" -> "团队 Coding Plan"
                "start-plan" -> "Start Plan"
                else -> provider?.name ?: "选择额度来源"
            }, enabled, state.providers.map { it.id to it.name }) { id ->
                val p = state.providers.first { it.id == id }
                p.models.firstOrNull()?.let { m -> vm.choose(ZSelection(id, m.id, m.levels.lastOrNull().orEmpty())) }
            }
            ZChoice(selection?.model ?: "模型", enabled, provider?.models.orEmpty().map { it.id to it.id }) { id ->
                val m = provider!!.models.first { it.id == id }
                vm.choose(ZSelection(provider.id, id, m.levels.lastOrNull().orEmpty()))
            }
            if (model?.levels?.isNotEmpty() == true) ZChoice(selection?.level.orEmpty().ifBlank { "推理强度" }, enabled,
                model.levels.map { it to it }) { level -> vm.choose(selection!!.copy(level = level)) }
        }
        Text("开发机已连接的计划 · 账号/团队切换后刷新", fontSize = 10.sp)
        if (provider != null && provider.guard != state.selectionGuard)
            Text("额度来源已变化，请重新选择计划", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        Text(state.quota, Modifier.fillMaxWidth().clickable(enabled = enabled) { vm.quota() }.padding(vertical = 7.dp), fontSize = 11.sp)
        if (state.currentSelection != null && state.currentSelection != selection)
            Text("会话当前：${state.currentSelection.model}；下次发送使用上方选择", fontSize = 10.sp)
    }
}

@Composable
private fun ZChoice(label: String, enabled: Boolean, options: List<Pair<String, String>>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip({ expanded = true }, label = { Text(label, Modifier.widthIn(max = 180.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, enabled = enabled && options.isNotEmpty())
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; choose(value) }) }
        }
    }
}

@Composable
private fun ZcodeTimeline(state: ZcodeState, modifier: Modifier, history: () -> Unit, retry: () -> Unit, detail: (String, String) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    // Server history merged with local outgoing bubbles and queue entries: a just-sent or
    // queued message shows as a full user bubble here, deduped by client id, and stays
    // reachable from the sticky user-message navigation.
    val rows = remember(state.items, state.outgoing, state.queue) { buildChatRows(state.items, state.outgoing, state.queue) }
    // Only real drags pause following; programmatic scrolls emit no drag interactions.
    LaunchedEffect(list) {
        list.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    withFrameNanos { }
                    snapshotFlow { list.isScrollInProgress }.first { !it }
                    // Reached the tail → resume following; otherwise stay put until 回到最新.
                    follow = !list.canScrollForward
                }
                else -> {}
            }
        }
    }
    // The tail anchor sits after the last message, so long replies land on the true bottom.
    val tailIndex = rows.size + if (state.beforeRow != null) 1 else 0
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(follow, rows, state.beforeRow, imeBottom) {
        if (follow && rows.isNotEmpty()) list.scrollToItem(tailIndex)
    }
    Column(modifier) {
        val question = rows.lastOrNull { it.isUserRow }
        question?.let { q ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    follow = false
                    scope.launch { list.scrollToItem(rows.indexOf(q) + if (state.beforeRow != null) 1 else 0) }
                }) { Text(q.text, Modifier.padding(8.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.beforeRow != null) item(key = "history") {
                TextButton({ follow = false; history() }, enabled = !state.busy && !state.historyLoading) {
                    Text(if (state.historyLoading) "加载中…" else "加载更早消息")
                }
            }
            when (timelineView(rows.size, state.historyLoading, state.historyError)) {
                TimelineView.Loading -> item(key = "timeline-loading") { Text("正在加载会话内容…", fontSize = 12.sp) }
                TimelineView.Error -> item(key = "timeline-error") {
                    Column {
                        Text("会话内容加载失败：${state.historyError}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        TextButton(retry, enabled = state.connected && !state.busy) { Text("重试") }
                    }
                }
                TimelineView.Empty -> item(key = "timeline-empty") { Text("暂无消息，发送第一条任务。", fontSize = 12.sp) }
                TimelineView.Content -> {}
            }
            items(rows, key = { it.key }) { row ->
                when {
                    row.item != null -> TimelineCard(row.item, agentName = "ZCode", detail = detail)
                    row.outgoing != null -> OutgoingCard(row.outgoing, detail)
                    row.queued != null -> OutgoingCard(OutgoingMessage(
                        state.selected?.id.orEmpty(), row.queued.clientId, row.queued.text,
                        OutgoingState.QUEUED, "已排队"), detail)
                }
            }
            item(key = "tail") { Spacer(Modifier.height(1.dp)) }
        }
        if (!follow) TextButton({ follow = true; scope.launch { list.scrollToItem(tailIndex) } }, Modifier.align(Alignment.CenterHorizontally)) { Text("回到最新") }
    }
}
