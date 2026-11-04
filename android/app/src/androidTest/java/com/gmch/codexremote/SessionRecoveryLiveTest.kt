package com.gmch.codexremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmch.codexremote.engines.contract.*
import com.gmch.codexremote.engines.codex.CodexEngineClient
import com.gmch.codexremote.engines.zcode.ZcodeEngineClient
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only native verification using the administrator's existing paired test device.
 * No session creation, model turns, pairing replacement or vault mutations. */
@RunWith(AndroidJUnit4::class)
class SessionRecoveryLiveTest {
    @Test fun bothNativeTransportsReconnectAndReleaseTheirWorkers(): Unit = runBlocking {
        val vault = Vault(InstrumentationRegistry.getInstrumentation().targetContext)
        val raw = vault.get("profile")
        assumeTrue("Administrator paired test profile is required", raw != null)
        val profile = ConnectionProfile.from(JSONObject(raw!!))
        for (zcode in listOf(false, true)) repeat(2) {
            val ssh = SshTransport()
            var client: RpcClient? = null
            try {
                ssh.connect(profile, vault, "", "", endpoint = if (zcode) com.gmch.codexremote.engines.BuiltinEndpoints.Zcode else com.gmch.codexremote.engines.BuiltinEndpoints.Codex)
                val rpc = RpcClient(ssh.input, ssh.output, {}, {})
                client = rpc
                if (zcode) {
                    assertEquals("zcode", rpc.call("initialize", obj("clientId" to "pockethelm-recovery-${java.util.UUID.randomUUID()}")).string("engine"))
                    val reader: EngineClient = ZcodeEngineClient(EngineOrigin(profile.resumeIdentity, "zcode", "bridge-v1"), rpc)
                    val catalog = reader.projects()
                    assertNotNull(catalog.sessions)
                    assertEquals(SessionCatalogMode.Snapshot, catalog.sessions!!.mode)
                    assertTrue(catalog.sessions.items.all { it.ref.origin == reader.origin })
                } else {
                    assertTrue(rpc.initialize().has("userAgent"))
                    val reader: EngineClient = CodexEngineClient(EngineOrigin(profile.resumeIdentity, "codex", profile.binary), rpc, profile.root)
                    val page = reader.sessions(SessionQuery(limit = 1))
                    assertEquals(SessionCatalogMode.Paged, page.mode)
                    assertTrue(page.items.all { it.ref.origin == reader.origin })
                    assertTrue(reader.projects().projects.all { it.origin == reader.origin })
                }
            } finally {
                client?.close(); ssh.close()
                assertTrue("SSH cleanup must release both the socket and connector worker", ssh.awaitClosed())
            }
        }
    }

    @Test fun stalledNativeSshHandshakeIsClosedWithinRecoveryBudget(): Unit = runBlocking {
        val vault = Vault(InstrumentationRegistry.getInstrumentation().targetContext)
        val raw = vault.get("profile")
        assumeTrue("Administrator paired test profile is required", raw != null)
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val accepted = java.util.concurrent.CountDownLatch(1)
        val peer = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>()
        val worker = kotlin.concurrent.thread(name = "stalled-test-ssh") {
            try {
                server.accept().use { socket ->
                    peer.set(socket); accepted.countDown()
                    // No SSH banner: JSch waits until the transport owner closes it.
                    while (socket.getInputStream().read() != -1) { }
                }
            } catch (_: java.io.IOException) { }
        }
        val profile = ConnectionProfile.from(JSONObject(raw!!)).copy(
            jumpHost = "127.0.0.1", jumpPort = server.localPort)
        val ssh = SshTransport()
        try {
            val error = runCatching {
                withContext(RecoveryDeadline(System.nanoTime() + 2_000_000_000L)) {
                    ssh.connect(profile, vault, "", "", com.gmch.codexremote.engines.BuiltinEndpoints.Codex)
                }
            }.exceptionOrNull()
            assertNotNull("stalled handshake must not connect", error)
            assertEquals("JSch must actually reach the controlled peer", 0L, accepted.count)
            ssh.close()
            assertTrue("blocking SSH read must exit after transport close", ssh.awaitClosed())
        } finally {
            ssh.close(); peer.get()?.close(); server.close(); worker.join(3_000)
            assertFalse("test listener must be reclaimed", worker.isAlive)
        }
    }

}
