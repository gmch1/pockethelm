package com.gmch.codexremote.app

import com.gmch.codexremote.engines.contract.EngineRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread owned. No concrete engine names and no session protocol knowledge. */
internal class AppCoordinator(val registry: EngineRegistry, savedEngine: String,
    private val persistEngine: (String) -> Unit) {
    private val mutable = MutableStateFlow(registry.restoredSelection(savedEngine))
    val selected = mutable.asStateFlow()
    var visible = false
        private set
    val current get() = registry[mutable.value]

    fun select(id: String) {
        require(registry.contains(id)) { "引擎未注册：$id" }
        if (id == mutable.value) return
        // Persistence first: a failed save must not leave UI and runtime ownership split.
        persistEngine(id)
        current.leave()
        mutable.value = id
        if (visible) current.enter()
    }
    fun start() {
        if (visible) return
        visible = true
        registry.hosts.forEach { it.keepConnection() }
        current.enter()
    }
    fun stop() {
        if (!visible) return
        visible = false
        registry.hosts.forEach { it.background() }
    }
    fun beforeEnvironmentChange() { registry.hosts.forEach { it.prepareEnvironmentChange() } }
    fun environmentChanged(reconnect: Boolean = true) {
        registry.hosts.forEach { it.reloadEnvironment() }
        if (visible && reconnect) current.connect()
    }
}
