package com.gmch.codexremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first

@Composable
internal fun EngineApp(codex: RemoteViewModel, zcode: ZcodeViewModel) {
    val navigation by codex.state.collectAsStateWithLifecycle()
    HostTrustDialog(navigation, codex)
    val engine = navigation.engine
    val pages = rememberSaveableStateHolder()
    LaunchedEffect(engine, navigation.profile.resumeIdentity) {
        if (engine == "zcode") { codex.leave(); zcode.enter() }
        else { zcode.leave(); codex.foreground() }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            pages.SaveableStateProvider("${navigation.profile.resumeIdentity}:$engine") {
                if (engine == "codex") RemoteApp(codex)
                else ZcodeScreen(zcode, codex)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZcodeScreen(vm: ZcodeViewModel, codex: RemoteViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigation by codex.state.collectAsStateWithLifecycle()
    var newSession by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pages = rememberSaveableStateHolder()
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var unlock by remember { mutableStateOf(false) }
    val chat = state.selected != null && tab == 0
    BackHandler(chat) { vm.back() }
    when {
        tab == 2 -> WorkspaceScaffold("连接", chat = false, tab = tab, busy = state.busy || navigation.busy,
            refresh = vm::refresh, selectTab = { tab = it }, engine = "zcode" to { codex.engine(it) }) {
            WorkspaceError(navigation.error ?: state.error, { codex.dismissError(); vm.dismissError() }) { title, text -> detail = title to text }
            pages.SaveableStateProvider("tab:2") {
                ConnectionScreen(navigation.copy(connected = state.connected, busy = state.busy || navigation.busy,
                    status = "ZCode · " + if (state.connected) "已连接" else "未连接"), codex, vm::connect, vm::disconnectManually)
            }
        }
        chat -> WorkspaceScaffold(state.selected!!.title, state.selected!!.workspace(state.projects).project,
            chat = true, busy = state.busy, back = vm::back, refresh = vm::refresh) {
            WorkspaceError(state.error, vm::dismissError) { title, text -> detail = title to text }
            if (pendingProtectionVisible(state.pending, state.commandInFlight)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("有一条提交尚待确认，请刷新查询。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.pendingUnknown) TextButton({ unlock = true }, enabled = !state.busy) { Text("已核对会话，解除发送保护") }
                }
            }
            pages.SaveableStateProvider("chat:${state.selected!!.id}") {
                Column(Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    ChatRunHeader(zcodeAgentRun(state.connected, state.phase, state.interactionCount), phaseLabel(state.phase),
                        state.historyCached, state.connected, state.executionId.isNotBlank(),
                        state.connected && !state.busy && !state.pending && !state.commandInFlight, vm::stop)
                    if (state.providers.find { it.id == state.selection?.provider }?.let { it.guard != state.selectionGuard } == true)
                        Text("额度来源已变化，请重新选择计划", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    if (state.interactionCount > 0) Text("ZCode 正在等待交互；尚未接入该交互类型。",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    if (state.notice.isNotBlank()) Text(state.notice, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    ZcodeTimeline(state, Modifier.weight(1f), vm::history, vm::retryLoad) { a, b -> detail = a to b }
                    ChatSettingsBar(zcodeSettingsSummary(state), state.quotaSummary,
                        { showSettings = true }, { showSettings = true; vm.quota() })
                    ChatComposer(state.draft, vm::draft, state.sending,
                        state.connected && !state.busy && !state.pending && !state.sending && state.draft.isNotBlank() && state.selection != null,
                        state.delivery == "guide", { vm.delivery(if (it) "guide" else "queue") }, vm::send)
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
        else -> pages.SaveableStateProvider("tab:0") { WorkspaceListPage(engine = "zcode", onEngine = { codex.engine(it) },
            sessions = state.sessions.map { it.workspace(state.projects, state.connected && state.sessionsFresh, state.liveStatuses) },
            connected = state.connected, busy = state.busy,
            canCreate = state.connected && !state.busy && !state.pending, canOpen = state.connected && !state.busy,
            open = { id -> tab = 0; state.sessions.find { it.id == id }?.let(vm::open) }, create = { newSession = true },
            reconnect = vm::connect, refresh = vm::refresh, addProject = vm::openBrowser,
            error = state.error, dismissError = vm::dismissError, detail = { title, text -> detail = title to text },
            selectTab = { tab = it }) {
            if (pendingProtectionVisible(state.pending, state.commandInFlight)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("有一条提交尚待确认，请刷新查询。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.pendingUnknown) TextButton({ unlock = true }, enabled = !state.busy) { Text("已核对会话，解除发送保护") }
                }
            }
        } }
    }
    if (newSession) AlertDialog(onDismissRequest = { newSession = false }, title = { Text("选择项目") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ZcodeControls(state, vm)
            Text("新会话默认完全访问（Full access）", fontSize = 12.sp)
            state.projects.filter { it.roots.size == 1 }.forEach { p ->
                TextButton({ newSession = false; tab = 0; vm.create(p) }, enabled = state.selection != null) { Text(p.name + "\n" + p.roots.single()) }
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
        Text(state.quota, Modifier.fillMaxWidth().clickable(enabled = enabled) { vm.quota() }.padding(vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall)
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
internal fun ZcodeTimeline(state: ZcodeState, modifier: Modifier, history: () -> Unit, retry: () -> Unit, detail: (String, String) -> Unit) {
    ChatTimeline(state.selected?.id.orEmpty(), "ZCode", state.items, state.outgoing, state.queue,
        state.beforeRow != null, state.historyLoading, state.historyError, state.connected, state.busy,
        modifier, history, retry, detail)
}
