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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Interaction answering through the real production wiring (task 2026-11-02-interaction-approvals):
 * real RpcClient over piped JSONL injected into the private rpc field.
 *
 * Codex: approval requests reach handle(), decisions echo the typed request id, sent-state
 * waits for resolution, stale/double decides never write, resolved/turn-end/disconnect withdraw.
 * ZCode: answers persist metadata before the RPC (never the answer text), definite
 * acceptance stays disabled until an authoritative snapshot confirms the end, rejection and
 * preemption clear, recovery reconciles command/query against presence without resending.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // ---------------- Codex approvals ----------------

    private lateinit var codexApp: TestPrefsApp
    private lateinit var codex: RemoteViewModel
    private lateinit var codexState: MutableStateFlow<UiState>
    private lateinit var codexBridge: FakeRpcBridge

    private fun freshCodex() {
        codexApp = TestPrefsApp(UUID.randomUUID().toString())
        codexApp.attach(instrumentation.targetContext)
        instrumentation.runOnMainSync { codex = RemoteViewModel(codexApp) }
        codexState = RemoteViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(codex) as MutableStateFlow<UiState>
        }
        codexState.value = UiState(connected = true, foreground = true,
            selected = SessionInfo("s1", "集成", "/tmp/remote", "", "active", 0))
        codexBridge = FakeRpcBridge(codex)
        codexBridge.install()
    }

    /** Feeds a server message into the production event path (same entry as the RpcClient onEvent). */
    private fun codexDispatch(message: JSONObject) {
        val handle = RemoteViewModel::class.java.getDeclaredMethod("handle", JSONObject::class.java)
        handle.isAccessible = true
        instrumentation.runOnMainSync { handle.invoke(codex, message) }
    }

    private fun commandApproval(id: Any, vararg decisions: Any): JSONObject = obj(
        "id" to id, "method" to "item/commandExecution/requestApproval",
        "params" to obj("threadId" to "s1", "turnId" to "u1", "itemId" to "c1", "kind" to "command",
            "command" to "rm -rf build", "cwd" to "/tmp/remote",
            "availableDecisions" to JSONArray().apply { decisions.forEach { put(it) } }))

    private suspend fun awaitCodex(timeoutMs: Long = 15_000, test: (UiState) -> Boolean): Boolean =
        withTimeout(timeoutMs) { while (!test(codex.state.value)) delay(20); true }

    @Test fun approvalDecisionEchoesTypedIdThenResolves() = runBlocking {
        freshCodex()
        codexDispatch(commandApproval(7, "accept", "acceptForSession", "decline"))
        assertTrue(awaitCodex { it.approvals.size == 1 })
        val approval = codex.state.value.approvals.single()
        assertEquals("n:7", approval.key)
        assertEquals(listOf("accept", "acceptForSession", "decline"), approval.decisions)
        assertEquals("command", approval.kind)

        instrumentation.runOnMainSync { codex.decide(approval, "accept") }
        withTimeout(10_000) { while (codexBridge.clientWrites.isEmpty()) delay(20) }
        val write = codexBridge.clientWrites.single()
        // The numeric id must stay numeric — never coerced to a string.
        assertEquals(7, write.getInt("id"))
        assertEquals("accept", write.getJSONObject("result").string("decision"))
        assertTrue(awaitCodex { it.approvals.singleOrNull()?.sent == true })

        // Another client answered first: the card withdraws and never comes back.
        codexDispatch(obj("method" to "serverRequest/resolved",
            "params" to obj("threadId" to "s1", "requestId" to 7)))
        assertTrue(awaitCodex { it.approvals.isEmpty() })
        delay(100)
        assertTrue(codex.state.value.approvals.isEmpty())
    }

    @Test fun stringRequestIdNeverClearsNumericApproval() = runBlocking {
        freshCodex()
        codexDispatch(commandApproval(7, "accept", "decline"))
        assertTrue(awaitCodex { it.approvals.size == 1 })
        codexDispatch(obj("method" to "serverRequest/resolved",
            "params" to obj("threadId" to "s1", "requestId" to "7")))
        delay(200)
        assertEquals(1, codex.state.value.approvals.size)
        codexDispatch(obj("method" to "serverRequest/resolved",
            "params" to obj("threadId" to "s1", "requestId" to 7)))
        assertTrue(awaitCodex { it.approvals.isEmpty() })
    }

    @Test fun decideAfterResolutionWritesNothingAndDoubleDecideIsBlocked() = runBlocking {
        freshCodex()
        codexDispatch(commandApproval("s-9", "accept", "decline"))
        assertTrue(awaitCodex { it.approvals.size == 1 })
        val approval = codex.state.value.approvals.single()
        codexDispatch(obj("method" to "serverRequest/resolved",
            "params" to obj("threadId" to "s1", "requestId" to "s-9")))
        assertTrue(awaitCodex { it.approvals.isEmpty() })
        // Stale tap: the request is gone, nothing may be written for it.
        instrumentation.runOnMainSync { codex.decide(approval, "accept") }
        delay(300)
        assertTrue(codexBridge.clientWrites.isEmpty())

        // Live approval with a STRING id: the second tap is blocked by the sent state, one write.
        codexDispatch(commandApproval("3", "accept", "decline"))
        assertTrue(awaitCodex { it.approvals.size == 1 })
        val live = codex.state.value.approvals.single()
        instrumentation.runOnMainSync { codex.decide(live, "decline") }
        withTimeout(10_000) { while (codexBridge.clientWrites.isEmpty()) delay(20) }
        instrumentation.runOnMainSync { codex.decide(live, "decline") }
        delay(300)
        assertEquals(1, codexBridge.clientWrites.size)
        assertEquals("decline", codexBridge.clientWrites.single().getJSONObject("result").string("decision"))
        // The string id "3" is echoed verbatim as a string — never coerced to a number.
        assertEquals("3", codexBridge.clientWrites.single().getString("id"))
    }

    @Test fun turnCompletionWithdrawsItsApprovalsAndDisconnectClearsAll() = runBlocking {
        freshCodex()
        codexDispatch(commandApproval(5, "accept", "decline"))
        assertTrue(awaitCodex { it.approvals.size == 1 })
        codexDispatch(obj("method" to "turn/completed",
            "params" to obj("threadId" to "s1", "turn" to obj("id" to "u1", "status" to "completed"))))
        assertTrue(awaitCodex { it.approvals.isEmpty() })

        codexDispatch(commandApproval(6, "accept", "decline"))
        assertTrue(awaitCodex { it.approvals.size == 1 })
        instrumentation.runOnMainSync { codex.disconnect() }
        assertTrue(awaitCodex { it.approvals.isEmpty() })
    }

    @Test fun objectOnlyDecisionsRenderUnsupportedAndDecideRefuses() = runBlocking {
        freshCodex()
        codexDispatch(commandApproval(8, JSONObject().put("acceptWithExecpolicyAmendment", JSONObject())))
        assertTrue(awaitCodex { it.approvals.size == 1 })
        val approval = codex.state.value.approvals.single()
        assertTrue(approval.hasObjectDecisions)
        assertTrue("交集为空时不得渲染决策按钮", approval.decisions.isEmpty())
        instrumentation.runOnMainSync { codex.decide(approval, "accept") }
        delay(300)
        assertTrue("未支持的决策不得写入", codexBridge.clientWrites.isEmpty())
        assertNotNull(codex.state.value.error)
    }

    // ---------------- ZCode interactions ----------------

    private lateinit var zApp: TestPrefsApp
    private lateinit var zvm: ZcodeViewModel
    private lateinit var zState: MutableStateFlow<ZcodeState>
    private lateinit var zBridge: FakeRpcBridge
    private val zSession = ZSession("s1", "集成", "/w", "idw")
    private val zQuestion = ZQuestion("用哪个数据库？", "数据库",
        listOf(ZQuestionOption("postgres", "PostgreSQL"), ZQuestionOption("mysql", "MySQL")), multiSelect = false)
    private val zFormInteraction = ZInteraction("i-form", "userInput", supported = true,
        prompt = "选择", freeText = false, questions = listOf(zQuestion))
    private val zFreeTextInteraction = ZInteraction("i-free", "userInput", supported = true,
        prompt = "输入", freeText = true, sensitive = true)

    private fun freshZcode() {
        zApp = TestPrefsApp(UUID.randomUUID().toString())
        zApp.attach(instrumentation.targetContext)
        instrumentation.runOnMainSync { zvm = ZcodeViewModel(zApp) }
        zState = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(zvm) as MutableStateFlow<ZcodeState>
        }
        zState.value = ZcodeState(connected = true, selected = zSession, interactionsEnabled = true)
        ZcodeViewModel::class.java.getDeclaredField("interactSupport").let {
            it.isAccessible = true; it.set(zvm, true)
        }
        zBridge = FakeRpcBridge(zvm)
        zBridge.install()
    }

    private fun zKey(type: String, id: String = ""): String =
        ZcodeViewModel::class.java.getDeclaredMethod("key", String::class.java, String::class.java).let {
            it.isAccessible = true; it.invoke(zvm, type, id) as String
        }

    private fun zVault() = Vault(zApp.prefsContext)
    private fun zRecords(): List<JSONObject> =
        zVault().get(zKey("pending-interact"))?.let { raw ->
            JSONArray(raw).objects()
        } ?: emptyList()

    private fun zReadResult(presentIds: List<String> = emptyList(), checked: Map<String, Boolean> = emptyMap(),
                            complete: Boolean = true, withAutoResolution: Boolean = false): JSONObject {
        val result = obj("id" to "s1", "title" to "集成", "revision" to 1L, "logEpoch" to "e1",
            "mode" to "yolo", "phase" to "waiting", "canStop" to false,
            "items" to JSONArray(), "totalRows" to 0, "queue" to JSONArray(),
            "interactions" to JSONArray().apply {
                presentIds.forEach { id ->
                    val item = obj("interactionId" to id, "kind" to "userInput", "supported" to true, "prompt" to "选择")
                    if (withAutoResolution) item.put("autoResolution", obj(
                        "state" to "visibleCountdown", "deadlineAt" to (System.currentTimeMillis() + 600_000)))
                    put(item)
                }
            },
            "interactionsComplete" to complete, "interactionCount" to presentIds.size)
        if (checked.isNotEmpty())
            result.put("interactionPresence", JSONArray().apply {
                checked.forEach { (id, pending) -> put(obj("interactionId" to id, "pending" to pending)) }
            })
        return result
    }

    private suspend fun awaitZ(timeoutMs: Long = 15_000, test: (ZcodeState) -> Boolean): Boolean =
        withTimeout(timeoutMs) { while (!test(zvm.state.value)) delay(20); true }

    private fun scriptCatalogAndProjects() {
        zBridge.scripted["catalog"] = { obj("providers" to JSONArray(), "preferred" to null) }
        zBridge.scripted["projects"] = { obj("projects" to JSONArray(), "sessions" to JSONArray()) }
    }

    @Test fun answerPersistsMetadataBeforeRpcAndNeverStoresTheText() = runBlocking {
        freshZcode()
        val gate = CountDownLatch(1)
        val sent = AtomicReference<JSONObject>()
        zBridge.scripted["session/interact"] = { request ->
            gate.await(10, TimeUnit.SECONDS)
            sent.set(request)
            obj("status" to "accepted")
        }
        zBridge.scripted["session/read"] = { request ->
            if (request.optJSONObject("params")?.has("checkInteractionIds") == true)
                zReadResult(checked = mapOf("i-free" to false))
            else zReadResult()
        }
        instrumentation.runOnMainSync { zvm.interactFreeText(zSession, zFreeTextInteraction, "SECRET-ANSWER") }
        // While the RPC is gated, the record exists, marks the buttons disabled, and holds
        // metadata only — the free text itself must not appear in the persisted JSON.
        assertTrue(awaitZ { "i-free" in it.interactSubmitting })
        val records = zRecords()
        assertEquals(1, records.size)
        assertEquals("submitting", records.single().string("phase"))
        assertEquals("freeText", records.single().string("summary"))
        assertTrue(records.single().string("commandId").length >= 16)
        assertFalse("答案文本不得落盘", zVault().get(zKey("pending-interact"))!!.contains("SECRET-ANSWER"))
        // The wire request does carry the answer.
        gate.countDown()
        withTimeout(10_000) { while (sent.get() == null) delay(20) }
        assertEquals("SECRET-ANSWER", sent.get().optJSONObject("params")!!.getJSONObject("answer").string("freeText"))
        // Accepted → awaiting snapshot → authoritative presence=false clears the record.
        assertTrue(awaitZ { it.interactSubmitting.isEmpty() && zRecords().isEmpty() })
        assertEquals("交互已结束", zvm.state.value.interactionNotice)
    }

    @Test fun formSubmitBuildsOfficialContentAndPreemptionClearsQuietly() = runBlocking {
        freshZcode()
        val sent = AtomicReference<JSONObject>()
        zBridge.scripted["session/interact"] = { request ->
            sent.set(request)
            obj("status" to "noop", "reasonCode" to "alreadyResolved")
        }
        zBridge.scripted["session/read"] = { zReadResult(checked = mapOf("i-form" to false)) }
        val drafts = mapOf("用哪个数据库？" to ZFormDraft(listOf("postgres")))
        instrumentation.runOnMainSync { zvm.interactForm(zSession, zFormInteraction, zFormInteraction.questions, drafts) }
        withTimeout(10_000) { while (sent.get() == null) delay(20) }
        val answer = sent.get().optJSONObject("params")!!.getJSONObject("answer")
        assertEquals("accept", answer.string("action"))
        val content = answer.getJSONObject("content")
        assertEquals("postgres", content.getJSONObject("answers").string("用哪个数据库？"))
        assertEquals("postgres", content.getString("answer_0"))
        assertEquals("postgres", content.getString("answer"))
        // noop(alreadyResolved) plus presence=false: no error, record cleared.
        assertTrue(awaitZ { it.interactSubmitting.isEmpty() && zRecords().isEmpty() })
        assertNull(zvm.state.value.error)
    }

    @Test fun acceptedButStillPendingStaysDisabledUntilAuthoritativeEnd() = runBlocking {
        freshZcode()
        zBridge.scripted["session/interact"] = { obj("status" to "accepted") }
        zBridge.scripted["session/read"] = { request ->
            if (request.optJSONObject("params")?.has("checkInteractionIds") == true)
                // The immediate refresh still sees the interaction pending: keep awaiting.
                zReadResult(presentIds = listOf("i-form"), checked = mapOf("i-form" to true))
            else
                // A later plain read is complete and no longer lists it: authoritative end.
                zReadResult()
        }
        instrumentation.runOnMainSync {
            zvm.interactDecline(zSession, zFormInteraction)
        }
        assertTrue(awaitZ { "i-form" in it.interactSubmitting })
        delay(400)
        assertEquals("旧快照不得重新启用按钮", setOf("i-form"), zvm.state.value.interactSubmitting)
        assertTrue(zRecords().isNotEmpty())
        // Force another authoritative read (retryLoad path) that no longer lists it.
        instrumentation.runOnMainSync { zvm.retryLoad() }
        assertTrue(awaitZ { it.interactSubmitting.isEmpty() && zRecords().isEmpty() })
        assertEquals("交互已结束", zvm.state.value.interactionNotice)
    }

    @Test fun definiteRejectionClearsRecordAndNotices() = runBlocking {
        freshZcode()
        zBridge.scripted["session/interact"] = { obj("status" to "rejected", "reasonCode" to "validation") }
        zBridge.scripted["session/read"] = { zReadResult(presentIds = listOf("i-form"), complete = false) }
        instrumentation.runOnMainSync { zvm.interactDecline(zSession, zFormInteraction) }
        assertTrue(awaitZ { it.interactSubmitting.isEmpty() && zRecords().isEmpty() })
        assertTrue(zvm.state.value.interactionNotice.contains("未接受"))
    }

    @Test fun recoveryUnknownOutcomeKeepsRecordWithoutAnyResend() = runBlocking {
        freshZcode()
        scriptCatalogAndProjects()
        // A record from a previous connection whose outcome is unknown.
        zVault().put(zKey("pending-interact"), JSONArray().put(
            InteractRecordJson("i-form")).toString())
        zBridge.scripted["command/query"] = { obj() } // no ack: outcome unknown
        zBridge.scripted["session/read"] = { zReadResult(presentIds = listOf("i-form"), complete = false) }
        instrumentation.runOnMainSync { zvm.refresh() }
        assertTrue(awaitZ { "i-form" in it.interactSubmitting })
        delay(300)
        // Unknown is not failure: the record stays, the user is prompted, nothing is resent.
        assertEquals(1, zRecords().size)
        assertEquals("uncertain", zRecords().single().string("phase"))
        assertTrue(zvm.state.value.interactionNotice.contains("未知"))
    }

    @Test fun recoveryAcceptedConfirmsThroughPresenceAndClears() = runBlocking {
        freshZcode()
        scriptCatalogAndProjects()
        zVault().put(zKey("pending-interact"), JSONArray().put(InteractRecordJson("i-free")).toString())
        zBridge.scripted["command/query"] = { obj("ack" to obj("commandId" to "cmd-0123456789abcdef", "status" to "accepted")) }
        zBridge.scripted["session/read"] = { request ->
            if (request.optJSONObject("params")?.has("checkInteractionIds") == true)
                zReadResult(checked = mapOf("i-free" to false))
            else zReadResult()
        }
        instrumentation.runOnMainSync { zvm.refresh() }
        assertTrue(awaitZ { zRecords().isEmpty() && it.interactSubmitting.isEmpty() })
        assertEquals("交互已结束", zvm.state.value.interactionNotice)
    }

    @Test fun snoozeWaitsForAutoResolutionThenSendsOnce() = runBlocking {
        freshZcode()
        scriptCatalogAndProjects()
        val snoozes = AtomicInteger(0)
        zBridge.scripted["session/interact/snooze"] = { snoozes.incrementAndGet(); obj("status" to "accepted") }
        val withCountdown = zFormInteraction.copy(autoResolution = ZAutoResolution("visibleCountdown", deadlineAt = System.currentTimeMillis() + 600_000))
        // Touch before autoResolution arrives: intent recorded, nothing sent yet.
        instrumentation.runOnMainSync { zvm.noteInteractionTouch(zSession, zFormInteraction) }
        delay(300)
        assertEquals(0, snoozes.get())
        // The snapshot brings the countdown: the recorded intent fires exactly once.
        zBridge.scripted["session/read"] = { zReadResult(presentIds = listOf("i-form"), withAutoResolution = true) }
        instrumentation.runOnMainSync { zvm.retryLoad() }
        assertTrue(awaitZ { snoozes.get() == 1 })
        instrumentation.runOnMainSync { zvm.retryLoad() }
        delay(300)
        assertEquals("确认后不得重复 snooze", 1, snoozes.get())
    }

    private fun InteractRecordJson(interactionId: String): JSONObject = obj(
        "workspacePath" to "/w", "workspaceIdentity" to "idw", "sessionId" to "s1",
        "interactionId" to interactionId, "commandId" to "cmd-0123456789abcdef",
        "phase" to "uncertain", "summary" to "decline", "issuedAt" to 1L)

    @After
    fun tearDown() {
        try {
            if (::codex.isInitialized) instrumentation.runOnMainSync {
                codex.background(); codex.viewModelScope.cancel()
            }
            if (::zvm.isInitialized) instrumentation.runOnMainSync {
                zvm.background(); zvm.viewModelScope.cancel()
            }
        } finally {
            if (::codexBridge.isInitialized) codexBridge.close()
            if (::zBridge.isInitialized) zBridge.close()
            val apps = mutableListOf<TestPrefsApp>()
            if (::codexApp.isInitialized) apps += codexApp
            if (::zApp.isInitialized) apps += zApp
            apps.forEach {
                instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${it.namespace}-vault")
            }
        }
        if (::codexBridge.isInitialized) codexBridge.assertClean()
        if (::zBridge.isInitialized) zBridge.assertClean()
    }
}
