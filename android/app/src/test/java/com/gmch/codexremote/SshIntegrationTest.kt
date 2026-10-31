package com.gmch.codexremote

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional live test of the production SSH/WebSocket stack, using short-lived test credentials. */
class SshIntegrationTest {
    @Test fun productionTransportThroughTencent() = runBlocking {
        val fixture = System.getenv("CODEX_REMOTE_SSH_FIXTURE")
        assumeTrue("Live SSH fixture not provided", !fixture.isNullOrBlank())
        val seed = JSONObject(File(fixture!!).readText())
        val profile = ConnectionProfile.from(JSONObject(seed.getString("profile")))
        val secrets = SecretReader { if (seed.has(it)) seed.getString(it) else null }
        val ssh = SshTransport()
        var rpc: RpcClient? = null
        try {
            ssh.connect(profile, secrets, "", "")
            val client = RpcClient(ssh.input, ssh.output, {}, {}); rpc = client
            assertTrue(client.initialize().has("userAgent"))
            assertTrue(client.call("thread/list", obj("limit" to 1)).has("data"))
            assertTrue(client.call("fs/readDirectory", obj("path" to profile.root)).has("entries"))
        } finally { rpc?.close(); ssh.close() }
    }
}
