package com.gmch.codexremote

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Codex (RemoteViewModel) send-path instrumentation through the real production wiring:
 * real RpcClient over piped JSONL to a scripted app-server, injected into the private rpc
 * field. Covers: SENDING bubble while thread/resume is still blocked, ack receipt, history
 * reconciliation by clientUserMessageId, ack-accepted + history failure, queue path,
 * explicit server rejection, editing during send, and a real disconnect after dispatch.
 * Prefs are isolated per test and deleted afterwards; nothing touches the user's vault.
 */
@RunWith(AndroidJUnit4::class)
class RemoteSendRealtimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestPrefsApp
    private lateinit var vm: RemoteViewModel
    private lateinit var state: MutableStateFlow<UiState>
    private lateinit var bridge: FakeRpcBridge

    private fun freshViewModel() {
        app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        instrumentation.runOnMainSync { vm = RemoteViewModel(app) }
        state = RemoteViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as MutableStateFlow<UiState>
        }
        state.value = UiState(connected = true, foreground = true)
        bridge = FakeRpcBridge(vm)
        bridge.install()
    }

    private fun seedThread(runtime: String, draft: String) {
        state.value = UiState(connected = true, foreground = true,
            selected = SessionInfo("s1", "集成", "/tmp/remote", "", runtime, 0))
        instrumentation.runOnMainSync { vm.draft(draft) }
    }

    private fun vault() = Vault(app.prefsContext)

    /** localKey reads the live profile identity; reflect it instead of guessing the format. */
    private fun remoteKey(type: String, id: String): String {
        val method = RemoteViewModel::class.java.getDeclaredMethod("localKey", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(vm, type, id) as String
    }

    /** Returns false on timeout so tests can assert the wait instead of hanging. */
    private suspend fun await(timeoutMs: Long = 15_000, test: (UiState) -> Boolean): Boolean =
        withTimeout(timeoutMs) {
            while (!test(vm.state.value)) delay(20)
            true
        }

    /** Scripts the idle-thread send flow; returns the reference carrying the clientUserMessageId. */
    private fun scriptTurnStart(
        resumeGate: CountDownLatch? = null, resumeEntered: CountDownLatch? = null,
        turnGate: CountDownLatch? = null, turnEntered: CountDownLatch? = null,
        readGate: CountDownLatch? = null, readError: String? = null
    ): AtomicReference<String> {
        val cmd = AtomicReference("")
        bridge.scripted["thread/resume"] = {
            resumeEntered?.countDown()
            resumeGate?.await(5, TimeUnit.SECONDS)
            obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "idle")),
                "approvalPolicy" to "never", "sandbox" to obj("type" to "dangerFullAccess"))
        }
        bridge.scripted["turn/start"] = { req ->
            turnEntered?.countDown()
            turnGate?.await(5, TimeUnit.SECONDS)
            cmd.set(req.optJSONObject("params")?.string("clientUserMessageId").orEmpty())
            obj("turn" to obj("id" to "t1", "status" to "inProgress")) }
        bridge.scripted["thread/read"] = {
            if (readError != null) throw FakeRpcBridge.FakeRpcError(-32000, readError)
            readGate?.await(5, TimeUnit.SECONDS)
            obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "idle"), "updatedAt" to 0L)) }
        bridge.scripted["thread/turns/list"] = { obj("data" to JSONArray()) }
        bridge.scripted["thread/queue/list"] = { obj("data" to JSONArray()) }
        bridge.scripted["thread/items/list"] = {
            obj("data" to JSONArray().put(obj("item" to obj(
                "id" to "i1", "type" to "userMessage", "status" to "completed", "clientId" to cmd.get(),
                "content" to JSONArray().put(obj("type" to "text", "text" to "任务R"))))), "nextCursor" to "") }
        return cmd
    }

    @After
    fun tearDown() {
        try {
            if (::vm.isInitialized) instrumentation.runOnMainSync {
                vm.background()
                vm.viewModelScope.cancel()
            }
        } finally {
            if (::bridge.isInitialized) bridge.close()
            if (::app.isInitialized)
                instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
        if (::bridge.isInitialized) bridge.assertClean()
    }

    @Test fun resumeBlockedStillShowsSendingThenReconciles() = runBlocking {
        freshViewModel(); seedThread("idle", "任务R")
        val resumeGate = CountDownLatch(1); val readGate = CountDownLatch(1)
        try {
            val cmd = scriptTurnStart(resumeGate = resumeGate, readGate = readGate)
            instrumentation.runOnMainSync { vm.send(false) }
            // thread/resume is still blocked at the fake server; the bubble must already exist.
            assertTrue(await { it.sending && it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.SENDING })
            assertEquals("发送中", outgoingStatusLabel(vm.state.value.outgoing[0]))
            resumeGate.countDown()
            // history read is held back, so the ACCEPTED receipt stays observable.
            assertTrue(await { it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.ACCEPTED })
            assertEquals("已接收", vm.state.value.outgoing[0].receipt)
            readGate.countDown()
            assertTrue(await { !it.busy && it.outgoing.isEmpty() && it.items.any { row -> row.clientId == cmd.get() } })
            assertEquals("任务R", vm.state.value.items.first { it.clientId == cmd.get() }.text)
            assertEquals("", vm.state.value.draft)
            assertNull(vm.state.value.uncertain)
        } finally {
            resumeGate.countDown(); readGate.countDown()
        }
    }

    @Test fun ackAcceptedThenHistoryFailureKeepsAccepted() = runBlocking {
        freshViewModel(); seedThread("idle", "任务R")
        scriptTurnStart(readError = "历史读取失败")
        instrumentation.runOnMainSync { vm.send(false) }
        assertTrue(await { !it.busy && it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.ACCEPTED })
        assertEquals("已接收", vm.state.value.outgoing[0].receipt)
        assertEquals("历史读取失败", vm.state.value.historyError)
        assertNull(vm.state.value.uncertain)
        assertFalse(vm.state.value.sending)
        assertFalse(vm.state.value.error.isNullOrBlank())
    }

    @Test fun activeThreadGoesThroughQueueAndReconcilesByClientId() = runBlocking {
        freshViewModel(); seedThread("active", "任务Q")
        val cmd = AtomicReference("")
        bridge.scripted["thread/resume"] = {
            obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "active")),
                "approvalPolicy" to "never", "sandbox" to obj("type" to "dangerFullAccess")) }
        bridge.scripted["thread/queue/add"] = { req ->
            cmd.set(req.optJSONObject("params")?.string("clientUserMessageId").orEmpty())
            obj("queuedSubmissionId" to "q1") }
        bridge.scripted["thread/read"] = {
            obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "active"), "updatedAt" to 0L)) }
        bridge.scripted["thread/turns/list"] = { obj("data" to JSONArray()) }
        bridge.scripted["thread/items/list"] = { obj("data" to JSONArray(), "nextCursor" to "") }
        bridge.scripted["thread/queue/list"] = {
            obj("data" to JSONArray().put(obj("id" to "q1", "clientUserMessageId" to cmd.get(),
                "input" to JSONArray().put(obj("type" to "text", "text" to "任务Q"))))) }
        instrumentation.runOnMainSync { vm.send(false) }
        assertTrue(await { !it.busy && it.outgoing.isEmpty() && it.queue.isNotEmpty() })
        assertEquals(cmd.get(), vm.state.value.queue[0].clientId)
        assertEquals("任务Q", vm.state.value.queue[0].text)
        assertNull(vm.state.value.uncertain)
        // UI merge shows exactly the queue row, no duplicate bubble.
        val rows = buildChatRows(vm.state.value.items, vm.state.value.outgoing, vm.state.value.queue)
        assertTrue(rows.none { it.outgoing != null })
        assertEquals(1, rows.count { it.queued != null })
    }

    @Test fun explicitServerRejectionShowsFailedAndKeepsDraft() = runBlocking {
        freshViewModel(); seedThread("idle", "任务R")
        val resumeGate = CountDownLatch(1)
        try {
            bridge.scripted["thread/resume"] = {
                resumeGate.await(5, TimeUnit.SECONDS)
                obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "idle")),
                    "approvalPolicy" to "never", "sandbox" to obj("type" to "dangerFullAccess")) }
            bridge.scripted["turn/start"] = { throw FakeRpcBridge.FakeRpcError(-32000, "服务器拒绝该消息") }
            instrumentation.runOnMainSync { vm.send(false) }
            resumeGate.countDown()
            assertTrue(await { !it.busy && it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.FAILED })
            assertEquals("发送失败", outgoingStatusLabel(vm.state.value.outgoing[0]))
            assertEquals("任务R", vm.state.value.draft)
            assertNull(vm.state.value.uncertain)
            assertFalse(vm.state.value.sending)
        } finally {
            resumeGate.countDown()
        }
    }

    @Test fun newDraftTypedDuringSendIsPreserved() = runBlocking {
        freshViewModel(); seedThread("idle", "任务R")
        val resumeGate = CountDownLatch(1); val resumeEntered = CountDownLatch(1)
        try {
            scriptTurnStart(resumeGate = resumeGate, resumeEntered = resumeEntered)
            instrumentation.runOnMainSync { vm.send(false) }
            assertTrue("resume handler 未进入", resumeEntered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { vm.draft("改稿R") }
            resumeGate.countDown()
            assertTrue(await { !it.busy && it.outgoing.isEmpty() && it.items.isNotEmpty() })
            assertEquals("改稿R", vm.state.value.draft)
            assertEquals("改稿R", vault().get(remoteKey("draft", "s1")))
        } finally {
            resumeGate.countDown()
        }
    }

    @Test fun pendingReceiptRestoresUncertainBubbleAfterRestart() = runBlocking {
        freshViewModel()
        vault().put(remoteKey("pending", "s1"), obj("clientId" to "cmd-r", "text" to "恢复R").toString())
        // Explicitly offline: pure restore path, no history RPC, no reader races at teardown.
        state.value = UiState()
        instrumentation.runOnMainSync { vm.openThread(SessionInfo("s1", "集成", "/tmp/remote", "", "idle", 0)) }
        assertTrue(await { it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.UNCERTAIN })
        assertEquals("cmd-r", vm.state.value.outgoing[0].clientId)
        assertEquals("恢复R", vm.state.value.outgoing[0].text)
        assertNotNull(vm.state.value.uncertain)
    }

    @Test fun paginatedHistoryReconcilesOldBubble() = runBlocking {
        freshViewModel()
        @Suppress("UNCHECKED_CAST")
        val map = RemoteViewModel::class.java.getDeclaredField("outgoingBySession")
            .apply { isAccessible = true }.get(vm) as MutableMap<OutgoingKey, MutableList<OutgoingMessage>>
        val record = OutgoingMessage("s1", "old-cmd", "更早的任务", OutgoingState.ACCEPTED)
        map[OutgoingKey(vm.state.value.profile.identity, "s1")] = mutableListOf(record)
        state.value = UiState(connected = true, foreground = true,
            selected = SessionInfo("s1", "集成", "/tmp/remote", "", "idle", 0),
            historyCursor = "c1", outgoing = listOf(record))
        bridge.scripted["thread/items/list"] = {
            obj("data" to JSONArray().put(obj("item" to obj("id" to "i0", "type" to "userMessage",
                "status" to "completed", "clientId" to "old-cmd",
                "content" to JSONArray().put(obj("type" to "text", "text" to "更早的任务"))))), "nextCursor" to "") }
        instrumentation.runOnMainSync { vm.moreHistory() }
        assertTrue(await { !it.busy && it.items.any { row -> row.clientId == "old-cmd" } && it.outgoing.isEmpty() })
        assertTrue(map[OutgoingKey(vm.state.value.profile.identity, "s1")].isNullOrEmpty())
        // Offline reopen of the same thread: the removed record cannot come back at the bottom.
        state.value = state.value.copy(connected = false)
        instrumentation.runOnMainSync { vm.back() }
        instrumentation.runOnMainSync { vm.openThread(SessionInfo("s1", "集成", "/tmp/remote", "", "idle", 0)) }
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertTrue(buildChatRows(vm.state.value.items, vm.state.value.outgoing, vm.state.value.queue)
            .none { it.outgoing != null })
    }

    @Test fun eventFirstThenDisconnectReleasesProtection() = runBlocking {
        freshViewModel(); seedThread("idle", "任务H")
        val turnGate = CountDownLatch(1); val turnEntered = CountDownLatch(1)
        try {
            scriptTurnStart(turnGate = turnGate, turnEntered = turnEntered)
            instrumentation.runOnMainSync { vm.send(false) }
            assertTrue(await { it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.SENDING })
            // Wait for the real dispatch: pending persisted, request at the server.
            assertTrue("turn/start 未进入", turnEntered.await(5, TimeUnit.SECONDS))
            val cmd = vm.state.value.outgoing[0].clientId
            assertNotNull(vm.state.value.uncertain)
            // The formal userMessage arrives first; the ack is still blocked at the server.
            val event = obj("method" to "item/started", "params" to obj("threadId" to "s1",
                "item" to obj("id" to "i1", "type" to "userMessage", "status" to "completed",
                    "clientId" to cmd, "content" to JSONArray().put(obj("type" to "text", "text" to "任务H")))))
            instrumentation.runOnMainSync {
                val method = RemoteViewModel::class.java.getDeclaredMethod("handle", JSONObject::class.java)
                method.isAccessible = true
                method.invoke(vm, event)
            }
            // Formal evidence published by paint must release the pending protection.
            assertTrue(await { it.uncertain == null && it.items.any { row -> row.clientId == cmd } && it.outgoing.isEmpty() })
            assertNull(vault().get(remoteKey("pending", "s1")))
            instrumentation.runOnMainSync { vm.draft("新草稿H") }
            instrumentation.runOnMainSync { vm.disconnect("断线") }
            assertTrue(await { !it.connected })
            // No duplicate 待确认 bubble, no resurrection, the new draft survives.
            assertTrue(vm.state.value.outgoing.isEmpty())
            assertNull(vm.state.value.uncertain)
            assertTrue(vm.state.value.items.any { row -> row.clientId == cmd })
            assertEquals("新草稿H", vm.state.value.draft)
        } finally {
            turnGate.countDown()
        }
    }

    @Test fun reopeningThreadKeepsInFlightSending() = runBlocking {
        freshViewModel(); seedThread("idle", "任务S")
        val turnGate = CountDownLatch(1); val turnEntered = CountDownLatch(1)
        try {
            val cmd = scriptTurnStart(turnGate = turnGate, turnEntered = turnEntered)
            instrumentation.runOnMainSync { vm.send(false) }
            assertTrue(await { it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.SENDING })
            // Only after the real dispatch does the round trip prove record preservation.
            assertTrue("turn/start 未进入", turnEntered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { vm.openThread(SessionInfo("s2", "其他", "/tmp/remote", "", "idle", 0)) }
            instrumentation.runOnMainSync { vm.openThread(SessionInfo("s1", "集成", "/tmp/remote", "", "idle", 0)) }
            // The in-flight record survives the round trip: 发送中 restored, and the
            // settled-protection banner must NOT appear for an unsettled send.
            assertTrue(await { it.selected?.id == "s1" && it.outgoing.size == 1 &&
                it.outgoing[0].state == OutgoingState.SENDING })
            assertTrue(vm.state.value.sending)
            assertFalse(pendingProtectionVisible(vm.state.value.uncertain != null, vm.state.value.sending))
            turnGate.countDown()
            assertTrue(await { !it.busy && !it.sending && it.outgoing.isEmpty() &&
                it.items.any { row -> row.clientId == cmd.get() } })
        } finally {
            turnGate.countDown()
        }
    }

    @Test fun disconnectAfterDispatchMarksUncertainWithProtection() = runBlocking {
        freshViewModel(); seedThread("idle", "任务R")
        val turnGate = CountDownLatch(1); val turnEntered = CountDownLatch(1)
        try {
            scriptTurnStart(turnGate = turnGate, turnEntered = turnEntered)
            instrumentation.runOnMainSync { vm.send(false) }
            assertTrue("turn/start 未进入", turnEntered.await(5, TimeUnit.SECONDS))
            // Real connection loss after dispatch (not an RPC error): the write may have
            // landed, so the receipt must be 结果待确认 with the persisted protection kept.
            instrumentation.runOnMainSync { vm.disconnect("测试断线") }
            turnGate.countDown()
            assertTrue(await { !it.connected && it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.UNCERTAIN })
            assertEquals("结果待确认", outgoingStatusLabel(vm.state.value.outgoing[0]))
            assertNotNull(vm.state.value.uncertain)
            assertEquals("任务R", vm.state.value.draft)
        } finally {
            turnGate.countDown()
        }
    }

    @Test fun formalEventReplacesBubbleWithoutFlicker() = runBlocking {
        freshViewModel(); seedThread("idle", "任务R")
        val turnGate = CountDownLatch(1)
        try {
            scriptTurnStart(turnGate = turnGate)
            instrumentation.runOnMainSync { vm.send(false) }
            assertTrue(await { it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.SENDING })
            val cmd = vm.state.value.outgoing[0].clientId
            // Drive the REAL handle() with an item/started for this message on the main thread.
            val event = obj("method" to "item/started", "params" to obj("threadId" to "s1",
                "item" to obj("id" to "i1", "type" to "userMessage", "status" to "inProgress",
                    "clientId" to cmd, "content" to JSONArray().put(obj("type" to "text", "text" to "任务R")))))
            instrumentation.runOnMainSync {
                val method = RemoteViewModel::class.java.getDeclaredMethod("handle", JSONObject::class.java)
                method.isAccessible = true
                method.invoke(vm, event)
                // Same frame: reconcile must not have run before paint published the items —
                // exactly one row (the bubble) must be visible, never an empty gap.
                val rows = buildChatRows(vm.state.value.items, vm.state.value.outgoing, vm.state.value.queue)
                assertEquals(1, rows.size)
                assertNotNull(rows[0].outgoing)
            }
            // After the throttled publish (and its reconciliation): still exactly one row,
            // now the formal message — buildChatRows hides the overlap before the record goes.
            assertTrue(await {
                val rows = buildChatRows(vm.state.value.items, vm.state.value.outgoing, vm.state.value.queue)
                rows.size == 1 && rows[0].item?.id == "i1"
            })
            turnGate.countDown()
            assertTrue(await { !it.busy && it.outgoing.isEmpty() && vm.state.value.items.any { row -> row.id == "i1" } })
            // The bubble must not resurrect beside the formal message after the ack lands.
            assertEquals(1, buildChatRows(vm.state.value.items, vm.state.value.outgoing, vm.state.value.queue).size)
        } finally {
            turnGate.countDown()
        }
    }
}
