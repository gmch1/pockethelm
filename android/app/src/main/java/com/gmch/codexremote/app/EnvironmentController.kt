package com.gmch.codexremote.app

import android.app.Application
import android.net.Uri
import android.graphics.BitmapFactory
import com.gmch.codexremote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface EnvironmentVerifier {
    suspend fun verify(profile: ConnectionProfile, secrets: SecretReader)
}
internal data class EnvironmentUiState(val environment: EnvironmentSnapshot, val busy: Boolean = false,
    val status: String = "尚未连接", val error: String? = null, val pairingCompleted: Int = 0)

/** Owns configuration workflows; no concrete engine ViewModel or session state. */
internal class EnvironmentController(private val app: Application, private val scope: CoroutineScope,
    val store: EnvironmentStore, private val verifier: EnvironmentVerifier,
    private val beforeChange: () -> Unit, private val changed: (Boolean) -> Unit,
    private val exchange: suspend (PairingTicket, String) -> PairingGrant = PairingClient::redeem) {
    private val mutable = MutableStateFlow(EnvironmentUiState(runCatching { store.refresh() }.getOrDefault(EnvironmentSnapshot()), error = store.loadError))
    val state = mutable.asStateFlow()
    private val actions = Mutex()
    private fun action(block: suspend () -> Unit) = scope.launch {
        actions.withLock {
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "配置更新失败") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun reportError(error: Throwable) { mutable.update { it.copy(error = error.message ?: "配置更新失败") } }
    fun refresh(): Boolean = runCatching {
        val next = store.refresh()
        mutable.update { it.copy(environment = next, error = if (next.revision != it.environment.revision) null else it.error) }
    }.onFailure(::reportError).isSuccess
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun saveProfile(profile: ConnectionProfile, targetPass: String, jumpPass: String) {
        runCatching {
            profile.validate()
            beforeChange()
            store.save(profile, targetPass, jumpPass)
            mutable.update { it.copy(environment = store.refresh(), status = "配置已保存", error = null) }
            changed(true)
        }.onFailure { e -> mutable.update { it.copy(error = e.message ?: "配置更新失败") } }
    }
    fun importKey(uri: Uri, jump: Boolean) = action {
        val slot = if (jump) "jump-key" else "target-key"
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buffer.size() <= 1_048_576) {
                    val count = stream.read(chunk, 0, minOf(chunk.size, 1_048_577 - buffer.size()))
                    if (count < 0) break
                    buffer.write(chunk, 0, count)
                }
                val bytes = buffer.toByteArray()
                require(bytes.size <= 1_048_576) { "私钥文件过大" }
                val value = bytes.toString(Charsets.UTF_8); bytes.fill(0); value
            } ?: error("无法读取私钥文件")
        }
        require(text.contains("PRIVATE KEY-----")) { "请选择 OpenSSH 或 PEM 格式的私钥" }
        beforeChange()
        store.vault.put(slot, text); store.vault.put("$slot-name", "已导入 · 加密保存")
        store.credentialsChanged()
        mutable.update { it.copy(environment = store.refresh(), status = "私钥已导入") }
        changed(false)
    }
    fun trust(challenge: HostChallenge) {
        runCatching {
        require(!challenge.changed) { "主机指纹已改变，不能自动覆盖" }
        beforeChange()
        store.vault.put("host:${challenge.host}", challenge.key)
        store.credentialsChanged()
        mutable.update { it.copy(environment = store.refresh(), error = null) }
        changed(true)
        }.onFailure(::reportError)
    }
    fun pair(raw: String) = action { pairInternal(raw) }
    fun pairImage(uri: Uri) = action {
        val raw = withContext(Dispatchers.IO) {
            val resolver = app.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取二维码图片" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("无法读取二维码图片")
            try { decodePairingQr(bitmap) } finally { bitmap.recycle() }
        }
        pairInternal(raw)
    }
    private suspend fun pairInternal(raw: String) {
        val ticket = PairingTicket.parse(raw)
        beforeChange()
        mutable.update { it.copy(status = "正在配对开发机…") }
        var committed = false
        try {
            val identity = withContext(Dispatchers.IO) {
                DeviceIdentity.read(store.vault) ?: DeviceIdentity.generate().also { store.vault.put(DeviceIdentity.SLOT, it.json()) }
            }
            val grant = exchange(ticket, identity.publicKey)
            mutable.update { it.copy(status = "正在验证开发机连接…") }
            verifier.verify(grant.profile, SecretReader { grant.pins[it] ?: store.vault.get(it) })
            currentCoroutineContext().ensureActive()
            grant.pins.forEach { (name, value) -> store.vault.put(name, value) }
            store.save(grant.profile, "", "")
            mutable.update { it.copy(environment = store.refresh(), status = "配对完成", pairingCompleted = it.pairingCompleted + 1) }
            committed = true
            changed(true)
        } finally {
            if (!committed) mutable.update { it.copy(status = "配对未完成，请重试或生成新的连接码") }
        }
    }
}
