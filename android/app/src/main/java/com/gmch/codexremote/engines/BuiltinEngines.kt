package com.gmch.codexremote.engines

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.gmch.codexremote.*
import com.gmch.codexremote.app.AppViewModel
import com.gmch.codexremote.app.EngineUiRegistry
import com.gmch.codexremote.app.EngineUiEntry
import com.gmch.codexremote.app.EnvironmentVerifier
import com.gmch.codexremote.engines.contract.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Native construction stays in the composition root; Activity only supplies its owner. */
internal fun builtinEngines(owner: ViewModelStoreOwner, scope: CoroutineScope): EngineUiRegistry {
    val models = ViewModelProvider(owner)
    return builtinEngines(scope, models[RemoteViewModel::class.java], models[ZcodeViewModel::class.java])
}

/** Composition root only: shared app/controller code never imports these ViewModels. */
internal fun builtinEngines(scope: CoroutineScope, codex: RemoteViewModel, zcode: ZcodeViewModel) = EngineUiRegistry(listOf(
    EngineUiEntry(CodexHost(codex, scope)) { app -> RemoteApp(codex, app) },
    EngineUiEntry(ZcodeHost(zcode, scope)) { app -> ZcodeScreen(zcode, app) },
))

private class CodexHost(private val vm: RemoteViewModel, scope: CoroutineScope) : EngineHost {
    override val descriptor = EngineDescriptor("codex", "Codex")
    private fun status(s: UiState) = EngineConnection(s.connected, s.busy, s.status, s.error, s.challenge, vm.environmentRevision, s.version, vm.connectionRevision)
    override val connection = vm.state.map(::status).stateIn(scope, SharingStarted.Eagerly, status(vm.state.value))
    override fun enter() = vm.foreground()
    override fun leave() = vm.leave()
    override fun keepConnection() = vm.keepConnection()
    override fun background() = vm.background()
    override fun connect() = vm.connect()
    override fun disconnect() = vm.disconnectManually()
    override fun prepareEnvironmentChange() = vm.environmentChanging()
    override fun reloadEnvironment() { vm.reloadEnvironment() }
    override fun sessionClient() = if (vm.state.value.connected) vm.sessionClient() else null
    override fun dismissError() = vm.dismissError()
    override fun dismissChallenge() = vm.cancelTrust()
}
private class ZcodeHost(private val vm: ZcodeViewModel, scope: CoroutineScope) : EngineHost {
    override val descriptor = EngineDescriptor("zcode", "ZCode")
    private fun status(s: ZcodeState) = EngineConnection(s.connected, s.busy,
        if (s.challenge != null) "核对 SSH 主机指纹" else if (s.connected) "已连接" else if (s.busy) "正在连接…" else "尚未连接",
        s.error, s.challenge, vm.environmentRevision, connectionRevision = vm.connectionRevision)
    override val connection = vm.state.map(::status).stateIn(scope, SharingStarted.Eagerly, status(vm.state.value))
    override fun enter() = vm.enter()
    override fun leave() = vm.leave()
    override fun keepConnection() = vm.keepConnection()
    override fun background() = vm.background()
    override fun connect() = vm.connect()
    override fun disconnect() = vm.disconnectManually()
    override fun prepareEnvironmentChange() = vm.environmentChanging()
    override fun reloadEnvironment() { vm.reloadEnvironment() }
    override fun sessionClient() = if (vm.state.value.connected) vm.sessionClient() else null
    override fun dismissError() = vm.dismissError()
    override fun dismissChallenge() = vm.dismissChallenge()
}

/** Existing pairing tickets provision a Codex daemon endpoint. Keep that legacy
 * verification here until the remote pairing protocol has engine-neutral discovery. */
internal suspend fun verifyLegacyEnvironment(profile: ConnectionProfile, secrets: SecretReader) {
    val candidate = SshTransport()
    var rpc: RpcClient? = null
    try {
        candidate.connect(profile, secrets, "", "", com.gmch.codexremote.engines.BuiltinEndpoints.Codex)
        val client = RpcClient(candidate.input, candidate.output, {}, {}); rpc = client
        client.initialize()
    } finally { rpc?.close(); candidate.close() }
}
internal val legacyEnvironmentVerifier = EnvironmentVerifier(::verifyLegacyEnvironment)
