package com.gmch.codexremote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun percent(value: Double) = String.format(Locale.getDefault(), "%.0f%%", value)
private fun moment(millis: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionControls(state: UiState, vm: RemoteViewModel) {
    val id = state.selected?.id ?: return
    var showSettings by remember(id) { mutableStateOf(false) }
    var showQuota by remember(id) { mutableStateOf(false) }
    val settings = state.modelSettings
    val model = state.models.firstOrNull { it.id == settings?.model }
    fun settingsPanel() { showSettings = true; vm.prepareModelSettings(id) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(::settingsPanel, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(36.dp)) {
            Text((model?.name ?: settings?.model?.ifBlank { null } ?: "模型").take(24) + " ▾", fontSize = 11.sp, maxLines = 1)
        }
        TextButton(::settingsPanel, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(36.dp)) {
            Text("思考：${effortLabel(settings?.effort)} ▾", fontSize = 11.sp)
        }
        TextButton(::settingsPanel, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(36.dp)) {
            Text(when {
                settings?.confirmed != true -> "速度待同步"
                settings.tier == "default" -> "标准 ▾"
                else -> (model?.speeds?.firstOrNull { it.id == settings.tier }?.name ?: settings.tier) + " ▾"
            }, fontSize = 11.sp)
        }
        TextButton({ showQuota = true; vm.refreshQuota() }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(36.dp)) {
            Text(when {
                !state.connected && state.quota != null -> "额度 · 离线"
                state.quotaError != null -> "额度 · 待刷新"
                state.quota?.blocked == true -> "额度受限"
                state.quota?.remaining != null -> "额度 ${percent(state.quota.remaining!!)}"
                state.quotaLoading -> "额度加载中"
                else -> "额度 ▾"
            }, fontSize = 11.sp)
        }
    }
    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("模型与速度", style = MaterialTheme.typography.titleLarge)
            Text(if (state.selected.runtime == "active") "当前任务保持原设置，更改从后续轮次生效，包括排队任务。" else "仅修改当前会话，后续轮次使用新设置。", fontSize = 12.sp)
            if (!state.connected) Text("当前离线，重连后可修改设置。", color = MaterialTheme.colorScheme.error)
            state.settingsNotice?.let { Text(it, fontSize = 12.sp) }
            state.modelsError?.let { Text("模型列表更新失败：$it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (state.modelsLoading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (settings?.confirmed == true && state.models.isNotEmpty()) {
                SettingsEditor(id, settings, state.models, state.connected && !state.busy && !state.modelsLoading) { choice ->
                    vm.updateModelSettings(id, choice); showSettings = false
                }
            } else {
                Text("读取开发机的当前设置后才能修改。", fontSize = 13.sp)
            }
            TextButton({ vm.prepareModelSettings(id, force = true) }, enabled = state.connected && !state.busy && !state.modelsLoading) { Text("刷新模型与设置") }
        }
    }
    if (showQuota) ModalBottomSheet(onDismissRequest = { showQuota = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("剩余额度", style = MaterialTheme.typography.titleLarge)
            Text("开发机当前账号 · 各客户端共享", fontSize = 12.sp)
            if (state.quotaLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.connected) Text("离线缓存，重连后刷新。", fontSize = 12.sp)
            state.quotaError?.let { Text("暂时无法读取额度：$it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            val quota = state.quota
            if (quota == null) Text(if (state.quotaLoading) "正在读取…" else "暂无额度数据，未提供数据不代表额度用尽。", fontSize = 13.sp)
            else {
                Text("更新时间：${moment(quota.updatedAt)}", fontSize = 12.sp)
                if (quota.ordinaryAllowed == false) Text("服务端当前限制普通额度使用，请以账号状态为准。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (quota.buckets.isEmpty()) Text("服务端未提供额度窗口。")
                quota.buckets.forEach { bucket ->
                    HorizontalDivider()
                    Text(bucket.name, style = MaterialTheme.typography.titleMedium)
                    if (bucket.blocked) Text("此额度当前受限", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (bucket.windows.isEmpty()) Text("未提供周期额度", fontSize = 13.sp)
                    bucket.windows.forEach { window ->
                        Text("${window.label} · 剩余 ${percent(window.remaining)}", fontSize = 14.sp)
                        LinearProgressIndicator(progress = { (window.remaining / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(window.resetsAt?.let {
                            "重置时间：${moment(it * 1000)}" + if (it * 1000 <= System.currentTimeMillis()) "（待刷新确认）" else ""
                        } ?: "重置时间未提供", fontSize = 11.sp)
                    }
                    if (bucket.unlimitedCredits) Text("点数余额：不限额", fontSize = 13.sp)
                    else bucket.credits?.let { balance ->
                        val number = balance.toBigDecimalOrNull()?.setScale(2, RoundingMode.DOWN)?.stripTrailingZeros()?.toPlainString() ?: balance.take(48)
                        Text("点数余额：$number", fontSize = 13.sp)
                    }
                }
            }
            Button({ vm.refreshQuota() }, enabled = state.connected && !state.quotaLoading, modifier = Modifier.fillMaxWidth()) { Text("刷新额度") }
        }
    }
}

@Composable
private fun SettingsEditor(id: String, current: ModelSettings, models: List<ModelOption>, enabled: Boolean, onSave: (ModelSettings) -> Unit) {
    var draft by remember(id) { mutableStateOf(current) }
    var expandModels by remember { mutableStateOf(false) }
    val option = models.firstOrNull { it.id == draft.model }
    val effective = draft.copy(effort = draft.effort ?: option?.defaultEffort)
    Box {
        OutlinedButton({ expandModels = true }, Modifier.fillMaxWidth(), enabled = enabled) {
            Text((option?.name ?: draft.model) + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expandModels, { expandModels = false }, Modifier.heightIn(max = 320.dp)) {
            models.forEach { model ->
                DropdownMenuItem(text = { Text(model.name) }, onClick = {
                    draft = draft.copy(model = model.id, effort = draft.effort?.takeIf { it in model.efforts } ?: model.defaultEffort,
                        tier = draft.tier.takeIf { tier -> tier == "default" || model.speeds.any { it.id == tier } } ?: "default")
                    expandModels = false
                })
            }
        }
    }
    Text("思考深度", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        option?.efforts?.forEach { effort -> FilterChip(effective.effort == effort, { draft = draft.copy(effort = effort) }, enabled = enabled, label = { Text(effortLabel(effort)) }) }
    }
    if (effective.effort == "ultra") Text("Ultra 包含自动任务委派能力。", fontSize = 12.sp)
    Text("执行速度", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(draft.tier == "default", { draft = draft.copy(tier = "default") }, enabled = enabled, label = { Text("标准") })
        option?.speeds?.forEach { speed -> FilterChip(draft.tier == speed.id, { draft = draft.copy(tier = speed.id) }, enabled = enabled, label = { Text(speed.name) }) }
    }
    Text("加速档会增加额度消耗，具体支持情况以开发机和账号为准。", fontSize = 12.sp)
    Button({ onSave(effective) }, Modifier.fillMaxWidth(), enabled = enabled && effective != current && option != null && effective.effort in option.efforts &&
        (effective.tier == "default" || option.speeds.any { it.id == effective.tier })) { Text("应用到当前会话") }
}
