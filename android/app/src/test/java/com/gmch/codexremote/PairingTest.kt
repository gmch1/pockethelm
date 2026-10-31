package com.gmch.codexremote

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PairingTest {
    @Test fun generatedIdentityCanAuthenticateWithTheSshLibrary() {
        val identity = DeviceIdentity.generate()
        SshTransport.configureCrypto()
        val loaded = KeyPair.load(JSch(), identity.privateKey.toByteArray(), null)
        try {
            val expected = Base64.getDecoder().decode(identity.publicKey.split(' ')[1])
            assertArrayEquals(expected, loaded.publicKeyBlob)
            val data = "prove possession of this device key".toByteArray()
            val signature = loaded.getSignature(data)
            val verifier = loaded.verifier
            verifier.update(data)
            assertTrue(verifier.verify(signature))
            assertFalse(identity.toString().contains("PRIVATE KEY"))
        } finally { loaded.dispose() }
    }
    @Test fun qrParsingRejectsExpiredAndInjectedEndpoints() {
        val identity = DeviceIdentity.generate()
        val j = obj("v" to 2, "host" to "jump.example.org", "port" to 22, "user" to "root",
            "hostKey" to identity.publicKey.split(' ')[1], "key" to identity.privateKey, "expires" to 1200L)
        fun encoded() = PairingTicket.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(j.toString().toByteArray())
        assertEquals(22, PairingTicket.parse(encoded(), 1000).port)
        assertTrue(runCatching { PairingTicket.parse(encoded(), 1200) }.isFailure)
        j.put("host", "host; touch /tmp/injected")
        assertTrue(runCatching { PairingTicket.parse(encoded(), 1000) }.isFailure)
        j.put("host", "jump.example.org").put("v", 1)
        assertTrue(runCatching { PairingTicket.parse(encoded(), 1000) }.isFailure)
    }
    @Test fun newInstallHasNoPersonalEndpointAndOldPairingIsRejected() {
        val profile = ConnectionProfile()
        assertTrue(profile.host.isBlank() && profile.jumpHost.isBlank() && profile.user.isBlank() && profile.root.isBlank())
        assertTrue(runCatching { profile.validate() }.isFailure)
        assertTrue(runCatching { profile.copy(host = "example.invalid", user = "developer", root = "/workspace").validate() }.isFailure)
    }
    @Test fun oldProfilesKeepTheirImportedKeyMode() {
        assertFalse(ConnectionProfile.from(obj("host" to "127.0.0.1")).useDeviceKey)
        assertTrue(ConnectionProfile.from(ConnectionProfile().json()).useDeviceKey)
    }
}
