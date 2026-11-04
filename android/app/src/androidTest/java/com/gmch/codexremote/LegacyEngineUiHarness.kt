package com.gmch.codexremote

import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import com.gmch.codexremote.app.AppViewModel
import com.gmch.codexremote.app.LocalEngineDescriptors
import com.gmch.codexremote.app.EngineApp as RegisteredEngineApp
import com.gmch.codexremote.engines.*
import kotlinx.coroutines.cancel

/** Keep existing isolated UI fixtures, but route them through the production app
 * coordinator/registry. This overload exists only in androidTest, never in the APK. */
@Composable
internal fun EngineApp(codex: RemoteViewModel, zcode: ZcodeViewModel) {
    val app = remember(codex) { AppViewModel(codex.getApplication()) }
    val registry = remember(codex, zcode) { builtinEngines(app.viewModelScope, codex, zcode) }
    val legacy by codex.state.collectAsState()
    remember(app, registry) {
        app.bind(registry.runtime, legacyEnvironmentVerifier)
        app.selectEngine(codex.state.value.engine)
    }
    LaunchedEffect(legacy.engine) { app.selectEngine(legacy.engine) }
    DisposableEffect(app) {
        app.start()
        onDispose { app.stop(); app.viewModelScope.cancel() }
    }
    RegisteredEngineApp(app, registry)
}

@Composable
internal fun RemoteApp(vm: RemoteViewModel) {
    val app = remember(vm) { AppViewModel(vm.getApplication()) }
    val zcode = remember(vm) { ZcodeViewModel(vm.getApplication()) }
    val registry = remember(vm) { builtinEngines(app.viewModelScope, vm, zcode) }
    remember(app) { app.bind(registry.runtime, legacyEnvironmentVerifier) }
    DisposableEffect(app) {
        app.start()
        onDispose { app.stop(); app.viewModelScope.cancel(); zcode.viewModelScope.cancel() }
    }
    CompositionLocalProvider(LocalEngineDescriptors provides registry.runtime.descriptors) { RemoteApp(vm, app) }
}
