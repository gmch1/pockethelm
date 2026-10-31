package com.gmch.codexremote

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class DeviceIdentity(val privateKey: String, val publicKey: String) {
    override fun toString() = "DeviceIdentity(" + fingerprint(publicKey) + ")"
    fun json() = obj("privateKey" to privateKey, "publicKey" to publicKey).toString()
    companion object {
        const val SLOT = "device-identity-v2"
        fun read(secrets: SecretReader): DeviceIdentity? = secrets.get(SLOT)?.let {
            val j = JSONObject(it); DeviceIdentity(j.getString("privateKey"), j.getString("publicKey"))
        }
        fun generate(): DeviceIdentity {
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
            val privateBuffer = ByteArrayOutputStream()
            val publicBuffer = ByteArrayOutputStream()
            try {
                pair.setPublicKeyComment("codex-remote-" + UUID.randomUUID().toString())
                pair.writeOpenSSHv1PrivateKey(privateBuffer, null)
                pair.writePublicKey(publicBuffer, pair.publicKeyComment)
                val bytes = privateBuffer.toByteArray()
                return try { DeviceIdentity(bytes.toString(Charsets.US_ASCII), publicBuffer.toString("US-ASCII").trim()) }
                finally { bytes.fill(0) }
            } finally { pair.dispose(); privateBuffer.reset() }
        }
        fun fingerprint(publicKey: String): String {
            val blob = Base64.getDecoder().decode(publicKey.split(' ')[1])
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        }
    }
}
