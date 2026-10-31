package com.gmch.codexremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Optional local fixture. The seed and this code exist only in the test package, never the app APK. */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    @Test fun qrPairingAndReconnect() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "pairing-qr.png")
        assumeTrue("Optional pairing QR is not installed", file.isFile)
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.path)
        val raw = try { decodePairingQr(bitmap) } finally { bitmap.recycle(); file.delete() }
        val ticket = PairingTicket.parse(raw)
        val vault = Vault(context)
        lateinit var vm: RemoteViewModel
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        suspend fun awaitState(test: (UiState) -> Boolean) = withTimeout(120_000) {
            while (!test(vm.state.value)) {
                vm.state.value.error?.let { error(it) }
                delay(100)
            }
        }
        var threadId: String? = null
        onMain { vm = RemoteViewModel(context.applicationContext as android.app.Application) }
        try {
            onMain { vm.foreground(); vm.pair(raw) }
            awaitState { it.connected && !it.busy && it.profile.useDeviceKey }
            val identity = DeviceIdentity.read(vault)!!
            assertNull(vault.get("jump-key"))
            assertNull(vault.get("target-key"))
            assertEquals(vm.state.value.profile, PairingClient.redeem(ticket, identity.publicKey).profile)
            val otherDevice = runCatching { PairingClient.redeem(ticket, DeviceIdentity.generate().publicKey) }
            assertTrue(otherDevice.isFailure)
            assertTrue(otherDevice.exceptionOrNull()!!.message!!.contains("其他设备"))
            onMain { vm.createThread("/tmp/codex-remote-qr-smoke", "Android 扫码配对验证") }
            awaitState { it.selected != null && !it.busy }
            threadId = vm.state.value.selected!!.id
            onMain { vm.draft("Reply exactly ANDROID_QR_OK. Do not use tools or modify files."); vm.send(false) }
            awaitState { !it.busy && it.draft.isEmpty() }
            onMain { vm.background(); vm.foreground() }
            awaitState { it.connected && !it.busy && it.selected?.status == "已完成" &&
                it.items.any { item -> item.kind == "agentMessage" && item.text.contains("ANDROID_QR_OK") } }
            onMain {
                vm.background()
                vm = RemoteViewModel(context.applicationContext as android.app.Application)
                vm.foreground()
            }
            awaitState { it.connected && !it.busy }
            assertEquals(identity.publicKey, DeviceIdentity.read(vault)!!.publicKey)
        } finally {
            File(context.filesDir, "pairing-result.json").writeText(obj(
                "threadId" to threadId, "publicKey" to DeviceIdentity.read(vault)?.publicKey).toString())
            onMain { vm.background() }
        }
    }
    @Test fun encryptedStorageRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = Vault(context)
        vault.put("test-value", "private-test-value")
        assertEquals("private-test-value", vault.get("test-value"))
        val raw = File(context.applicationInfo.dataDir, "shared_prefs/vault.xml").readText()
        assertFalse(raw.contains("private-test-value"))
        vault.put("test-value", null)
    }
    @Test fun realSshAndAppServer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "integration-seed.json")
        assumeTrue("Optional live fixture is not installed", file.isFile)
        val seed = JSONObject(file.readText()); file.delete()
        val vault = Vault(context)
        seed.keys().forEach { vault.put(it, seed.getString(it)) }
        val profile = ConnectionProfile.from(JSONObject(vault.get("profile")!!))
        val ssh = SshTransport()
        var rpc: RpcClient? = null
        try {
            ssh.connect(profile, vault, "", "")
            val c = RpcClient(ssh.input, ssh.output, {}, {}); rpc = c
            assertTrue(c.initialize().has("userAgent"))
            val result = c.call("thread/list", obj("limit" to 2))
            assertTrue(result.has("data"))
            assertTrue(c.call("fs/readDirectory", obj("path" to profile.root)).has("entries"))
        } finally { rpc?.close(); ssh.close() }

        // Exercise the same state/lifecycle code as the screens, against our own temporary workspace.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var vm: RemoteViewModel
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        suspend fun awaitState(test: (UiState) -> Boolean) = withTimeout(120_000) {
            while (!test(vm.state.value)) {
                vm.state.value.error?.let { error(it) }
                delay(100)
            }
        }
        var threadId: String? = null
        onMain { vm = RemoteViewModel(context.applicationContext as android.app.Application) }
        try {
            onMain { vm.foreground() }
            awaitState { it.connected && !it.busy }
            onMain { vm.createThread(profile.root, "Android 实机链路验证") }
            awaitState { it.selected != null && !it.busy }
            threadId = vm.state.value.selected!!.id
            onMain { vm.draft("Reply exactly ANDROID_REMOTE_OK. Do not use tools or modify files."); vm.send(false) }
            awaitState { !it.busy && it.draft.isEmpty() }
            onMain { vm.background() }
            assertFalse(vm.state.value.connected)
            onMain { vm.foreground() }
            awaitState { it.connected && !it.busy && it.selected?.status == "已完成" && it.items.any { item -> item.kind == "agentMessage" && item.text.contains("ANDROID_REMOTE_OK") } }
            assertEquals("已完成", vm.state.value.selected!!.status)
            assertNull(vm.state.value.uncertain)
        } finally {
            threadId?.let { File(context.filesDir, "integration-result.json").writeText(obj("threadId" to it).toString()) }
            onMain { vm.background() }
        }
    }
}
