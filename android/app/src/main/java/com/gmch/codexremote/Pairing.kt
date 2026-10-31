package com.gmch.codexremote

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64

/** Short-lived SSH enrollment capability. Never persisted or included in logs. */
class PairingTicket(
    val host: String, val port: Int, val user: String, val hostKey: String,
    val privateKey: String, val expires: Long
) {
    companion object {
        const val PREFIX = "codex-remote:pair:"
        fun parse(raw: String, now: Long = System.currentTimeMillis() / 1000): PairingTicket {
            require(raw.length in 100..6000 && raw.startsWith(PREFIX)) { "不是有效的 Codex Remote 连接码" }
            val j = try { JSONObject(String(Base64.getUrlDecoder().decode(raw.removePrefix(PREFIX)), Charsets.UTF_8)) }
                catch (_: Exception) { error("连接码内容损坏，请重新生成") }
            require(j.optInt("v") == 2) { "旧版连接码已停用，请重新生成" }
            val expires = j.getLong("expires")
            require(expires > now && expires <= now + 600) { "连接码已过期，请在电脑上重新生成" }
            val host = j.getString("host"); val user = j.getString("user"); val port = j.getInt("port")
            require(host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}")) && user.matches(Regex("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}")) && port in 1..65535) { "连接码地址无效" }
            val key = j.getString("key"); val pin = j.getString("hostKey")
            require(key.length in 300..2048 && key.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) { "连接码凭据无效" }
            require(pin.length in 40..1500 && !pin.any(Char::isWhitespace)) { "连接码主机信息无效" }
            HostKey("pairing", Base64.getDecoder().decode(pin))
            return PairingTicket(host, port, user, pin, key, expires)
        }
    }
}

fun decodePairingQr(bitmap: Bitmap): String {
    require(bitmap.width.toLong() * bitmap.height <= 16_000_000) { "二维码图片过大" }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val bitmapSource = BinaryBitmap(HybridBinarizer(source))
    return try { MultiFormatReader().decode(bitmapSource, mapOf(DecodeHintType.TRY_HARDER to true)).text }
    catch (_: com.google.zxing.ReaderException) {
        // Dense exported QR images can confuse finder-pattern detection. Pure
        // barcode decoding applies only to this image path, after normal detection.
        try { MultiFormatReader().decode(bitmapSource, mapOf(DecodeHintType.PURE_BARCODE to true)).text }
        catch (_: com.google.zxing.ReaderException) { error("没有识别到连接二维码，请选择完整清晰的二维码图片") }
    }
}

data class PairingGrant(val profile: ConnectionProfile, val pins: Map<String, String>)

object PairingClient {
    suspend fun redeem(ticket: PairingTicket, publicKey: String): PairingGrant = withContext(Dispatchers.IO) {
        SshTransport.configureCrypto()
        val ssh = JSch()
        val key = ticket.privateKey.toByteArray(Charsets.US_ASCII)
        try { ssh.addIdentity("pairing-ticket", key, null, null) } finally { key.fill(0) }
        val type = HostKey("pairing", Base64.getDecoder().decode(ticket.hostKey)).type
        ssh.setKnownHosts(ByteArrayInputStream(("pairing " + type + " " + ticket.hostKey + "\n").toByteArray()))
        val session = ssh.getSession(ticket.user, ticket.host, ticket.port)
        session.setHostKeyAlias("pairing")
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        val deadline = launch { delay(45_000); session.disconnect() }
        try {
            session.connect(15_000)
            val exec = session.openChannel("exec") as ChannelExec
            exec.setCommand("pair-device")
            val input = exec.inputStream; val output = exec.outputStream
            exec.connect(15_000)
            output.write((obj("publicKey" to publicKey).toString() + "\n").toByteArray()); output.flush()
            val buffer = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == 10) break
                check(buffer.size() < 16_384) { "配对响应无效" }; buffer.write(b)
            }
            check(buffer.size() > 0) { "配对未完成，连接码可能已过期，请重新生成" }
            val result = JSONObject(buffer.toString("UTF-8"))
            check(result.optBoolean("ok")) { result.string("error", "连接码已失效，请重新生成") }
            val profile = ConnectionProfile.from(result.getJSONObject("profile")).copy(useDeviceKey = true)
            profile.validate()
            require(profile.useJump && profile.jumpHost == ticket.host && profile.jumpPort == ticket.port) { "配对返回的环境与连接码不符" }
            val pinsJson = result.getJSONObject("pins")
            val pins = pinsJson.keys().asSequence().associateWith { pinsJson.getString(it) }
            val jumpAlias = "host:" + profile.jumpHost + ":" + profile.jumpPort
            val targetAlias = "host:" + profile.host + ":" + profile.port + " via " + profile.jumpHost + ":" + profile.jumpPort
            require(pins[jumpAlias] == ticket.hostKey && pins.containsKey(targetAlias)) { "配对主机信息不完整" }
            PairingGrant(profile, pins.filterKeys { it == jumpAlias || it == targetAlias })
        } finally { deadline.cancel(); session.disconnect(); ssh.removeAllIdentity() }
    }
}
