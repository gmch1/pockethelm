package com.gmch.codexremote.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmch.codexremote.HostChallenge
import com.gmch.codexremote.BuildConfig
import com.gmch.codexremote.engines.contract.EngineConnection
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private val Green = Color(0xff226f50)
private val Muted = Color(0xff58685e)

@Composable
internal fun HostTrustDialog(challenge: HostChallenge?, trust: (HostChallenge) -> Unit, cancel: () -> Unit) {
    challenge?.let { challenge ->
        AlertDialog(onDismissRequest = cancel, title = { Text(if (challenge.changed) "主机身份已改变" else "首次连接主机") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(challenge.host, fontSize = 13.sp)
                Text(challenge.algorithm, color = Muted)
                SelectionContainer { Text(challenge.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text(if (challenge.changed) "当前指纹与已保存记录不符，连接已停止。请从可信渠道核对服务器身份。" else "核对服务器指纹后保存。之后每次连接会自动验证该身份。", fontSize = 13.sp)
            } }, confirmButton = { if (!challenge.changed) TextButton(onClick = { trust(challenge) }) { Text("指纹一致，保存并连接") } },
            dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
    }
}

@Composable
internal fun ConnectionScreen(environment: EnvironmentUiState, connection: EngineConnection, vm: EnvironmentController,
    reconnect: () -> Unit, disconnect: () -> Unit) {
    val state = environment.environment
    val busy = environment.busy || connection.busy
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
                Text(if (environment.busy) environment.status else connection.status, color = Muted, fontSize = 12.sp)
                Text("进入前台刷新 · 默认 YOLO", color = Green, fontSize = 11.sp)
            }
        }
        Text("扫码连接开发机", style = MaterialTheme.typography.titleLarge)
        Text("扫描电脑生成的连接码，自动完成连接配置。以后打开 App 即可使用。", color = Muted, fontSize = 13.sp)
        Button({
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描电脑上的 PocketHelm 连接码").setBeepEnabled(false).setOrientationLocked(false))
        }, Modifier.fillMaxWidth(), enabled = !busy, contentPadding = PaddingValues(15.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("扫码连接")
        }
        OutlinedButton({ qrImage.launch("image/*") }, Modifier.fillMaxWidth(), enabled = !busy) { Text("从二维码图片连接") }
        if (state.saved) {
            OutlinedButton(reconnect, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (connection.connected) "刷新连接" else "连接已配对的开发机") }
        }
        if (connection.connected) OutlinedButton(disconnect, Modifier.fillMaxWidth()) { Text("断开连接") }
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
        Button({ vm.saveProfile(profile, targetPass, jumpPass); targetPass = ""; jumpPass = "" }, Modifier.fillMaxWidth(), enabled = !busy, contentPadding = PaddingValues(14.dp)) { Text("保存并连接") }
        if (connection.version.isNotBlank()) Text(connection.version, fontSize = 10.sp, color = Muted)
        }
        Text("PocketHelm ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label, fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(10.dp))
}
