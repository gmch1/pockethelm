package com.gmch.codexremote

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ResumeLifecycleTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = TestPrefsApp(UUID.randomUUID().toString()).apply { attach(instrument.targetContext) }
    private val models = mutableListOf<AndroidViewModel>()
    private val bridges = mutableListOf<FakeRpcBridge>()
    private val profile = ConnectionProfile(useJump = false, host = "example.invalid", user = "test", root = "/work",
        useDeviceKey = true, configVersion = 2, environmentId = "test-${app.namespace}")
    private fun main(block: () -> Unit) = instrument.runOnMainSync(block)
    private fun field(vm: Any, name: String) = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun <T> state(vm: Any) = field(vm, "mutable").get(vm) as MutableStateFlow<T>
    private fun codex(): RemoteViewModel { lateinit var vm: RemoteViewModel; main { vm = RemoteViewModel(app); models += vm }; return vm }
    private fun zcode(): ZcodeViewModel { lateinit var vm: ZcodeViewModel; main { vm = ZcodeViewModel(app); models += vm }; return vm }
    private suspend fun await(test: () -> Boolean) = withTimeout(8_000) { while (!test()) delay(20) }
    private fun seedProfile() { Vault(app).put("profile", profile.json().toString()) }
    private suspend fun restored(vm: Any) { (field(vm, "restoreJob").get(vm) as Job).join() }

    @Test fun encryptedCacheRejectsCorruptionOtherEnvironmentAndOtherEngine() = runBlocking {
        val cache = ResumeCache(app, "codex")
        cache.write(ResumeSnapshot(profile.resumeIdentity, items = listOf(TimelineItem("i", "agentMessage", "PRIVATE-CACHED-ANSWER"))))
        val file = File(app.noBackupFilesDir, "resume-v1-codex.bin")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("PRIVATE-CACHED-ANSWER"))
        assertEquals("PRIVATE-CACHED-ANSWER", cache.read(profile.resumeIdentity)!!.items.single().text)
        assertNull(cache.read("other-environment"))
        assertNull(ResumeCache(app, "zcode").read(profile.resumeIdentity))
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.read(profile.resumeIdentity))
    }

    @Test fun coldCodexRestoresEnginePageHistoryDraftAndPendingWithoutSending() = runBlocking {
        seedProfile(); val vault = Vault(app)
        vault.put("ui:engine", "zcode")
        vault.put("draft:${profile.identity}:s", "尚未发送的草稿")
        vault.put("pending:${profile.identity}:s", obj("clientId" to "pending-id", "text" to "待核对").toString())
        val session = SessionInfo("s", "缓存会话", "/work", "", "active", 1)
        ResumeCache(app, "codex").write(ResumeSnapshot(profile.resumeIdentity, 1, listOf(session), listOf("/work"), session,
            listOf(TimelineItem("i", "agentMessage", "缓存回答"))))
        val vm = codex(); restored(vm)
        val s = vm.state.value
        assertEquals("zcode", s.engine); assertEquals(1, s.tab)
        assertEquals("s", s.selected!!.id); assertEquals("缓存回答", s.items.single().text)
        assertEquals("尚未发送的草稿", s.draft); assertNotNull(s.uncertain)
        assertEquals(OutgoingState.UNCERTAIN, s.outgoing.single().state)
        assertTrue(s.historyCached); assertTrue(s.sessionsCached); assertFalse(s.connected)
        assertNull(field(vm, "rpc").get(vm)); assertFalse(s.historyLoading)
    }

    @Test fun coldZcodeRestoresConversationAndChoiceWithoutTrustingCachedStatus() = runBlocking {
        seedProfile()
        val session = ZSession("z", "缓存Z", "/work", status = "running")
        val choice = ZSelection("provider", "model", "max")
        Vault(app).put("zcode:draft:${profile.identity}:z", "草稿Z")
        ResumeCache(app, "zcode").write(ResumeSnapshot(profile.resumeIdentity, zSessions = listOf(session), zSelected = session,
            items = listOf(TimelineItem("i", "agentMessage", "回答Z")), choice = choice, guard = "guard"))
        val vm = zcode(); restored(vm)
        val s = vm.state.value
        assertEquals("z", s.selected!!.id); assertEquals("回答Z", s.items.single().text)
        assertEquals("草稿Z", s.draft); assertEquals(choice, s.selection); assertEquals("guard", s.selectionGuard)
        assertTrue(s.historyCached); assertFalse(s.sessionsFresh); assertFalse(s.connected)
        assertEquals("", s.executionId); assertNull(field(vm, "rpc").get(vm))
    }

    @Test fun quickCodexReturnReusesConnectionWithoutReloading() = runBlocking {
        val vm = codex(); restored(vm)
        val flow = state<UiState>(vm)
        main { flow.value = UiState(profile = profile, saved = true, connected = true, foreground = true, recoveryPhase = RecoveryPhase.Ready,
            items = listOf(TimelineItem("i", "agentMessage", "保留内容"))) }
        val bridge = FakeRpcBridge(vm).also { it.install(); bridges += it }
        val rpc = field(vm, "rpc").get(vm)
        main { vm.background(); vm.foreground() }
        delay(100)
        assertSame(rpc, field(vm, "rpc").get(vm)); assertTrue(vm.state.value.connected)
        assertEquals("保留内容", vm.state.value.items.single().text)
        assertTrue(bridge.unexpected.isEmpty())
    }

    @Test fun quickZcodeReturnReusesConnectionAndDoesNotReloadCatalog() = runBlocking {
        seedProfile(); val vm = zcode(); restored(vm)
        val flow = state<ZcodeState>(vm)
        main {
            flow.value = ZcodeState(connected = true, sessionsFresh = true, recoveryPhase = RecoveryPhase.Ready)
            field(vm, "active").set(vm, true); field(vm, "visible").set(vm, true)
            field(vm, "listSyncedAt").set(vm, System.currentTimeMillis())
        }
        val bridge = FakeRpcBridge(vm).also { it.install(); bridges += it }
        val rpc = field(vm, "rpc").get(vm)
        main { vm.background(); vm.foreground() }
        delay(100)
        assertSame(rpc, field(vm, "rpc").get(vm)); assertTrue(vm.state.value.connected)
        assertTrue(bridge.unexpected.isEmpty())
    }

    @Test fun longBackgroundReleasesBothChannels() = runBlocking {
        val c = codex(); val z = zcode(); restored(c); restored(z)
        main {
            state<UiState>(c).value = UiState(connected = true, foreground = true)
            state<ZcodeState>(z).value = ZcodeState(connected = true)
            field(z, "visible").set(z, true)
        }
        bridges += FakeRpcBridge(c).also { it.install() }; bridges += FakeRpcBridge(z).also { it.install() }
        main { c.background(); z.background() }
        assertTrue(c.state.value.connected); assertTrue(z.state.value.connected)
        withTimeout(BACKGROUND_GRACE_MS + 5_000) {
            while (c.state.value.connected || z.state.value.connected) delay(100)
        }
        assertNull(field(c, "rpc").get(c)); assertNull(field(z, "rpc").get(z))
    }

    @Test fun codexListAppearsBeforeSlowStatusEnrichment() = runBlocking {
        val vm = codex(); restored(vm)
        val flow = state<UiState>(vm)
        main { flow.value = UiState(connected = true, cursor = "next") }
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        val bridge = FakeRpcBridge(vm).also { it.install(); bridges += it }
        bridge.scripted["thread/list"] = { obj("data" to JSONArray().put(obj("id" to "s", "name" to "立即展示", "updatedAt" to 1))) }
        bridge.scripted["thread/turns/list"] = { entered.countDown(); gate.await(5, TimeUnit.SECONDS); obj("data" to JSONArray().put(obj("status" to "completed"))) }
        try {
            main { vm.moreSessions() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("立即展示", vm.state.value.sessions.single().title)
            assertFalse(vm.state.value.busy)
        } finally { gate.countDown() }
        await { vm.state.value.sessions.single().lastTurnStatus == "completed" }
    }

    @Test fun missingZcodeSessionIsEvictedAfterRefreshingCachedList() = runBlocking {
        seedProfile()
        val session = ZSession("deleted", "已删会话", "/work")
        ResumeCache(app, "zcode").write(ResumeSnapshot(profile.resumeIdentity, zSessions = listOf(session), zSelected = session,
            items = listOf(TimelineItem("i", "agentMessage", "过期内容"))))
        val vm = zcode(); restored(vm)
        main { state<ZcodeState>(vm).value = vm.state.value.copy(connected = true) }
        val bridge = FakeRpcBridge(vm).also { it.install(); bridges += it }
        bridge.scripted["session/read"] = { throw FakeRpcBridge.FakeRpcError(-32000, "会话已删除") }
        bridge.scripted["catalog"] = { obj("providers" to JSONArray()) }
        bridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
        main { vm.refresh() }
        await { vm.state.value.sessionsFresh && !vm.state.value.busy }
        assertNull(vm.state.value.selected); assertTrue(vm.state.value.items.isEmpty()); assertFalse(vm.state.value.historyCached)
    }

    @Test fun changingTabsKeepsBothChannelsPastBackgroundTimeout() = runBlocking {
        seedProfile(); val c = codex(); val z = zcode(); restored(c); restored(z)
        main {
            state<UiState>(c).value = UiState(profile = profile, saved = true, connected = true, foreground = true)
            state<ZcodeState>(z).value = ZcodeState(connected = true, sessionsFresh = true, recoveryPhase = RecoveryPhase.Ready,
                sessions = listOf(ZSession("z", "保留列表", "/work")))
            field(z, "active").set(z, true); field(z, "visible").set(z, true)
            field(z, "listSyncedAt").set(z, System.currentTimeMillis())
        }
        val cb = FakeRpcBridge(c).also { it.install(); bridges += it }
        val zb = FakeRpcBridge(z).also { it.install(); bridges += it }
        val crpc = field(c, "rpc").get(c); val zrpc = field(z, "rpc").get(z)
        main { c.leave(); z.leave() }
        delay(BACKGROUND_GRACE_MS + 500)
        assertTrue(c.state.value.connected); assertTrue(z.state.value.connected)
        assertSame(crpc, field(c, "rpc").get(c)); assertSame(zrpc, field(z, "rpc").get(z))
        main { z.enter(); z.leave(); z.enter() }
        delay(100)
        assertFalse(z.state.value.busy); assertEquals("保留列表", z.state.value.sessions.single().title)
        assertTrue(cb.unexpected.isEmpty()); assertTrue(zb.unexpected.isEmpty())
    }

    @Test fun returningToZcodeChatRefreshesOnlyHistoryWithoutBlockingOrClearingContent() = runBlocking {
        seedProfile(); val z = zcode(); restored(z)
        main {
            state<ZcodeState>(z).value = ZcodeState(connected = true, sessionsFresh = true,
                selected = ZSession("z", "现有会话", "/work"), draft = "保留草稿",
                items = listOf(TimelineItem("a", "agentMessage", "现有回答")))
            field(z, "active").set(z, true); field(z, "visible").set(z, true)
        }
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        val bridge = FakeRpcBridge(z).also { it.install(); bridges += it }
        var reads = 0
        bridge.scripted["session/read"] = {
            reads++; entered.countDown(); gate.await(5, TimeUnit.SECONDS)
            obj("title" to "现有会话", "phase" to "idle", "items" to JSONArray(), "revision" to 1)
        }
        try {
            main { z.leave(); z.enter() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(z.state.value.busy); assertEquals("现有回答", z.state.value.items.single().text)
            assertEquals("保留草稿", z.state.value.draft)
            main { z.leave(); z.enter() }
        } finally { gate.countDown() }
        await { z.state.value.revision == 1L && z.state.value.recoveryPhase == RecoveryPhase.Ready }
        main { z.leave(); z.enter() }
        delay(100)
        // Leaving cancels the suspended first read; returning must perform one replacement.
        assertEquals(2, reads); assertTrue(bridge.unexpected.isEmpty())
    }

    @After fun cleanup() {
        main { models.forEach { it.viewModelScope.cancel() } }
        bridges.forEach { it.close(); it.assertClean() }
        app.noBackupFilesDir.deleteRecursively()
        instrument.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
    }
}
