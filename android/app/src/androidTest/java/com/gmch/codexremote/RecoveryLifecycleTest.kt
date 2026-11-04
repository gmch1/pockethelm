package com.gmch.codexremote

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

/** R4–R6: real ViewModels and RPC framing; no system network changes or model calls. */
@RunWith(AndroidJUnit4::class)
class RecoveryLifecycleTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(vm: Any) = vm.javaClass.getDeclaredField("mutable").apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun app() = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
    private fun clear(app: TestPrefsApp) { inst.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault"); app.prefsContext.noBackupFilesDir.deleteRecursively() }
    private suspend fun await(test: () -> Boolean) = withTimeout(5_000) { while (!test()) delay(10) }
    private suspend fun fence(vm: Any) {
        val rpc = vm.javaClass.getDeclaredField("rpc").apply { isAccessible = true }.get(vm) as RpcClient
        rpc.call("fence", obj(), timeout = 3000)
    }
    private fun activate(vm: ZcodeViewModel) {
        for (field in listOf("active", "visible")) ZcodeViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }.set(vm, true)
    }
    private fun readB() = obj("title" to "B", "revision" to 1, "logEpoch" to "e",
        "selection" to obj("providerId" to "p", "modelId" to "model-B"),
        "phase" to "idle", "items" to JSONArray(), "queue" to JSONArray())
    private fun catalog() = obj("providers" to JSONArray().put(obj("id" to "p", "name" to "plan", "guard" to "g",
        "models" to JSONArray().put(obj("id" to "model-B")))))
    private fun quota() = obj("planKind" to "individual-coding-plan", "limits" to JSONArray().put(
        obj("unit" to 3, "number" to 5, "percentage" to 20.0, "currentValue" to 4.0)))

    private suspend fun codexResume(existing: Boolean, tabSwitch: Boolean) {
        val app = app(); lateinit var vm: RemoteViewModel
        inst.runOnMainSync { vm = RemoteViewModel(app) }
        val state = state<UiState>(vm); state.value = UiState(saved = true, connected = true, foreground = true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val reads = AtomicInteger()
        val bridge = FakeRpcBridge(vm)
        val blockedRead = if (existing) 2 else 1
        bridge.scripted["thread/read"] = {
            if (reads.incrementAndGet() == blockedRead) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            obj("thread" to obj("id" to "A", "cwd" to "/tmp/test", "status" to obj("type" to "notLoaded")))
        }
        bridge.scripted["thread/turns/list"] = { obj("data" to JSONArray()) }
        bridge.scripted["thread/items/list"] = { obj("data" to JSONArray().put(obj("item" to obj("id" to "message-A", "type" to "agentMessage", "text" to "existing content")))) }
        bridge.scripted["fence"] = { obj() }; bridge.install()
        try {
            inst.runOnMainSync { vm.openThread(SessionInfo("A", "A", "/tmp/test", "", "idle", 0)) }
            if (existing) {
                await { state.value.recoveryPhase == RecoveryPhase.Ready }
                inst.runOnMainSync { vm.retryHistory() }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            inst.runOnMainSync { vm.draft("preserved draft"); if (tabSwitch) vm.leave() else vm.background(); vm.foreground() }
            release.countDown()
            await { state.value.recoveryPhase == RecoveryPhase.Ready }
            fence(vm)
            assertEquals(blockedRead + 1, reads.get())
            assertEquals("existing content", state.value.items.single().text)
            assertEquals("preserved draft", state.value.draft)
            assertFalse(state.value.historyLoading); assertNull(state.value.historyError)
            // A completed, fresh target must still dedupe a subsequent short visit.
            inst.runOnMainSync { vm.background(); vm.foreground() }
            fence(vm)
            assertEquals(blockedRead + 1, reads.get())
            assertEquals(RecoveryPhase.Ready, state.value.recoveryPhase)
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnect(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); clear(app)
        }
    }
    @Test fun codexQuickReturnRestartsFirstRead(): Unit = runBlocking { codexResume(false, false) }
    @Test fun codexTabReturnRestartsInterruptedRefreshAndPreservesContent(): Unit = runBlocking { codexResume(true, true) }

    private suspend fun entryChoice(mode: String) {
        val app = app(); lateinit var vm: ZcodeViewModel
        inst.runOnMainSync { vm = ZcodeViewModel(app); activate(vm) }
        val state = state<ZcodeState>(vm)
        state.value = ZcodeState(connected = true, selection = ZSelection("p", "model-A"), selectionGuard = "g",
            providers = listOf(ZProvider("p", "plan", "subscription", "g", listOf("model-A", "model-B", "model-C").map { ZModel(it, emptyList()) })))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val reads = AtomicInteger()
        val quotas = ConcurrentLinkedQueue<String>()
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["session/read"] = {
            if (reads.incrementAndGet() == 1) {
                entered.countDown()
                if (mode == "retry") throw FakeRpcBridge.FakeRpcError(-32000, "first read failed")
                check(release.await(5, TimeUnit.SECONDS))
            }
            readB()
        }
        bridge.scripted["quota"] = { quotas.add(it.getJSONObject("params").getJSONObject("selection").getString("modelId")); quota() }
        bridge.scripted["fence"] = { obj() }; bridge.install()
        try {
            inst.runOnMainSync { vm.open(ZSession("B", "B", "/tmp/test")) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (mode == "retry") {
                await { state.value.recoveryPhase == RecoveryPhase.Failed }
                inst.runOnMainSync { vm.retryLoad() }
            } else inst.runOnMainSync {
                if (mode == "manual") vm.choose(ZSelection("p", "model-C"))
                vm.background(); vm.foreground()
            }
            release.countDown()
            await { state.value.recoveryPhase == RecoveryPhase.Ready }
            assertEquals("model-B", state.value.currentSelection?.model)
            assertEquals(if (mode == "manual") "model-C" else "model-B", state.value.selection?.model)
            assertEquals(2, reads.get())
            if (mode != "manual") { await { quotas.isNotEmpty() }; assertEquals("model-B", quotas.single()) }
            else { fence(vm); assertTrue(quotas.isEmpty()) }
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnectManually(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); clear(app)
        }
    }
    @Test fun zcodeResumePreservesEntrySelectionIntent(): Unit = runBlocking { entryChoice("background") }
    @Test fun zcodeRetryPreservesEntrySelectionIntent(): Unit = runBlocking { entryChoice("retry") }
    @Test fun manualChoiceSupersedesInterruptedEntrySync(): Unit = runBlocking { entryChoice("manual") }

    private suspend fun runtimeCatalog(background: Boolean) {
        val app = app(); lateinit var vm: ZcodeViewModel
        inst.runOnMainSync { vm = ZcodeViewModel(app); activate(vm) }
        val state = state<ZcodeState>(vm); state.value = ZcodeState(connected = true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
        bridge.scripted["catalog"] = {
            if (calls.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            catalog()
        }
        bridge.scripted["quota"] = { quota() }
        bridge.scripted["session/read"] = { readB() }; bridge.scripted["fence"] = { obj() }; bridge.install()
        try {
            inst.runOnMainSync { vm.refresh() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            inst.runOnMainSync { vm.open(ZSession("B", "B", "/tmp/test")); if (background) { vm.background(); vm.foreground() } }
            release.countDown()
            await { state.value.recoveryPhase == RecoveryPhase.Ready && state.value.providers.isNotEmpty() }
            fence(vm)
            assertEquals(if (background) 2 else 1, calls.get())
            assertEquals("g", state.value.selectionGuard)
            assertEquals("model-B", state.value.selection?.model)
            await { state.value.quotaSummary != "额度 ▾" }
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnectManually(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); clear(app)
        }
    }
    @Test fun navigationDoesNotCancelRuntimeCatalog(): Unit = runBlocking { runtimeCatalog(false) }
    @Test fun foregroundResumesInterruptedRuntimeCatalog(): Unit = runBlocking { runtimeCatalog(true) }

    @Test fun navigationKeepsGlobalPendingReceiptRecovery(): Unit = runBlocking {
        val app = app(); lateinit var vm: ZcodeViewModel
        inst.runOnMainSync { vm = ZcodeViewModel(app) }
        val state = state<ZcodeState>(vm); state.value = ZcodeState(connected = true, pending = true)
        ZcodeViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }.set(vm,
            obj("method" to "session/send", "params" to obj("commandId" to "cmd-A", "sessionId" to "A", "text" to "draft-A")))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
        bridge.scripted["catalog"] = { obj("providers" to JSONArray()) }
        bridge.scripted["command/query"] = {
            calls.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            obj("ack" to obj("status" to "accepted", "result" to obj("delivery" to "queue")))
        }
        bridge.scripted["session/read"] = { readB() }; bridge.install()
        try {
            inst.runOnMainSync { vm.refresh() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            inst.runOnMainSync { vm.open(ZSession("B", "B", "/tmp/test")) }
            release.countDown()
            await { state.value.recoveryPhase == RecoveryPhase.Ready && !state.value.pending }
            assertEquals(1, calls.get()); assertEquals("B", state.value.selected?.id)
            assertTrue(state.value.outgoing.isEmpty())
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnectManually(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); clear(app)
        }
    }

    @Test fun codexPausedGlobalReadsResumeWithoutReloadingFreshHistory(): Unit = runBlocking {
        for (models in listOf(true, false)) {
            val app = app(); lateinit var vm: RemoteViewModel
            inst.runOnMainSync { vm = RemoteViewModel(app) }
            val state = state<UiState>(vm)
            state.value = UiState(saved = true, connected = true, foreground = true, recoveryPhase = RecoveryPhase.Ready,
                selected = SessionInfo("A", "A", "/tmp/test", "", "idle", 0))
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
            val bridge = FakeRpcBridge(vm)
            bridge.scripted[if (models) "model/list" else "account/rateLimits/read"] = {
                if (calls.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                if (models) obj("data" to JSONArray().put(obj("id" to "model-X"))) else obj()
            }
            bridge.install()
            try {
                inst.runOnMainSync { if (models) vm.refreshModels() else vm.refreshQuota() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                inst.runOnMainSync { vm.background(); vm.foreground() }
                release.countDown()
                await { if (models) state.value.models.isNotEmpty() else state.value.quota != null }
                assertEquals(2, calls.get())
                assertEquals(RecoveryPhase.Ready, state.value.recoveryPhase)
                assertFalse(state.value.modelsLoading || state.value.quotaLoading)
            } finally {
                release.countDown(); inst.runOnMainSync { vm.disconnect(); vm.viewModelScope.cancel() }
                bridge.close(); bridge.assertClean(); clear(app)
            }
        }
    }

    @Test fun navigationKeepsRuntimeProjectRead(): Unit = runBlocking {
        val app = app(); lateinit var vm: ZcodeViewModel
        inst.runOnMainSync { vm = ZcodeViewModel(app) }
        val state = state<ZcodeState>(vm)
        state.value = ZcodeState(connected = true, selected = ZSession("A", "A", "/tmp/test"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["projects"] = {
            calls.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            obj("projects" to JSONArray().put(obj("id" to "project", "name" to "project", "roots" to JSONArray().put("/tmp/test"))), "sessions" to JSONArray())
        }
        bridge.scripted["catalog"] = { obj("providers" to JSONArray()) }
        bridge.scripted["session/read"] = { readB() }; bridge.install()
        try {
            inst.runOnMainSync { vm.refresh() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            inst.runOnMainSync { vm.open(ZSession("B", "B", "/tmp/test")) }
            release.countDown()
            await { state.value.recoveryPhase == RecoveryPhase.Ready && state.value.projects.isNotEmpty() }
            assertEquals(1, calls.get()); assertEquals("B", state.value.selected?.id)
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnectManually(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); clear(app)
        }
    }

    @Test fun environmentChangeStillCancelsOldRuntimeCatalog(): Unit = runBlocking {
        val app = app(); lateinit var vm: ZcodeViewModel
        inst.runOnMainSync { vm = ZcodeViewModel(app); activate(vm) }
        val state = state<ZcodeState>(vm); state.value = ZcodeState(connected = true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
        bridge.scripted["catalog"] = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); catalog() }
        bridge.install()
        try {
            inst.runOnMainSync { vm.refresh() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            @Suppress("UNCHECKED_CAST")
            val entries = SessionRecovery::class.java.getDeclaredField("entries").apply { isAccessible = true }.get(vm.recovery) as Map<String, Any>
            val entry = entries.getValue("aux:catalog")
            val catalogJob = entry.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(entry) as Job
            inst.runOnMainSync {
                vm.disconnectManually()
                Vault(app.prefsContext).put("profile", ConnectionProfile(name = "other environment", root = "/tmp").json().toString())
                vm.foreground() // The explicit disconnect prevents any real connection attempt.
            }
            withTimeout(3000) { catalogJob.join() }
            release.countDown()
            assertTrue(catalogJob.isCancelled)
            assertTrue(state.value.providers.isEmpty()); assertFalse(state.value.connected)
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnectManually(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); clear(app)
        }
    }


    @Test fun codexPausedStatusEnrichmentResumesWithoutReloadingList(): Unit = runBlocking {
        val app = app(); lateinit var vm: RemoteViewModel
        inst.runOnMainSync { vm = RemoteViewModel(app) }
        val state = state<UiState>(vm); state.value = UiState(saved = true, connected = true, foreground = true, projects = listOf("/tmp"))
        RemoteViewModel::class.java.getDeclaredField("projectsSyncedAt").apply { isAccessible = true }.setLong(vm, System.currentTimeMillis())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val lists = AtomicInteger(); val statuses = AtomicInteger()
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["thread/list"] = {
            lists.incrementAndGet()
            obj("data" to JSONArray().put(obj("id" to "A", "cwd" to "/tmp", "status" to obj("type" to "idle"))))
        }
        bridge.scripted["thread/turns/list"] = {
            if (statuses.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            obj("data" to JSONArray().put(obj("id" to "turn-A", "status" to "completed")))
        }
        bridge.scripted["model/list"] = { obj("data" to JSONArray()) }
        bridge.scripted["account/rateLimits/read"] = { obj() }
        bridge.scripted["fs/readDirectory"] = { obj("entries" to JSONArray()) }
        bridge.scripted["fence"] = { obj() }; bridge.install()
        try {
            inst.runOnMainSync { vm.refresh() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            await { state.value.recoveryPhase == RecoveryPhase.Ready }
            inst.runOnMainSync { vm.background(); vm.foreground() }
            release.countDown()
            await { state.value.sessions.singleOrNull()?.lastTurnStatus == "completed" }
            fence(vm)
            assertEquals(2, statuses.get()); assertEquals(1, lists.get())
        } finally {
            release.countDown(); inst.runOnMainSync { vm.disconnect(); vm.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); clear(app)
        }
    }

}
