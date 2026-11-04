package com.gmch.codexremote

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
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
    var interactionSheet by remember { mutableStateOf(false) }
    // In-memory form drafts keyed by interaction id; scoped to the selected session and
    // surviving sheet open/close and polls. Never persisted, cleared when it ends.
    val forms = remember(navigation.profile.resumeIdentity, state.selected?.path, state.selected?.identity, state.selected?.id) { mutableStateMapOf<String, InteractionFormState>() }
    LaunchedEffect(state.interactions, state.interactionsComplete, state.interactionPresence, forms) {
        val live = state.interactions.map { it.id }.toSet()
        forms.keys.removeAll(forms.keys.filter {
            state.interactionPresence[it] == false || (state.interactionsComplete && it !in live)
        })
        if (state.interactionsComplete && live.isEmpty()) interactionSheet = false
    }
    // The turn-diff badge fires an RPC; its formatted result lands here and opens the
    // shared detail sheet once, then clears so reopening re-queries.
    LaunchedEffect(state.diffText) {
        state.diffText?.let { detail = "本轮更改明细" to it; vm.clearTurnDiff() }
    }
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
                    if (state.recoveryPhase == RecoveryPhase.Restoring) Text("正在恢复会话…", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp)
                    ChatRunHeader(zcodeAgentRun(state.connected, state.phase, state.interactionCount), phaseLabel(state.phase),
                        state.historyCached, state.connected, state.executionId.isNotBlank(),
                        state.connected && !state.busy && !state.pending && !state.commandInFlight, vm::stop)
                    if (state.providers.find { it.id == state.selection?.provider }?.let { it.guard != state.selectionGuard } == true)
                        Text("额度来源已变化，请重新选择计划", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    if (state.interactionsEnabled && state.interactions.isNotEmpty())
                        InteractionBanner(zcodeInteractionLabel(state.interactions), state.interactionCount) { interactionSheet = true }
                    else if (state.interactionCount > 0) Text("ZCode 正在等待交互；当前桥接器尚未接入应答能力，请更新开发机桥接器。",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    if (state.interactionNotice.isNotBlank()) Text(state.interactionNotice, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    if (state.notice.isNotBlank()) Text(state.notice, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    ZcodeTimeline(state, vm, Modifier.weight(1f), vm::history, vm::retryLoad) { a, b -> detail = a to b }
                    ChatSettingsBar(zcodeSettingsSummary(state), state.quotaSummary,
                        { showSettings = true }, { showSettings = true; vm.quota() })
                    val pickImage = rememberAttachmentPicker(vm::attachmentScope, vm::addAttachment)
                    ChatComposer(state.draft, vm::draft, state.sending,
                        state.connected && !state.busy && !state.pending && !state.sending && !state.attachmentsBusy && (state.draft.isNotBlank() || state.attachments.isNotEmpty()) && state.selection != null,
                        state.delivery == "guide", { vm.delivery(if (it) "guide" else "queue") }, vm::send,
                        attachments = state.attachments, attachmentsBusy = state.attachmentsBusy,
                        canAttach = state.attachmentsEnabled && !state.attachmentsBusy
                            && state.attachments.size < ImagePolicy.MESSAGE_MAX && !state.sending && !state.busy,
                        attach = pickImage,
                        removeAttachment = vm::removeAttachment)
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
            sessions = state.sessions.map { it.workspace(state.projects, state.connected && state.sessionsFresh, state.liveStatuses, state.listMeta) },
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
    if (interactionSheet && state.selected != null && state.interactions.isNotEmpty()) {
        ZcodeInteractionSheet(state, vm, forms) { interactionSheet = false }
    }
}

private fun zcodeInteractionLabel(interactions: List<ZInteraction>): String {
    val titles = interactions.map { it.title }.distinct()
    return titles.singleOrNull() ?: "待处理交互"
}

/** In-memory form state for one AskUserQuestion; drafts live until the interaction ends. */
private class InteractionFormState {
    var index by mutableIntStateOf(-1) // -1: follow the snapshot's current question
    val drafts = mutableStateMapOf<String, ZFormDraft>()
    var seeded = false
    var text by mutableStateOf("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZcodeInteractionSheet(
    state: ZcodeState, vm: ZcodeViewModel, forms: SnapshotStateMap<String, InteractionFormState>, onDismiss: () -> Unit
) {
    val session = state.selected ?: return
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.interactions.forEach { interaction ->
                ZcodeInteractionCard(interaction, state, session, vm, forms)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (!state.interactionsComplete && state.interactionCount > state.interactions.size)
                Text("还有 ${state.interactionCount - state.interactions.size} 个待交互，处理完当前内容后会继续显示。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.interactionNotice.isNotBlank())
                Text(state.interactionNotice, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ZcodeInteractionCard(
    interaction: ZInteraction, state: ZcodeState, session: ZSession,
    vm: ZcodeViewModel, forms: SnapshotStateMap<String, InteractionFormState>
) {
    val disabled = !state.connected || interaction.id in state.interactSubmitting
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(interaction.title, style = MaterialTheme.typography.titleLarge)
        if (interaction.toolName.isNotBlank())
            Text("工具：${interaction.toolName}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ZcodeAutoResolutionLine(interaction, state)
        if (!interaction.supported) {
            Text(zcodeUnsupportedReason(interaction.unsupportedReason), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (interaction.questions.isNotEmpty()) ZcodeQuestionForm(interaction, session, vm, forms, disabled)
        else ZcodeSimpleInteraction(interaction, session, vm, disabled, forms.getOrPut(interaction.id) { InteractionFormState() })
        if (interaction.id in state.interactSubmitting) {
            Text("应答已发送，等待确认；结果未知时不会自动重发。", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            TextButton({ vm.releaseInteraction(session, interaction.id) }, enabled = interaction.id in state.interactReleasable) { Text("已核对，重新填写") }
        }
    }
}

/** Pause display follows the server snapshot only: snoozed is shown after confirmation,
 *  a failed/unconfirmed snooze keeps the real countdown with a hint. */
@Composable
private fun ZcodeAutoResolutionLine(interaction: ZInteraction, state: ZcodeState) {
    val unconfirmed = interaction.id in state.snoozeUnconfirmed
    val auto = interaction.autoResolution ?: return
    val now by produceState(System.currentTimeMillis(), auto) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val text = when {
        auto.state == "snoozed" -> "已暂停自动结束"
        auto.state == "visibleCountdown" && auto.deadlineAt > 0 -> {
            interactionCountdown(auto.deadlineAt, now)
        }
        else -> return
    }
    Text(text + if (unconfirmed && auto.state != "snoozed") " · 暂停尚未确认" else "",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
}

private fun zcodeUnsupportedReason(reason: String) = when (reason) {
    "unsupported_kind" -> "该交互类型暂不能在 App 内应答，请在桌面端处理。"
    "oversized" -> "该交互内容超出 App 可处理的大小，请在桌面端处理。"
    "invalid_id" -> "该交互的标识超出 App 可处理范围，请在桌面端处理。"
    else -> "该交互内容格式不受支持，请在桌面端处理。"
}

/** Simple interactions (permission or option-only userInput): optionId / freeText answers. */
private fun interactionKeyboardOptions(sensitive: Boolean) = if (sensitive)
    KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
else KeyboardOptions.Default

@Composable
private fun ZcodeSimpleInteraction(interaction: ZInteraction, session: ZSession, vm: ZcodeViewModel, disabled: Boolean, form: InteractionFormState) {
    if (interaction.summary.isNotBlank()) Text(interaction.summary, fontSize = 13.sp)
    if (interaction.prompt.isNotBlank()) Text(interaction.prompt, fontSize = 13.sp)
    interaction.options.forEach { option ->
        TextButton({ vm.interactOption(session, interaction, option.optionId) }, enabled = !disabled && option.optionId.isNotBlank()) {
            Text(option.label.ifBlank { option.optionId },
                color = if (option.kind == "deny") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
    if (interaction.freeText) {
        OutlinedTextField(form.text, { form.text = it; vm.noteInteractionTouch(session, interaction) },
            Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.noteInteractionTouch(session, interaction) }, enabled = !disabled, label = { Text(if (interaction.sensitive) "输入（敏感内容不落盘）" else "输入回答") },
            visualTransformation = if (interaction.sensitive) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = interactionKeyboardOptions(interaction.sensitive))
        TextButton({ vm.interactFreeText(session, interaction, form.text) }, enabled = !disabled && form.text.isNotBlank()) { Text("发送") }
    }
}

/** AskUserQuestion form: per-question drafts, multi-select toggles, custom answers,
 *  next/submit navigation — switching questions only updates local drafts. */
@Composable
private fun ZcodeQuestionForm(
    interaction: ZInteraction, session: ZSession, vm: ZcodeViewModel,
    forms: SnapshotStateMap<String, InteractionFormState>, disabled: Boolean
) {
    val form = forms.getOrPut(interaction.id) { InteractionFormState() }
    if (!form.seeded && form.drafts.isEmpty() && interaction.answerDrafts.isNotEmpty()) {
        form.seeded = true
        // First display seeds from upstream drafts; later edits are never overwritten.
        form.drafts.putAll(initialInteractionDrafts(interaction))
    }
    val questions = interaction.questions
    val index = if (form.index in questions.indices) form.index
        else interaction.currentQuestionIndex.coerceIn(0, questions.size - 1)
    val question = questions[index]
    val draft = form.drafts[question.question] ?: ZFormDraft()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${index + 1}/${questions.size} · ${question.header}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(question.question, fontSize = 13.sp)
        question.options.forEach { option ->
            val selected = option.value in draft.selectedValues
            Column {
                FilterChip(selected = selected, enabled = !disabled,
                    onClick = {
                        vm.noteInteractionTouch(session, interaction)
                        val base = form.drafts[question.question] ?: ZFormDraft()
                        form.drafts[question.question] = if (question.multiSelect) {
                            val values = if (selected) base.selectedValues - option.value else base.selectedValues + option.value
                            base.copy(selectedValues = values)
                        } else base.copy(selectedValues = listOf(option.value), customAnswer = "")
                    },
                    label = { Text(option.label.ifBlank { option.value }) })
                if (option.description.isNotBlank() && selected)
                    Text(option.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp))
            }
        }
        OutlinedTextField(draft.customAnswer, { value ->
            vm.noteInteractionTouch(session, interaction)
            form.drafts[question.question] = draft.copy(customAnswer = value, selectedValues = if (question.multiSelect) draft.selectedValues else emptyList())
        }, Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.noteInteractionTouch(session, interaction) },
            label = { Text("自定义回答") }, enabled = !disabled,
            visualTransformation = if (interaction.sensitive) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = interactionKeyboardOptions(interaction.sensitive))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (index > 0) TextButton({ form.index = index - 1 }, enabled = !disabled) { Text("上一题") }
            if (index < questions.size - 1) TextButton({ form.index = index + 1 }, enabled = !disabled) { Text("下一题") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ vm.interactForm(session, interaction, questions, form.drafts.toMap()) },
                enabled = !disabled && interactionFormAnswered(questions, form.drafts.toMap())) { Text("提交") }
            TextButton({ vm.interactDecline(session, interaction) }, enabled = !disabled) {
                Text("跳过", color = MaterialTheme.colorScheme.error)
            }
            TextButton({ vm.interactCancel(session, interaction) }, enabled = !disabled) { Text("取消") }
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
internal fun ZcodeTimeline(state: ZcodeState, vm: ZcodeViewModel, modifier: Modifier, history: () -> Unit, retry: () -> Unit, detail: (String, String) -> Unit) {
    val queueContext = vm.queueContext(state)
    ChatTimeline(state.selected?.id.orEmpty(), "ZCode", state.items, state.outgoing, state.queue,
        state.beforeRow != null, state.historyLoading, state.historyError, state.connected, state.busy,
        modifier, history, retry, detail,
        turnSummary = state.turn.summary(),
        onTurnDiff = if (state.diffEnabled && !state.turn?.entityId.orEmpty().isNullOrBlank() && state.logEpoch.isNotBlank())
            ({ vm.loadTurnDiff() }) else null,
        removeQueued = if (state.queueOpsEnabled) ({ vm.removeQueued(it) }) else null,
        promoteQueued = if (state.queueOpsEnabled) ({ vm.promoteQueued(it) }) else null,
        dismissFailed = vm::dismissFailed,
        editQueued = if (state.queueOpsEnabled) ({ queued, text -> vm.editQueued(queued, text, queueContext) }) else null)
}
