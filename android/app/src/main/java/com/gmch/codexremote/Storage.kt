package com.gmch.codexremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private preferences contain ciphertext only; the non-exportable AES key stays in Android Keystore. */
fun interface SecretReader { fun get(name: String): String? }

class Vault(context: Context) : SecretReader {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("codex-remote-v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("codex-remote-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(name: String, value: String?) {
        if (value == null) { check(prefs.edit().remove(name).commit()); return }
        val bytes = encrypt(name, value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "无法保存本地数据" }
    }
    @Synchronized internal fun encrypt(name: String, value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(name.toByteArray()) }
        return cipher.iv + cipher.doFinal(value)
    }
    @Synchronized override fun get(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return decrypt(name, Base64.decode(encoded, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    @Synchronized internal fun decrypt(name: String, bytes: ByteArray): ByteArray {
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(name.toByteArray())
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}

data class ConnectionProfile(
    val name: String = "尚未配对", val useJump: Boolean = true,
    val jumpHost: String = "", val jumpPort: Int = 22, val jumpUser: String = "",
    val host: String = "", val port: Int = 22, val user: String = "",
    val root: String = "", val binary: String = "codex", val socket: String = "",
    val useDeviceKey: Boolean = true, val environmentId: String = "", val configVersion: Int = 0
) {
    val identity get() = "${if (useJump) "$jumpUser@$jumpHost:$jumpPort/" else ""}$user@$host:$port|$socket"
    fun validate() {
        if (useDeviceKey) {
            require(configVersion == 2 && environmentId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "请使用新版连接码重新配对" }
            require(useJump && jumpUser != "root" && user != "root") { "配对设备必须使用无特权连接账号" }
        }
        require(host.isNotBlank() && user.isNotBlank()) { "请填写目标主机与用户名" }
        require(port in 1..65535 && jumpPort in 1..65535) { "端口范围为 1–65535" }
        require(!useJump || (jumpHost.isNotBlank() && jumpUser.isNotBlank())) { "请填写跳板主机与用户名" }
        require(root.startsWith('/')) { "项目根目录需要使用绝对路径" }
        require(binary.isNotBlank()) { "请填写 Codex 命令或绝对路径" }
        require(socket.isBlank() || socket.startsWith('/')) { "自定义 socket 需要使用绝对路径" }
    }
    fun json() = obj("name" to name, "useJump" to useJump, "jumpHost" to jumpHost, "jumpPort" to jumpPort,
        "jumpUser" to jumpUser, "host" to host, "port" to port, "user" to user, "root" to root, "binary" to binary, "socket" to socket, "useDeviceKey" to useDeviceKey, "environmentId" to environmentId, "configVersion" to configVersion)
    companion object {
        fun from(j: JSONObject) = ConnectionProfile(j.string("name", "尚未配对"), j.optBoolean("useJump", true),
            j.string("jumpHost"), j.optInt("jumpPort", 22), j.string("jumpUser"),
            j.string("host"), j.optInt("port", 22), j.string("user"),
            j.string("root"), j.string("binary", "codex"), j.string("socket"), j.optBoolean("useDeviceKey", false), j.string("environmentId"), j.optInt("configVersion", 0))
    }
}
