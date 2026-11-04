package com.gmch.codexremote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmch.codexremote.*
import com.gmch.codexremote.engines.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = EnvironmentStore.forApp(app)
    private var verifier: EnvironmentVerifier? = null
    private var coordinator: AppCoordinator? = null
    private val mutableEngine = MutableStateFlow(store.selectedEngine())
    val engine = mutableEngine.asStateFlow()
    private var selectionJob: Job? = null
    private var secretsExpiry: Job? = null
    private var started = false
    val environment = EnvironmentController(app, viewModelScope, store,
        EnvironmentVerifier { profile, secrets -> checkNotNull(verifier) { "应用尚未装配连接验证器" }.verify(profile, secrets) },
        beforeChange = { coordinator?.beforeEnvironmentChange() },
        changed = { reconnect -> coordinator?.environmentChanged(reconnect && environmentSaved()) })
    private fun environmentSaved() = store.refresh().saved

    fun bind(registry: EngineRegistry, verifier: EnvironmentVerifier) {
        this.verifier = verifier
        if (coordinator?.registry === registry) return
        coordinator?.stop()
        selectionJob?.cancel()
        val next = AppCoordinator(registry, mutableEngine.value, store::selectEngine)
        coordinator = next
        mutableEngine.value = next.selected.value
        selectionJob = viewModelScope.launch { next.selected.collect { mutableEngine.value = it } }
        if (started) next.start()
    }
    fun selectEngine(id: String) {
        if (!environment.state.value.busy) runCatching { coordinator?.select(id) }.onFailure(environment::reportError)
    }
    fun start() {
        started = true
        secretsExpiry?.cancel(); secretsExpiry = null
        val previous = environment.state.value.environment.revision
        val readable = environment.refresh()
        if (!readable || environment.state.value.environment.revision != previous) {
            coordinator?.beforeEnvironmentChange()
            coordinator?.environmentChanged(readable && environmentSaved())
        }
        coordinator?.start()
    }
    fun stop() {
        started = false
        coordinator?.stop()
        secretsExpiry?.cancel()
        secretsExpiry = viewModelScope.launch { delay(BACKGROUND_GRACE_MS); store.clearPassphrases() }
    }
    fun trustHost(engineId: String, expected: HostChallenge, revision: Long) {
        if (environment.state.value.busy) return
        val c = coordinator ?: return
        val connection = c.current.connection.value
        if (c.selected.value != engineId || connection.challenge != expected || expected.changed ||
            revision != connection.environmentRevision || revision != store.refresh().revision) return
        environment.trust(expected)
    }
    override fun onCleared() {
        store.clearPassphrases()
        super.onCleared()
    }

}
