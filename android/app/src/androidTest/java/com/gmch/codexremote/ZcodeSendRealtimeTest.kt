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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Send-path instrumentation for ZcodeViewModel through its real production wiring: a real
 * RpcClient over piped JSONL to a scripted bridge, injected into the private rpc field.
 * Covers: bubble visible before the ack, real ack receipts, queue reconciliation, explicit
 * ack rejection vs bridge errors, late/switched sessions, draft preservation, disconnect.
 * Prefs are isolated per test and deleted afterwards; nothing touches the user's vault.
 */
@RunWith(AndroidJUnit4::class)
class ZcodeSendRealtimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestPrefsApp
    private lateinit var vm: ZcodeViewModel
    private lateinit var state: MutableStateFlow<ZcodeState>
    private lateinit var bridge: FakeRpcBridge

    // session/read carries only the session target, so the send handler records
    // sessionId -> (commandId, text) here and the read handler answers from it.
    private val sentCommands = ConcurrentHashMap<String, Pair<String, String>>()

    private fun freshViewModel() {
        app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as MutableStateFlow<ZcodeState>
        }
        state.value = ZcodeState(connected = true)
        bridge = FakeRpcBridge(vm)
        bridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
        bridge.install()
    }

    private fun seedSession(id: String, draft: String) {
        state.value = ZcodeState(connected = true,
            selected = ZSession(id, "会话$id", "/tmp/zcode-send", project = "p1"),
            providers = listOf(ZProvider("p1", "计划一", "subscription", "guard1", listOf(ZModel("m1", emptyList())))),
            selection = ZSelection("p1", "m1"), selectionGuard = "guard1")
        instrumentation.runOnMainSync { vm.draft(draft) }
    }

    private fun vault() = Vault(app.prefsContext)
    private fun draftKey(sessionId: String) = "zcode:draft:null:$sessionId"

    /** Returns false on timeout so tests can assert the wait instead of hanging. */
    private suspend fun await(timeoutMs: Long = 15_000, test: (ZcodeState) -> Boolean): Boolean =
        withTimeout(timeoutMs) {
            while (!test(vm.state.value)) delay(20)
            true
        }

    private fun scriptSend(gate: CountDownLatch? = null, entered: CountDownLatch? = null,
                           respond: (JSONObject) -> JSONObject) {
        bridge.scripted["session/send"] = { req ->
            val p = req.optJSONObject("params") ?: obj()
            sentCommands[p.string("sessionId")] = p.string("commandId") to p.string("text")
            entered?.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            respond(req)
        }
    }

    /** session/read answers from the recorded send per session; sessions without a send show empty. */
    private fun scriptRead(readGate: CountDownLatch? = null) {
        bridge.scripted["session/read"] = { req ->
            val p = req.optJSONObject("params") ?: obj()
            readGate?.await(5, TimeUnit.SECONDS)
            val record = sentCommands[p.string("sessionId")]
            val items = if (record == null) JSONArray() else JSONArray().put(obj(
                "id" to "r1", "kind" to "userInput", "text" to record.second,
                "status" to "success", "commandId" to record.first))
            readPayload(p.string("sessionId"), items, JSONArray())
        }
    }

    private fun readPayload(sessionId: String, items: JSONArray, queue: JSONArray) = obj(
        "items" to items, "logEpoch" to "e1", "revision" to 1L, "title" to "会话", "phase" to "idle",
        "queue" to queue, "mode" to "yolo", "hasMore" to false)

    private fun selectReflection(session: ZSession) {
        val method = ZcodeViewModel::class.java.getDeclaredMethod("select", ZSession::class.java)
        method.isAccessible = true
        instrumentation.runOnMainSync { method.invoke(vm, session) }
    }

    @After
    fun tearDown() {
        try {
            if (::vm.isInitialized) instrumentation.runOnMainSync {
                vm.leave()
                vm.viewModelScope.cancel()
            }
        } finally {
            if (::bridge.isInitialized) bridge.close()
            if (::app.isInitialized)
                instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
        if (::bridge.isInitialized) bridge.assertClean()
    }

    @Test fun bubbleVisibleBeforeAckThenAcceptedThenReconciled() = runBlocking {
        freshViewModel(); seedSession("sessA", "任务A")
        val sendGate = CountDownLatch(1); val readGate = CountDownLatch(1)
        try {
            scriptSend(gate = sendGate) { obj("status" to "accepted", "result" to obj("delivery" to "startNow")) }
            scriptRead(readGate = readGate)
            instrumentation.runOnMainSync { vm.send() }
            // The dispatch is still blocked at the bridge, yet the message is already on screen.
            assertTrue(await { it.sending && it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.SENDING })
            assertEquals("发送中", outgoingStatusLabel(vm.state.value.outgoing[0]))
            val cmd = vm.state.value.outgoing[0].clientId
            sendGate.countDown()
            // read is held back, so the ACCEPTED receipt stays observable.
            assertTrue(await { it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.ACCEPTED })
            assertEquals("已接收", vm.state.value.outgoing[0].receipt)
            readGate.countDown()
            assertTrue(await { !it.busy && it.outgoing.isEmpty() && it.items.any { row -> row.clientId == cmd } })
            assertEquals("任务A", vm.state.value.items.first { it.clientId == cmd }.text)
            assertEquals("", vm.state.value.draft)
            assertEquals(null, vm.state.value.error)
        } finally {
            sendGate.countDown(); readGate.countDown()
        }
    }

    @Test fun queueReceiptDedupesAgainstQueueSnapshot() = runBlocking {
        freshViewModel(); seedSession("sessA", "任务B")
        val cmd = AtomicReference("")
        try {
            scriptSend { req ->
                cmd.set(req.optJSONObject("params")?.string("commandId").orEmpty())
                obj("status" to "accepted", "result" to obj("delivery" to "queue")) }
            bridge.scripted["session/read"] = { req ->
                val p = req.optJSONObject("params") ?: obj()
                val record = sentCommands[p.string("sessionId")]
                readPayload(p.string("sessionId"), JSONArray(),
                    JSONArray().put(obj("queueItemId" to "q1", "sourceCommandId" to record?.first.orEmpty(),
                        "text" to record?.second.orEmpty()))) }
            instrumentation.runOnMainSync { vm.send() }
            assertTrue(await { !it.busy && it.outgoing.isEmpty() && it.queue.isNotEmpty() })
            assertEquals(cmd.get(), vm.state.value.queue[0].clientId)
            assertEquals("任务B", vm.state.value.queue[0].text)
            // UI merge: the queue row shows, no duplicate bubble beside it.
            val rows = buildChatRows(vm.state.value.items, vm.state.value.outgoing, vm.state.value.queue)
            assertTrue(rows.none { it.outgoing != null })
            assertEquals(1, rows.count { it.queued != null })
        } finally {
            // no gates held in this test
        }
    }

    @Test fun explicitAckRejectionShowsFailedAndKeepsDraft() = runBlocking {
        freshViewModel(); seedSession("sessA", "任务C")
        scriptSend { obj("status" to "rejected", "reasonCode" to "rate_limited") }
        instrumentation.runOnMainSync { vm.send() }
        assertTrue(await { !it.busy && it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.FAILED })
        assertEquals("发送失败", outgoingStatusLabel(vm.state.value.outgoing[0]))
        assertEquals("任务C", vm.state.value.draft)
        assertFalse(vm.state.value.pending)
        assertFalse(vm.state.value.sending)
    }

    @Test fun bridgeErrorStaysUncertainWithPendingProtection() = runBlocking {
        freshViewModel(); seedSession("sessA", "任务D")
        scriptSend { throw FakeRpcBridge.FakeRpcError(-32000, "桥接器内部错误") }
        instrumentation.runOnMainSync { vm.send() }
        assertTrue(await { !it.busy && it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.UNCERTAIN })
        assertEquals("结果待确认", outgoingStatusLabel(vm.state.value.outgoing[0]))
        assertTrue(vm.state.value.pending)
        assertEquals("任务D", vm.state.value.draft)
    }

    @Test fun switchSessionIsolatesLateAckAndReturnsReconciled() = runBlocking {
        freshViewModel(); seedSession("sessA", "任务E")
        val sendGate = CountDownLatch(1)
        try {
            scriptSend(gate = sendGate) { obj("status" to "accepted", "result" to obj("delivery" to "startNow")) }
            scriptRead()
            instrumentation.runOnMainSync { vm.send() }
            assertTrue(await { it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.SENDING })
            // The send action holds the actions mutex, so a real vm.open(B) would block until
            // the ack lands. Simulate the switch directly instead: select B on main, give B a
            // persisted draft with the same text, and release the ack while B is on screen.
            selectReflection(ZSession("sessB", "会话B", "/tmp/zcode-send"))
            instrumentation.runOnMainSync { vm.draft("任务E") }
            sendGate.countDown()
            assertTrue(await { !it.busy && it.selected?.id == "sessB" && !it.sending && it.outgoing.isEmpty() })
            // The late ack belongs to sessA: B's same-text draft stays, nothing leaks in.
            assertEquals("任务E", vm.state.value.draft)
            assertEquals("任务E", vault().get(draftKey("sessB")))
            assertEquals("", vault().get(draftKey("sessA")))
            // Reopen A for real: its recorded send reconciles the bubble into history.
            instrumentation.runOnMainSync { vm.back() }
            instrumentation.runOnMainSync { vm.open(ZSession("sessA", "会话A", "/tmp/zcode-send")) }
            val cmd = sentCommands.getValue("sessA").first
            assertTrue(await { !it.busy && it.selected?.id == "sessA" && it.outgoing.isEmpty() &&
                it.items.any { row -> row.clientId == cmd } })
            assertEquals("任务E", vm.state.value.items.first { it.clientId == cmd }.text)
        } finally {
            sendGate.countDown()
        }
    }

    @Test fun newDraftTypedDuringSendIsPreserved() = runBlocking {
        freshViewModel(); seedSession("sessA", "任务F")
        val sendGate = CountDownLatch(1); val sendEntered = CountDownLatch(1)
        try {
            scriptSend(gate = sendGate, entered = sendEntered) { obj("status" to "accepted", "result" to obj("delivery" to "startNow")) }
            scriptRead()
            instrumentation.runOnMainSync { vm.send() }
            assertTrue("send handler 未进入", sendEntered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { vm.draft("改稿F") }
            sendGate.countDown()
            assertTrue(await { !it.busy && it.outgoing.isEmpty() && it.items.isNotEmpty() })
            assertEquals("改稿F", vm.state.value.draft)
            assertEquals("改稿F", vault().get(draftKey("sessA")))
        } finally {
            sendGate.countDown()
        }
    }

    @Test fun disconnectMarksInFlightSendingAsUncertain() = runBlocking {
        freshViewModel(); seedSession("sessA", "任务G")
        val sendGate = CountDownLatch(1)
        try {
            scriptSend(gate = sendGate) { obj("status" to "accepted") }
            instrumentation.runOnMainSync { vm.send() }
            assertTrue(await { it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.SENDING })
            // Leaving the screen now keeps the channel briefly; explicitly simulate a real disconnect.
            instrumentation.runOnMainSync {
                ZcodeViewModel::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(vm)
            }
            assertTrue(await { it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.UNCERTAIN })
            assertEquals("结果待确认", outgoingStatusLabel(vm.state.value.outgoing[0]))
            assertTrue(vm.state.value.pending)
        } finally {
            sendGate.countDown()
        }
    }

    @Test fun pendingReceiptRestoresBubbleThenRecoveryRejectionMarksFailed() = runBlocking {
        freshViewModel(); seedSession("sessA", "恢复任务")
        val pendingField = ZcodeViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }
        pendingField.set(vm, obj("method" to "session/send",
            "params" to obj("commandId" to "cmd-9", "sessionId" to "sessA", "text" to "恢复任务")))
        scriptRead()
        instrumentation.runOnMainSync { vm.open(ZSession("sessA", "会话A", "/tmp/zcode-send")) }
        // Fresh map after a restart: the persisted receipt must still restore the bubble.
        assertTrue(await { !it.busy && it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.UNCERTAIN })
        assertEquals("cmd-9", vm.state.value.outgoing[0].clientId)
        bridge.scripted["catalog"] = { obj("providers" to JSONArray()) }
        bridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
        bridge.scripted["command/query"] = { obj("ack" to obj("status" to "rejected", "reasonCode" to "rate_limited")) }
        instrumentation.runOnMainSync { vm.refresh() }
        // The recovered command was explicitly rejected: FAILED for exactly this session.
        assertTrue(await { !it.busy && it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.FAILED })
        assertEquals("发送失败", outgoingStatusLabel(vm.state.value.outgoing[0]))
        assertEquals("恢复任务", vm.state.value.draft)
        assertEquals("恢复任务", vault().get(draftKey("sessA")))
        assertFalse(vm.state.value.pending)
    }

    @Test fun returningToSessionKeepsInFlightSending() = runBlocking {
        freshViewModel(); seedSession("sessA", "任务S")
        val sendGate = CountDownLatch(1)
        try {
            scriptSend(gate = sendGate) { obj("status" to "accepted", "result" to obj("delivery" to "startNow")) }
            scriptRead()
            instrumentation.runOnMainSync { vm.send() }
            assertTrue(await { it.outgoing.size == 1 && it.outgoing[0].state == OutgoingState.SENDING })
            // Switch away and back while the request is still in flight.
            selectReflection(ZSession("sessB", "会话B", "/tmp/zcode-send"))
            selectReflection(ZSession("sessA", "会话A", "/tmp/zcode-send"))
            // The persisted receipt must not downgrade the live SENDING record.
            assertTrue(await { it.selected?.id == "sessA" && it.outgoing.size == 1 &&
                it.outgoing[0].state == OutgoingState.SENDING })
            sendGate.countDown()
            assertTrue(await { !it.busy && it.outgoing.isEmpty() && it.items.isNotEmpty() })
        } finally {
            sendGate.countDown()
        }
    }

    @Test fun recoveryRejectionBeforeSelectCreatesFailedRecord() = runBlocking {
        // connect() order: recoverPending runs while the target session was never selected,
        // so the outgoing map is empty — the rejection must still create the FAILED record.
        freshViewModel()
        vault().put(draftKey("sessA"), "恢复E")
        val pendingField = ZcodeViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }
        pendingField.set(vm, obj("method" to "session/send",
            "params" to obj("commandId" to "cmd-8", "sessionId" to "sessA", "text" to "恢复E")))
        bridge.scripted["catalog"] = { obj("providers" to JSONArray()) }
        bridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
        bridge.scripted["command/query"] = { obj("ack" to obj("status" to "stale", "reasonCode" to "already_done")) }
        scriptRead()
        instrumentation.runOnMainSync { vm.refresh() }
        assertTrue(await { !it.busy && !it.pending })
        @Suppress("UNCHECKED_CAST")
        val map = ZcodeViewModel::class.java.getDeclaredField("outgoingBySession").apply { isAccessible = true }
            .get(vm) as MutableMap<OutgoingKey, MutableList<OutgoingMessage>>
        assertEquals(OutgoingState.FAILED, map[OutgoingKey("", "sessA")]?.single()?.state)
        instrumentation.runOnMainSync { vm.open(ZSession("sessA", "会话A", "/tmp/zcode-send")) }
        assertTrue(await { !it.busy && it.selected?.id == "sessA" && it.outgoing.size == 1 &&
            it.outgoing[0].state == OutgoingState.FAILED })
        assertEquals("发送失败", outgoingStatusLabel(vm.state.value.outgoing[0]))
        assertEquals("恢复E", vm.state.value.draft)
        assertFalse(vm.state.value.pending)
    }

    @Test fun pendingGuardBlocksSendAsFailedWithoutTouchingProtection() = runBlocking {
        freshViewModel(); seedSession("sessA", "被挡的消息")
        val pendingField = ZcodeViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }
        pendingField.set(vm, obj("method" to "session/stop",
            "params" to obj("commandId" to "cmd-old", "sessionId" to "sessA")))
        state.value = state.value.copy(pending = true)
        instrumentation.runOnMainSync { vm.send() }
        // Nothing was dispatched: the local failure is FAILED, the previous pending
        // protection and its command stay untouched, and no session/send reaches the bridge.
        assertTrue(await { !it.busy && it.outgoing.isNotEmpty() && it.outgoing[0].state == OutgoingState.FAILED })
        assertEquals("被挡的消息", vm.state.value.draft)
        assertTrue(vm.state.value.pending)
        assertEquals("cmd-old", (pendingField.get(vm) as? JSONObject)?.optJSONObject("params")?.string("commandId"))
        assertTrue("不应发出 session/send：${bridge.unexpected}", bridge.unexpected.isEmpty())
    }

    @Test fun olderHistoryPageReconcilesOutgoingRecord() = runBlocking {
        freshViewModel(); seedSession("sessA", "")
        scriptRead()
        @Suppress("UNCHECKED_CAST")
        val map = ZcodeViewModel::class.java.getDeclaredField("outgoingBySession").apply { isAccessible = true }
            .get(vm) as MutableMap<OutgoingKey, MutableList<OutgoingMessage>>
        val record = OutgoingMessage("sessA", "old-cmd", "更早的ZCode任务", OutgoingState.ACCEPTED)
        map[OutgoingKey("", "sessA")] = mutableListOf(record)
        state.value = state.value.copy(outgoing = listOf(record), beforeRow = 10.0,
            logEpoch = "e1", revision = 1L,
            items = listOf(TimelineItem("new1", "agentMessage", "回复", "", "")))
        bridge.scripted["session/history"] = {
            obj("items" to JSONArray().put(obj("id" to "old1", "kind" to "userInput",
                "text" to "更早的ZCode任务", "status" to "success", "commandId" to "old-cmd")),
                "logEpoch" to "e1", "revision" to 1L, "hasMore" to false, "beforeRowId" to JSONObject.NULL) }
        instrumentation.runOnMainSync { vm.history() }
        assertTrue(await { !it.busy && it.items.any { row -> row.clientId == "old-cmd" } && it.outgoing.isEmpty() })
        // Reopen after the reconciliation: the removed record cannot reappear at the bottom.
        instrumentation.runOnMainSync { vm.back() }
        instrumentation.runOnMainSync { vm.open(ZSession("sessA", "会话A", "/tmp/zcode-send")) }
        assertTrue(await { !it.busy && it.selected?.id == "sessA" && it.outgoing.isEmpty() })
        assertTrue(map[OutgoingKey("", "sessA")].isNullOrEmpty())
    }
}
