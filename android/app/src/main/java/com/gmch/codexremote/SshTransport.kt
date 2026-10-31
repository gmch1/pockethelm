package com.gmch.codexremote

import com.jcraft.jsch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

data class HostChallenge(val host: String, val algorithm: String, val key: String, val fingerprint: String, val changed: Boolean)
class HostIdentityException(val challenge: HostChallenge) : Exception(
    if (challenge.changed) "${challenge.host} 的主机密钥已改变，连接已停止。请核对服务器身份。" else "首次连接，需要核对服务器指纹")

class SshTransport : Closeable {
    companion object {
        fun configureCrypto() {
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        }
    }
    private val sessions = mutableListOf<Session>()
    private var channel: ChannelExec? = null
    private val clients = mutableListOf<JSch>()
    @Volatile private var closing = false
    lateinit var input: InputStream
        private set
    lateinit var output: OutputStream
        private set
    var diagnostics: String = ""
        private set

    suspend fun connect(profile: ConnectionProfile, vault: SecretReader, targetPassword: String, jumpPassword: String, zcode: Boolean = false) = withContext(Dispatchers.IO) {
        // Socket keepalives alone cannot time out a silent exec command / WebSocket upgrade.
        val deadline = launch { delay(45_000); close() }
        try {
            profile.validate()
            configureCrypto()
            val deviceKey = if (profile.useDeviceKey) DeviceIdentity.read(vault)?.privateKey ?: error("请先扫码连接此开发机") else null
            // Force lightweight BC implementations on Android, independent of its reported Java version.
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            fun session(user: String, host: String, port: Int, alias: String, keySlot: String, passphrase: String): Session {
                check(!closing) { "连接已取消" }
                val ssh = JSch()
                synchronized(clients) { clients.add(ssh) }
                val privateKey = (deviceKey ?: vault.get(keySlot))?.toByteArray() ?: error("请先导入${if (keySlot == "jump-key") "跳板机" else "开发机"} SSH 私钥")
                val password = (if (deviceKey != null) "" else passphrase).toByteArray()
                try { ssh.addIdentity(keySlot, privateKey, null, password.takeIf { it.isNotEmpty() }) }
                finally { privateKey.fill(0); password.fill(0) }
                var challenge: HostChallenge? = null
                ssh.hostKeyRepository = object : HostKeyRepository {
                    override fun check(hostName: String, key: ByteArray): Int {
                        val hostKey = HostKey(hostName, key)
                        val encoded = Base64.getEncoder().encodeToString(key)
                        val pin = vault.get("host:$alias")
                        if (pin == encoded) return HostKeyRepository.OK
                        val digest = Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                        challenge = HostChallenge(alias, hostKey.type, encoded, "SHA256:$digest", pin != null)
                        return if (pin == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
                    }
                    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
                    override fun remove(host: String?, type: String?) = Unit
                    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                    override fun getKnownHostsRepositoryID() = "Android Keystore pins"
                    override fun getHostKey(): Array<HostKey> = emptyArray()
                    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
                }
                val result = ssh.getSession(user, host, port)
                synchronized(sessions) { sessions.add(result) }
                result.setHostKeyAlias(alias)
                result.setConfig("StrictHostKeyChecking", "yes")
                result.setConfig("PreferredAuthentications", "publickey")
                result.serverAliveInterval = 15_000
                result.serverAliveCountMax = 2
                try { result.connect(15_000) } catch (e: Exception) { throw challenge?.let(::HostIdentityException) ?: e }
                check(!closing) { "连接已取消" }
                result.timeout = 0
                return result
            }
            var targetHost = profile.host
            var targetPort = profile.port
            if (profile.useJump) {
                val jump = session(profile.jumpUser, profile.jumpHost, profile.jumpPort,
                    "${profile.jumpHost}:${profile.jumpPort}", "jump-key", jumpPassword)
                targetPort = jump.setPortForwardingL("127.0.0.1", 0, profile.host, profile.port)
                targetHost = "127.0.0.1"
            }
            val target = session(profile.user, targetHost, targetPort,
                if (profile.useJump) "${profile.host}:${profile.port} via ${profile.jumpHost}:${profile.jumpPort}" else "${profile.host}:${profile.port}",
                "target-key", targetPassword)
            val exec = target.openChannel("exec") as ChannelExec
            channel = exec
            val bin = shellQuote(profile.binary)
            val proxy = "$bin app-server proxy" + if (profile.socket.isNotBlank()) " --sock ${shellQuote(profile.socket)}" else ""
            // Reuse the shared daemon; a custom socket is never started/replaced implicitly.
            val command = if (profile.socket.isBlank()) "$bin app-server daemon start >/dev/null && exec $proxy" else "exec $proxy"
            exec.setCommand(if (zcode) "pockethelm-zcode-v1" else "bash -lc ${shellQuote(command)}")
            exec.setPty(false)
            val rawInput = exec.inputStream
            val rawOutput = exec.outputStream
            val stderr = exec.errStream
            exec.connect(15_000)
            Thread({
                try { stderr.bufferedReader().useLines { lines -> lines.forEach { diagnostics = (diagnostics + it + "\n").takeLast(2500) } } }
                catch (_: Exception) { /* transport closure */ }
            }, "ssh-stderr").apply { isDaemon = true; start() }
            target.timeout = 20_000
            if (zcode) {
                input = rawInput
                output = rawOutput
            } else {
                val websocket = WebSocketTunnel(rawInput, rawOutput)
                websocket.handshake()
                input = websocket.input
                output = websocket.output
            }
            target.timeout = 0
            check(!closing) { "连接已取消" }
        } catch (t: Throwable) { close(); throw t }
        finally { deadline.cancel() }
    }

    override fun close() {
        closing = true
        runCatching { channel?.disconnect() }
        synchronized(sessions) { sessions.reversed().forEach { runCatching { it.disconnect() } }; sessions.clear() }
        synchronized(clients) { clients.forEach { runCatching { it.removeAllIdentity() } }; clients.clear() }
    }
}
