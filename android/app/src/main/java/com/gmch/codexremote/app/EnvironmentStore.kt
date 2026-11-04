package com.gmch.codexremote.app

import android.app.Application
import com.gmch.codexremote.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal data class EnvironmentSnapshot(val profile: ConnectionProfile = ConnectionProfile(), val saved: Boolean = false,
    val targetKeyName: String = "未导入", val jumpKeyName: String = "未导入", val revision: Long = 0)

/** Existing encrypted keys/formats stay intact. Session data is not owned here. */
internal class EnvironmentStore private constructor(app: Application) {
    val vault = Vault(app)
    private var snapshot = EnvironmentSnapshot()
    var loadError: String? = null
        private set
    var targetPassphrase = ""
        private set
    var jumpPassphrase = ""
        private set
    init {
        runCatching {
            vault.put("device-identity", null)
            val old = vault.get("profile")?.let { ConnectionProfile.from(org.json.JSONObject(it)) }
            if (old?.useDeviceKey == true && old.configVersion != 2) vault.put("profile", null)
            refresh()
        }.onFailure { loadError = it.message ?: "无法读取连接配置" }
    }
    fun refresh(): EnvironmentSnapshot {
        val raw = vault.get("profile")
        val next = EnvironmentSnapshot(raw?.let { ConnectionProfile.from(org.json.JSONObject(it)) } ?: ConnectionProfile(),
            raw != null, vault.get("target-key-name") ?: "未导入", vault.get("jump-key-name") ?: "未导入", snapshot.revision)
        if (next != snapshot) snapshot = next.copy(revision = snapshot.revision + 1)
        loadError = null
        return snapshot
    }
    fun save(profile: ConnectionProfile, targetPass: String, jumpPass: String) {
        profile.validate()
        vault.put("profile", profile.json().toString())
        targetPassphrase = targetPass; jumpPassphrase = jumpPass
        snapshot = refresh().copy(revision = snapshot.revision + 1)
    }
    fun credentialsChanged() { snapshot = refresh().copy(revision = snapshot.revision + 1) }
    fun clearPassphrases() { targetPassphrase = ""; jumpPassphrase = "" }
    fun selectedEngine() = runCatching { vault.get("ui:engine").orEmpty() }.getOrDefault("")
    fun selectEngine(id: String) { vault.put("ui:engine", id) }
    companion object {
        private val stores = WeakHashMap<Application, WeakReference<EnvironmentStore>>()
        fun forApp(app: Application): EnvironmentStore = synchronized(stores) {
            stores[app]?.get() ?: EnvironmentStore(app).also { stores[app] = WeakReference(it) }
        }
    }
}
