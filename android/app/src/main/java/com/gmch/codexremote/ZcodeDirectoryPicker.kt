package com.gmch.codexremote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ZcodeDirectoryPicker(state: ZcodeBrowserState, vm: ZcodeViewModel) {
    if (!state.open) return
    var showManual by remember { mutableStateOf(false) }
    var showFullPath by remember { mutableStateOf(false) }
    // Filter is view-only: the server always owns which directories exist.
    val shown = remember(state.entries, state.filter) {
        if (state.filter.isBlank()) state.entries
        else state.entries.filter { it.name.contains(state.filter, ignoreCase = true) }
    }
    // Read errors block adding (the listing may be stale) but never navigation: the manual
    // path field stays usable to recover. Add errors only block the add itself.
    val canNavigate = !state.loading && !state.adding
    val canAdd = canNavigate && state.readError == null && state.current.isNotBlank()
    // Full-screen dialog: AlertDialog windows do not resize with the IME, which buried the
    // footer under the keyboard. Here the window spans the screen, imePadding() shrinks the
    // usable height while typing, the content region scrolls, and the footer stays clickable.
    Dialog(onDismissRequest = vm::closeBrowser, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable(scrimInteraction, indication = null) { vm.closeBrowser() }
            .statusBarsPadding().windowInsetsPadding(WindowInsets.displayCutout)
            .imePadding().navigationBarsPadding()) {
            BoxWithConstraints(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 20.dp)) {
                Surface(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {},
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp) {
                    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(20.dp)) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            Text("添加项目目录", style = MaterialTheme.typography.titleLarge)
                            if (state.loading) LinearProgressIndicator(
                                Modifier.fillMaxWidth().padding(top = 10.dp))
                            Text(if (state.loading) "读取中：${state.requested ?: "主目录"}"
                                else "当前：${state.current.ifBlank { "（未获取）" }}",
                                Modifier.fillMaxWidth().padding(top = 12.dp)
                                    .clickable(enabled = canNavigate && state.current.isNotBlank()) {
                                        showFullPath = !showFullPath
                                    },
                                fontSize = 11.sp, maxLines = if (showFullPath) 6 else 1,
                                overflow = TextOverflow.Ellipsis)
                            state.readError?.let {
                                Text("读取 ${state.requested ?: "主目录"} 失败：$it",
                                    Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                TextButton({ vm.retryBrowser() }, enabled = !state.adding) { Text("重试读取") }
                            }
                            state.addError?.let {
                                Text("添加 ${state.current} 失败：$it",
                                    Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                TextButton(vm::confirmBrowserAdd, enabled = canAdd) { Text("重试添加") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ vm.browseBrowser(state.parent) },
                                    enabled = canNavigate && state.hasParent) { Text("返回上级") }
                                SelectionChip(state.showHidden, { vm.browserShowHidden(!state.showHidden) }, "隐藏目录", enabled = !state.adding)
                            }
                            OutlinedTextField(state.filter, vm::browserFilter, Modifier.fillMaxWidth(),
                                singleLine = true, placeholder = { Text("按名称筛选") })
                            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 300.dp).padding(top = 4.dp)) {
                                when {
                                    state.entries.isEmpty() ->
                                        Text("没有可进入的子目录。", Modifier.align(Alignment.Center), fontSize = 12.sp)
                                    shown.isEmpty() ->
                                        Text("没有匹配“${state.filter}”的目录。",
                                            Modifier.align(Alignment.Center), fontSize = 12.sp)
                                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        items(shown, key = { it.path }) { entry ->
                                            Text(entry.name, Modifier.fillMaxWidth()
                                                .clickable(enabled = canNavigate) { vm.browseBrowser(entry.path) }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            TextButton({ showManual = !showManual }, enabled = canNavigate) {
                                Text(if (showManual) "收起手动输入" else "手动输入绝对路径")
                            }
                            if (showManual) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(state.manual, vm::browserManual, Modifier.weight(1f),
                                        singleLine = true, placeholder = { Text("/absolute/path") })
                                    TextButton({ vm.browseBrowser(state.manual) },
                                        enabled = canNavigate && state.manual.startsWith('/')) { Text("跳转") }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(vm::closeBrowser) { Text("取消") }
                            Button(vm::confirmBrowserAdd, enabled = canAdd) {
                                Text(if (state.adding) "添加中…" else "添加当前目录")
                            }
                        }
                    }
                }
            }
        }
    }
}
