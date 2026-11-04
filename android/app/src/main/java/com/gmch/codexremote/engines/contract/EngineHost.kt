package com.gmch.codexremote.engines.contract

import com.gmch.codexremote.HostChallenge
import kotlinx.coroutines.flow.StateFlow

/** App-facing lifecycle port. Session/turn protocols remain adapter-owned. */
internal interface EngineHost {
    val descriptor: EngineDescriptor
    val connection: StateFlow<EngineConnection>
    fun enter()
    fun leave()
    fun keepConnection()
    fun background()
    fun connect()
    fun disconnect()
    fun prepareEnvironmentChange()
    fun reloadEnvironment()
    fun dismissError()
    fun sessionClient(): EngineClient? = null
    fun dismissChallenge()
}

internal data class EngineDescriptor(val id: String, val label: String)
internal data class EngineConnection(
    val connected: Boolean = false, val busy: Boolean = false,
    val status: String = "尚未连接", val error: String? = null,
    val challenge: HostChallenge? = null, val environmentRevision: Long = 0,
    val version: String = "",
    // Increment when replacing the transport, even if a fast reconnect conflates connected states.
    val connectionRevision: Long = 0,
)

internal class EngineRegistry(hosts: List<EngineHost>) {
    val hosts = hosts.toList()
    val descriptors get() = hosts.map { it.descriptor }
    private val byId = hosts.associateBy { it.descriptor.id }
    init {
        require(hosts.isNotEmpty()) { "至少注册一个引擎" }
        require(byId.size == hosts.size) { "引擎 ID 重复" }
        require(hosts.all { it.descriptor.id.matches(Regex("[a-z][a-z0-9_-]*")) }) { "引擎 ID 无效" }
    }
    operator fun get(id: String): EngineHost = byId[id] ?: error("引擎未注册：$id")
    fun sessionClient(id: String): EngineClient? = get(id).sessionClient()?.also {
        check(it.origin.engine == id) { "引擎注册与会话客户端不匹配" }
    }
    fun contains(id: String) = id in byId
    fun restoredSelection(saved: String) = saved.takeIf(::contains) ?: hosts.first().descriptor.id
}
