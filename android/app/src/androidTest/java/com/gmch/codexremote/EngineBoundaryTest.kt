package com.gmch.codexremote

import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gmch.codexremote.app.*
import com.gmch.codexremote.app.EngineApp as RegisteredEngineApp
import com.gmch.codexremote.engines.*
import com.gmch.codexremote.engines.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import android.view.accessibility.AccessibilityNodeInfo

@RunWith(AndroidJUnit4::class)
class EngineBoundaryTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private class Host(id: String, label: String) : EngineHost {
        override val descriptor = EngineDescriptor(id, label)
        override val connection = MutableStateFlow(EngineConnection())
        var entered = 0; var left = 0; var backgrounded = 0; var connected = 0; var disconnected = 0
        override fun enter() { entered++ }
        override fun leave() { left++ }
        override fun keepConnection() = Unit
        override fun background() { backgrounded++ }
        override fun connect() { connected++; connection.value = connection.value.copy(connected = true, status = "已连接") }
        override fun disconnect() { disconnected++; connection.value = connection.value.copy(connected = false) }
        override fun prepareEnvironmentChange() = disconnect()
        override fun reloadEnvironment() = Unit
        override fun dismissError() = Unit
        override fun dismissChallenge() { connection.value = connection.value.copy(challenge = null) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; result += n; for (i in 0 until n.childCount) walk(n.getChild(i)) }
        walk(inst.uiAutomation.rootInActiveWindow)
        return result.filter { it.isVisibleToUser }
    }
    private fun awaitUi(predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!predicate() && System.nanoTime() < end) Thread.sleep(40)
        assertTrue(predicate())
    }
    private fun click(label: String) {
        awaitUi { nodes().any { it.text?.toString() == label } }
        var n = nodes().first { it.text?.toString() == label }
        while (!n.isClickable && n.parent != null) n = n.parent
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun thirdEngineUsesRegisteredTabsAndLifecycleWithoutNativeViewModelDependencies(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var app: AppViewModel
        val a = Host("alpha", "Alpha"); val b = Host("beta", "Beta"); val c = Host("third", "Third")
        inst.runOnMainSync { app = AppViewModel(fixture) }
        val engines = EngineUiRegistry(listOf(a, b, c).map { host -> EngineUiEntry(host) { shell ->
            val state by host.connection.collectAsState()
            WorkspaceScaffold(host.descriptor.label, engine = host.descriptor.id to shell::selectEngine) {
                Text("${host.descriptor.label} panel")
                Button(onClick = host::connect) { Text("连接 ${host.descriptor.label}") }
                Button(onClick = host::disconnect) { Text("断开 ${host.descriptor.label}") }
                Text(if (state.connected) "fake connected" else "fake disconnected")
            }
        } })
        try {
            inst.runOnMainSync { app.bind(engines.runtime, EnvironmentVerifier { _, _ -> error("not used") }); app.start() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { PocketHelmTheme { RegisteredEngineApp(app, engines) } } }
                click("Third"); awaitUi { nodes().any { it.text?.toString() == "Third panel" } }
                assertEquals(1, a.left); assertEquals(1, c.entered)
                click("连接 Third"); awaitUi { nodes().any { it.text?.toString() == "fake connected" } }
                click("断开 Third"); awaitUi { nodes().any { it.text?.toString() == "fake disconnected" } }
                assertEquals(1, c.connected); assertEquals(1, c.disconnected)
                assertEquals("third", app.store.selectedEngine())
                inst.runOnMainSync { app.stop(); app.start() }
                assertEquals(1, c.backgrounded); assertEquals(2, c.entered)
            }
        } finally {
            inst.runOnMainSync { app.stop(); app.viewModelScope.cancel() }
            inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
            fixture.prefsContext.noBackupFilesDir.deleteRecursively()
        }
    }
    @Test fun environmentStorePreservesFormatsAndSessionRecordsAndDoesNotPersistPassphrases(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        val vault = Vault(fixture)
        val profile = ConnectionProfile(name = "fixture", useJump = false, host = "127.0.0.1", user = "test", root = "/tmp", useDeviceKey = false)
        vault.put("draft:fixture", "keep draft"); vault.put("pending:fixture", "keep pending")
        val store = EnvironmentStore.forApp(fixture)
        store.save(profile, "ephemeral-target", "ephemeral-jump")
        assertEquals(profile, ConnectionProfile.from(org.json.JSONObject(vault.get("profile")!!)))
        assertEquals("keep draft", vault.get("draft:fixture")); assertEquals("keep pending", vault.get("pending:fixture"))
        assertEquals("ephemeral-target", store.targetPassphrase)
        store.clearPassphrases(); assertEquals("", store.targetPassphrase); assertEquals("", store.jumpPassphrase)
        val rawPrefs = fixture.getSharedPreferences("vault", 0).all
        assertEquals(setOf("profile", "draft:fixture", "pending:fixture"), rawPrefs.keys)
        assertFalse(rawPrefs.keys.any { vault.get(it)?.contains("ephemeral-") == true })
        inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
    }
    @Test fun staleOrChangedHostChallengesCannotBeTrusted(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var app: AppViewModel
        val a = Host("alpha", "Alpha"); val b = Host("beta", "Beta")
        inst.runOnMainSync {
            app = AppViewModel(fixture)
            app.bind(EngineRegistry(listOf(a, b)), EnvironmentVerifier { _, _ -> error("not used") })
            val challenge = HostChallenge("fixture", "ssh-ed25519", "public-key", "fingerprint", false)
            a.connection.value = EngineConnection(challenge = challenge, environmentRevision = app.store.refresh().revision)
            app.selectEngine("beta")
            app.trustHost("alpha", challenge, app.store.refresh().revision)
            assertNull(app.store.vault.get("host:fixture"))
            app.selectEngine("alpha")
            app.trustHost("alpha", challenge, app.store.refresh().revision + 1)
            assertNull(app.store.vault.get("host:fixture"))
            val changed = challenge.copy(changed = true)
            a.connection.value = a.connection.value.copy(challenge = changed)
            app.trustHost("alpha", changed, app.store.refresh().revision)
            assertNull(app.store.vault.get("host:fixture"))
            a.connection.value = a.connection.value.copy(challenge = challenge)
            app.trustHost("alpha", challenge, app.store.refresh().revision)
            assertEquals("public-key", app.store.vault.get("host:fixture"))
            app.viewModelScope.cancel()
        }
        inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
    }
    @Test fun pairingCommitsOnlyAfterVerificationAndLeavesDraftsIntact(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = EnvironmentStore.forApp(fixture)
        store.vault.put("draft:fixture", "keep")
        val pin = DeviceIdentity.generate().publicKey.split(' ')[1]
        val ticket = PairingTicket.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(obj(
            "v" to 2, "expires" to System.currentTimeMillis() / 1000 + 120,
            "host" to "127.0.0.1", "port" to 22, "user" to "fixture", "hostKey" to pin,
            "key" to "-----BEGIN OPENSSH PRIVATE KEY-----${"x".repeat(300)}").toString().toByteArray())
        val profile = ConnectionProfile(name = "verified fixture", jumpHost = "127.0.0.1", jumpUser = "device",
            host = "127.0.0.1", user = "test", root = "/tmp", environmentId = "fixture", configVersion = 2)
        var reject = true; var changes = 0
        val controller = EnvironmentController(fixture, scope, store,
            EnvironmentVerifier { _, _ ->
                assertNull(store.vault.get("profile")); assertNull(store.vault.get("host:fixture"))
                if (reject) throw java.io.IOException("verification failed")
            }, beforeChange = {}, changed = { changes++ },
            exchange = { _, publicKey -> assertTrue(publicKey.isNotBlank()); PairingGrant(profile, mapOf("host:fixture" to pin)) })
        try {
            withContext(Dispatchers.Main) { controller.pair(ticket) }.join()
            assertNull(store.vault.get("profile")); assertNull(store.vault.get("host:fixture"))
            assertEquals(0, changes); assertFalse(controller.state.value.busy)
            reject = false
            withContext(Dispatchers.Main) { controller.pair(ticket) }.join()
            assertEquals(profile, store.refresh().profile)
            assertEquals(pin, store.vault.get("host:fixture")); assertEquals("keep", store.vault.get("draft:fixture"))
            assertEquals(1, changes); assertEquals(1, controller.state.value.pairingCompleted)
            assertFalse(controller.state.value.busy); assertNull(controller.state.value.error)
        } finally { scope.cancel(); inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault") }
    }

    @Test fun clearingApplicationOwnerClearsSharedEphemeralPassphrases(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        val owner = object : androidx.lifecycle.ViewModelStoreOwner {
            override val viewModelStore = androidx.lifecycle.ViewModelStore()
        }
        inst.runOnMainSync {
            val app = androidx.lifecycle.ViewModelProvider(owner, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(fixture))[AppViewModel::class.java]
            app.store.save(ConnectionProfile(useJump = false, host = "127.0.0.1", user = "fixture", root = "/tmp", useDeviceKey = false), "target-secret", "jump-secret")
            owner.viewModelStore.clear()
            // Keep the old store strongly reachable: this must not rely on GC/WeakReference expiry.
            assertEquals("", app.store.targetPassphrase); assertEquals("", app.store.jumpPassphrase)
        }
        inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
    }

    @Test fun changingEnvironmentCancelsOldZcodeWriteWithoutClearingAnotherEnvironmentReceipt(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var vm: ZcodeViewModel
        inst.runOnMainSync { vm = ZcodeViewModel(fixture) }
        @Suppress("UNCHECKED_CAST")
        val state = ZcodeViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<ZcodeState>
        state.value = ZcodeState(connected = true, selected = ZSession("A", "A", "/tmp/test"), draft = "pending text",
            selection = ZSelection("p", "m"), selectionGuard = "g", providers = listOf(ZProvider("p", "fixture", "subscription", "g", listOf(ZModel("m", emptyList())))))
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["session/send"] = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); obj("status" to "accepted") }
        bridge.install()
        val store = EnvironmentStore.forApp(fixture)
        val next = ConnectionProfile(name = "other", useJump = false, host = "127.0.0.1", user = "other", root = "/tmp", useDeviceKey = false)
        val otherReceipt = obj("method" to "session/send", "params" to obj("commandId" to "other", "sessionId" to "B")).toString()
        store.vault.put("zcode:pending:${next.identity}:", otherReceipt)
        lateinit var send: Job
        try {
            inst.runOnMainSync { send = vm.send() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            inst.runOnMainSync { vm.environmentChanging(); store.save(next, "", ""); vm.reloadEnvironment() }
            withTimeout(3_000) { send.join() }
            release.countDown()
            assertTrue(send.isCancelled)
            assertNotNull(store.vault.get("zcode:pending:null:"))
            assertEquals(otherReceipt, store.vault.get("zcode:pending:${next.identity}:"))
            assertNull(state.value.selected); assertFalse(state.value.commandInFlight)
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnectManually(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean()
            inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
            fixture.prefsContext.noBackupFilesDir.deleteRecursively()
        }
    }

    @Test fun changingEnvironmentCancelsOldCodexAction(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var vm: RemoteViewModel
        inst.runOnMainSync { vm = RemoteViewModel(fixture) }
        @Suppress("UNCHECKED_CAST")
        val state = RemoteViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<UiState>
        state.value = UiState(connected = true, selected = SessionInfo("A", "A", "/tmp", "", "active", 0, activeTurnId = "turn-A"))
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["turn/interrupt"] = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); obj() }
        bridge.install()
        lateinit var action: Job
        try {
            inst.runOnMainSync { action = vm.interrupt() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            inst.runOnMainSync {
                vm.environmentChanging()
                EnvironmentStore.forApp(fixture).save(ConnectionProfile(useJump = false, host = "127.0.0.1", user = "other", root = "/tmp", useDeviceKey = false), "", "")
                vm.reloadEnvironment()
            }
            withTimeout(3000) { action.join() }; release.countDown()
            assertTrue(action.isCancelled); assertNull(state.value.selected); assertNull(state.value.error)
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnect(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean()
            inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
            fixture.prefsContext.noBackupFilesDir.deleteRecursively()
        }
    }

    @Test fun unreadableEnvironmentDoesNotLoseForegroundLifecycleState(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        Vault(fixture).put("profile", "not valid json")
        lateinit var vm: RemoteViewModel
        inst.runOnMainSync {
            vm = RemoteViewModel(fixture)
            vm.foreground()
            assertTrue(vm.state.value.foreground)
            assertFalse(vm.state.value.connected)
            assertNotNull(vm.state.value.error)
            EnvironmentStore.forApp(fixture).save(ConnectionProfile(useJump = false, host = "127.0.0.1", user = "fixture", root = "/tmp", useDeviceKey = false), "", "")
            assertTrue(vm.reloadEnvironment())
            assertTrue(vm.state.value.foreground); assertTrue(vm.state.value.saved)
            vm.disconnect(); vm.viewModelScope.cancel()
        }
        inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
        fixture.prefsContext.noBackupFilesDir.deleteRecursively()
    }

}
