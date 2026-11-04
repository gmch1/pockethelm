package com.gmch.codexremote.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.gmch.codexremote.*
import com.gmch.codexremote.application.EngineSessionController
import com.gmch.codexremote.engines.contract.*

/** Default conversation UI for a newly registered provider. Existing rich native
 * screens remain optional UI extensions rather than requirements for another CLI. */
internal fun genericEngineEntry(host: EngineHost, controller: EngineSessionController) = EngineUiEntry(host) { app ->
    val connection by host.connection.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(connection.connected, connection.environmentRevision, connection.connectionRevision) {
        controller.bind(host.sessionClient())
        if (connection.connected) controller.refresh()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, connection.connected, state.selected) {
        if (connection.connected && state.selected != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    delay(3_000)
                    if (!controller.state.value.working && !controller.state.value.loading)
                        controller.refresh(refreshChoices = false)?.join()
                }
            } finally { if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.pause() }
        }
    }
    DisposableEffect(controller) { onDispose { controller.pause() } }
    var create by remember { mutableStateOf(false) }
    var confirmUnknown by remember { mutableStateOf<PendingOperation?>(null) }
    val client = if (connection.connected) host.sessionClient() else null
    WorkspaceScaffold(state.snapshot?.title ?: host.descriptor.label, engine = host.descriptor.id to app::selectEngine,
        chat = state.selected != null, busy = state.working,
        back = controller::back,
        refresh = { if (connection.connected) controller.refresh() else host.connect() }) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(controller::dismissError) { Text("关闭提示") }
            }
            if (!connection.connected) TextButton(host::connect) { Text("连接引擎") }
            if (state.loading) Text("正在更新…")
            if (state.choices.isNotEmpty()) {
                Text("模型与来源")
                state.choices.forEach { choice ->
                    TextButton({ controller.choose(choice) }, enabled = !state.working) {
                        Text((if (state.choice == choice) "✓ " else "") + choice.label)
                    }
                }
            }
            if (state.selected == null) {
                if (client != null && EngineOperation.Create in client.operations)
                    Button({ create = true }, enabled = state.choicesLoaded && !state.working) { Text("新建会话") }
                state.sessions.forEach { session -> TextButton({ controller.open(session.ref) }) { Text(session.title) } }
                if (state.sessionCursor != null) TextButton({ controller.moreSessions() }) { Text("加载更多会话") }
            } else {
                if (state.snapshot == null) Text("会话尚未加载，请连接并刷新")
                if (state.readingOlder) Text("正在查看较早消息，点击刷新返回最新内容")
                state.snapshot?.history?.next?.let { TextButton({ controller.moreHistory() }) { Text("加载更早消息") } }
                state.snapshot?.history?.items?.forEach { item -> Text(item.text, Modifier.padding(vertical = 6.dp)) }
                state.outgoing.forEach { row -> Text("${row.text}\n${outgoingStatusLabel(row)}") }
                OutlinedTextField(state.draft, controller::draft, Modifier.fillMaxWidth(), label = { Text("消息") })
                if (client != null && EngineOperation.Send in client.operations)
                    Button({ controller.send() }, enabled = !state.working && state.storeHealthy && state.choicesLoaded && state.draft.isNotBlank()) { Text("发送") }
                if (client != null && EngineOperation.Interrupt in client.operations && state.snapshot?.canInterrupt == true)
                    TextButton({ controller.interrupt() }, enabled = !state.working) { Text("停止执行") }
            }
            state.pending.filter { it.kind == PendingKind.Create || it.session == state.selected }.forEach { pending ->
                Text("操作结果待核对，不会自动重发")
                if (pending.kind == PendingKind.Create && pending.session != null)
                    TextButton({ controller.open(pending.session) }) { Text("打开已创建的会话核对") }
                TextButton({ controller.refresh() }) { Text("刷新核对") }
                TextButton({ confirmUnknown = pending }, enabled = !state.working) { Text("我已手动核对") }
            }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("选择项目") },
        text = { Column { state.projects.forEach { project ->
            TextButton({ create = false; controller.create(project) }, enabled = project.roots.size == 1) { Text(project.title) }
        } } }, confirmButton = { TextButton({ create = false }) { Text("关闭") } })
    confirmUnknown?.let { pending -> AlertDialog(onDismissRequest = { confirmUnknown = null }, title = { Text("解除待确认保护") },
        text = { Text("确认已经检查引擎中的实际结果。解除保护不会自动重发操作。") },
        confirmButton = { TextButton({ controller.acknowledgeUnknown(pending.id); confirmUnknown = null }) { Text("已核对") } },
        dismissButton = { TextButton({ confirmUnknown = null }) { Text("取消") } }) }
}
