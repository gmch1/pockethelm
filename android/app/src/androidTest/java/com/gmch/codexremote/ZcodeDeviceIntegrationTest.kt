package com.gmch.codexremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in, own test workspace only. The QR and target never enter a release APK. */
@RunWith(AndroidJUnit4::class)
class ZcodeDeviceIntegrationTest {
    @Test fun pairedZcodeReadAndReconnect() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val targetFile = File(context.filesDir, "zcode-test-target.json")
        assumeTrue("Optional ZCode target fixture is not installed", targetFile.isFile)
        val target = JSONObject(targetFile.readText()); targetFile.delete()
        val app = context.applicationContext as android.app.Application
        val vault = Vault(context)
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        val qr = File(context.filesDir, "pairing-qr.png")
        if (qr.isFile) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(qr.path)
            val raw = try { decodePairingQr(bitmap) } finally { bitmap.recycle(); qr.delete() }
            lateinit var pairing: RemoteViewModel
            onMain { pairing = RemoteViewModel(app); pairing.foreground(); pairing.pair(raw) }
            try {
                withTimeout(120_000) {
                    while (!pairing.state.value.connected || pairing.state.value.busy) {
                        pairing.state.value.error?.let { error(it) }; delay(100)
                    }
                }
                assertTrue(pairing.state.value.profile.useDeviceKey)
            } finally { onMain { pairing.background() } }
        }
        assumeTrue("Optional paired device is not installed", vault.get("profile") != null)
        val identity = DeviceIdentity.read(vault)!!.publicKey
        lateinit var vm: ZcodeViewModel
        suspend fun awaitState(test: (ZcodeState) -> Boolean) = withTimeout(120_000) {
            while (!test(vm.state.value)) {
                vm.state.value.error?.let { error(it) }; delay(100)
            }
        }
        onMain { vm = ZcodeViewModel(app); vm.enter() }
        try {
            awaitState { it.connected && !it.busy && it.providers.isNotEmpty() }
            onMain { vm.open(ZSession(target.getString("sessionId"), "PocketHelm integration", target.getString("workspacePath"))) }
            awaitState { !it.busy && it.items.any { row -> row.kind == "agentMessage" && row.text.contains("POCKETHELM_ZCODE_OK") } }
            assertEquals("完全访问", vm.state.value.permission)
            onMain { vm.quota() }
            awaitState { !it.busy && it.quota.contains("剩余") }
            onMain { vm.background(); vm.foreground() }
            awaitState { it.connected && !it.busy && it.items.any { row -> row.kind == "agentMessage" } }
            assertEquals(identity, DeviceIdentity.read(vault)!!.publicKey)
            assertFalse(vm.state.value.pending)
            // Model controls must not mutate the existing session until a send.
            val actual = vm.state.value.currentSelection
            val provider = vm.state.value.providers.first()
            val alternate = provider.models.first()
            onMain { vm.choose(ZSelection(provider.id, alternate.id, alternate.levels.lastOrNull().orEmpty())); vm.refresh() }
            awaitState { it.connected && !it.busy }
            assertEquals(actual, vm.state.value.currentSelection)
        } finally { onMain { vm.leave() } }
    }
}
