package com.gmch.codexremote.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gmch.codexremote.*
import com.gmch.codexremote.engines.contract.*

internal val LocalEngineDescriptors = staticCompositionLocalOf<List<EngineDescriptor>> { emptyList() }

@Composable
internal fun EngineApp(app: AppViewModel, engines: EngineUiRegistry) {
    val engine by app.engine.collectAsStateWithLifecycle()
    val env by app.environment.state.collectAsStateWithLifecycle()
    val host = engines.runtime[engine]
    val connection by host.connection.collectAsStateWithLifecycle()
    val pages = rememberSaveableStateHolder()
    var details by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalEngineDescriptors provides engines.runtime.descriptors) {
        HostTrustDialog(connection.challenge,
            trust = { challenge -> app.trustHost(engine, challenge, connection.environmentRevision) },
            cancel = host::dismissChallenge)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            WorkspaceError(env.error, app.environment::dismissError) { _, text -> details = text }
            Box(Modifier.weight(1f)) {
                pages.SaveableStateProvider("${env.environment.profile.resumeIdentity}:$engine") {
                    engines.Content(engine, app)
                }
            }
        }
    }
    details?.let { text -> AlertDialog(onDismissRequest = { details = null }, title = { Text("连接配置") },
        text = { SelectionContainer { Text(text) } }, confirmButton = { TextButton({ details = null }) { Text("关闭") } }) }
}
