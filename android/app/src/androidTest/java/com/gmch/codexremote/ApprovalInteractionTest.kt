package com.gmch.codexremote

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import android.view.accessibility.AccessibilityNodeInfo
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
        zState.value = ZcodeState(connected = true, selected = zSession, interactionsEnabled = true,
            interactions = listOf(zFormInteraction, zFreeTextInteraction))
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
        assertEquals("text", records.single().string("summary"))
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
        zBridge.scripted["session/read"] = { zReadResult(checked = mapOf("i-form" to false)) }
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

    @Test fun oldApprovalCannotAnswerReusedIdOnNewGeneration() = runBlocking {
        freshCodex()
        codexDispatch(commandApproval(7, "accept"))
        val old = codex.state.value.approvals.single()
        instrumentation.runOnMainSync {
            RemoteViewModel::class.java.getDeclaredField("epoch").apply { isAccessible = true }.set(codex, old.generation + 1)
            codexState.value = codexState.value.copy(approvals = emptyList())
        }
        codexDispatch(commandApproval(7, "accept").apply { getJSONObject("params").put("command", "different-new-command") })
        instrumentation.runOnMainSync { codex.decide(old, "accept") }
        delay(300)
        assertEquals("Old approval must not authorize a new request", 0, codexBridge.clientWrites.size)
    }

    @Test fun adapterErrorKeepsUncertainRecord() = runBlocking {
        freshZcode()
        zBridge.scripted["session/interact"] = { throw FakeRpcBridge.FakeRpcError(-32000, "adapter result unknown") }
        instrumentation.runOnMainSync { zvm.interactFreeText(zSession, zFreeTextInteraction, "synthetic") }
        assertTrue(awaitZ { it.interactionNotice.contains("未知") })
        assertEquals("uncertain", zRecords().single().string("phase"))
        assertTrue("i-free" in zState.value.interactSubmitting)
    }

    @Test fun oldSnapshotCannotClearPendingInteraction() = runBlocking {
        freshZcode()
        zState.value = zState.value.copy(logEpoch = "e1", revision = 100, interactions = listOf(zFormInteraction), interactionCount = 1)
        zBridge.scripted["session/interact"] = { obj("status" to "accepted") }
        zBridge.scripted["session/read"] = { zReadResult().put("revision", 1) }
        instrumentation.runOnMainSync { zvm.interactDecline(zSession, zFormInteraction) }
        assertTrue(awaitZ { zRecords().singleOrNull()?.string("phase") == "awaiting" })
        delay(300)
        assertEquals(1, zRecords().size)
        assertEquals(100L, zState.value.revision)
    }

    @Test fun unknownAckProtectsAndUnknownQueryStillChecksPresence() = runBlocking {
        freshZcode()
        scriptCatalogAndProjects()
        val sends = AtomicInteger()
        zBridge.scripted["session/interact"] = { sends.incrementAndGet(); obj("status" to "future-status") }
        instrumentation.runOnMainSync { zvm.interactDecline(zSession, zFormInteraction) }
        assertTrue(awaitZ { it.interactionNotice.contains("未知") })
        assertEquals("uncertain", zRecords().single().string("phase"))
        val checks = AtomicInteger()
        zBridge.scripted["command/query"] = { obj() }
        zBridge.scripted["session/read"] = { request ->
            if (request.getJSONObject("params").has("checkInteractionIds")) checks.incrementAndGet()
            zReadResult(checked = mapOf("i-form" to false), complete = false)
        }
        instrumentation.runOnMainSync { zvm.refresh() }
        assertTrue(awaitZ { it.interactSubmitting.isEmpty() && zRecords().isEmpty() })
        assertTrue(checks.get() > 0)
        assertEquals(1, sends.get())
    }

    @Test fun rapidDuplicateAndReleaseDuringRpcCannotResend() = runBlocking {
        freshZcode()
        val gate = CountDownLatch(1)
        val sends = AtomicInteger()
        zBridge.scripted["session/interact"] = { sends.incrementAndGet(); gate.await(5, TimeUnit.SECONDS); obj("status" to "accepted") }
        zBridge.scripted["session/read"] = { zReadResult(listOf("i-form"), checked = mapOf("i-form" to true)) }
        try {
            instrumentation.runOnMainSync {
                zvm.interactDecline(zSession, zFormInteraction)
                zvm.interactDecline(zSession, zFormInteraction)
            }
            assertTrue(awaitZ { "i-form" in it.interactSubmitting })
            instrumentation.runOnMainSync { zvm.releaseInteraction(zSession, "i-form") }
            assertEquals(1, zRecords().size)
            assertTrue(zState.value.interactReleasable.isEmpty())
        } finally { gate.countDown() }
        assertTrue(awaitZ { zRecords().singleOrNull()?.string("phase") == "awaiting" })
        assertEquals(1, sends.get())
    }

    @Test fun independentInteractionsKeepBothReceiptsAndHideOptionValues() = runBlocking {
        freshZcode()
        zBridge.scripted["session/interact"] = { throw FakeRpcBridge.FakeRpcError(-32001, "unknown") }
        instrumentation.runOnMainSync {
            zvm.interactOption(zSession, zFormInteraction, "PRIVATE-OPTION")
            zvm.interactFreeText(zSession, zFreeTextInteraction, "PRIVATE-ANSWER")
        }
        assertTrue(awaitZ { zRecords().size == 2 && zRecords().all { r -> r.string("phase") == "uncertain" } })
        assertEquals(setOf("i-form", "i-free"), zState.value.interactSubmitting)
        assertFalse(zVault().get(zKey("pending-interact"))!!.contains("PRIVATE-"))
    }

    @Test fun environmentSwitchPreservesBothVaultsAndRejectsOldAck() = runBlocking {
        freshZcode()
        scriptCatalogAndProjects()
        val gate = CountDownLatch(1)
        zBridge.scripted["session/interact"] = { gate.await(10, TimeUnit.SECONDS); obj("status" to "rejected") }
        zBridge.scripted["command/query"] = { obj() }
        zBridge.scripted["session/read"] = { zReadResult(listOf("i-form", "i-free"), complete = false) }
        val profileField = ZcodeViewModel::class.java.getDeclaredField("profile").apply { isAccessible = true }
        val epochField = ZcodeViewModel::class.java.getDeclaredField("generation").apply { isAccessible = true }
        val reset = ZcodeViewModel::class.java.getDeclaredMethod("resetInteractionEnvironment").apply { isAccessible = true }
        val a = ConnectionProfile(host = "fixture", root = "/w", environmentId = "A")
        val b = a.copy(environmentId = "B") // Same transport, different deployment identity.
        fun switch(profile: ConnectionProfile) = instrumentation.runOnMainSync {
            profileField.set(zvm, profile)
            epochField.setInt(zvm, epochField.getInt(zvm) + 1)
            reset.invoke(zvm)
            zState.value = zState.value.copy(interactions = listOf(zFormInteraction, zFreeTextInteraction))
        }
        switch(a)
        val aKey = zKey("pending-interact")
        instrumentation.runOnMainSync { zvm.interactDecline(zSession, zFormInteraction) }
        assertTrue(awaitZ { zRecords().isNotEmpty() })
        val aBefore = zVault().get(aKey)
        switch(b)
        val bKey = zKey("pending-interact")
        assertFalse(aKey == bKey)
        zVault().put(bKey, JSONArray().put(InteractRecordJson("i-free")).toString())
        try {
            instrumentation.runOnMainSync { zvm.refresh() }
        } finally { gate.countDown() }
        assertTrue(awaitZ { it.interactSubmitting == setOf("i-free") })
        assertEquals(aBefore, zVault().get(aKey))
        assertEquals("i-free", zRecords().single().string("interactionId"))
        switch(a)
        instrumentation.runOnMainSync { zvm.refresh() }
        assertTrue(awaitZ { it.interactSubmitting == setOf("i-form") && !it.busy })
        assertEquals("i-free", JSONArray(zVault().get(bKey)!!).getJSONObject(0).string("interactionId"))
    }

    @Test fun changedEpochRequiresConfirmationAndCannotRegressBack() = runBlocking {
        freshZcode()
        zState.value = zState.value.copy(logEpoch = "old", revision = 100)
        val reads = AtomicInteger()
        zBridge.scripted["session/read"] = { reads.incrementAndGet(); zReadResult(listOf("i-form")).put("logEpoch", "new") }
        instrumentation.runOnMainSync { zvm.retryLoad() }
        assertTrue(awaitZ { it.logEpoch == "new" })
        assertEquals(2, reads.get())
        zBridge.scripted["session/read"] = { zReadResult().put("logEpoch", "old").put("revision", 200) }
        instrumentation.runOnMainSync { zvm.retryLoad() }
        delay(300)
        assertEquals("new", zState.value.logEpoch)
        assertEquals(listOf("i-form"), zState.value.interactions.map { it.id })
    }

    @Test fun diskFailurePreventsAnswerRpc() = runBlocking {
        freshZcode()
        val sends = AtomicInteger()
        zBridge.scripted["session/interact"] = { sends.incrementAndGet(); obj("status" to "accepted") }
        zApp.failCommits = true
        try {
            instrumentation.runOnMainSync { zvm.interactDecline(zSession, zFormInteraction) }
            assertTrue(awaitZ { it.interactionNotice.contains("无法保存") })
            assertEquals(0, sends.get())
            assertTrue(zState.value.interactSubmitting.isEmpty())
            assertTrue(zRecords().isEmpty())
        } finally { zApp.failCommits = false }
    }

    @Test fun diskFailureAfterAckKeepsReceiptUntilRecovery() = runBlocking {
        freshZcode(); scriptCatalogAndProjects()
        val gate = CountDownLatch(1)
        zBridge.scripted["session/interact"] = { gate.await(5, TimeUnit.SECONDS); obj("status" to "accepted") }
        instrumentation.runOnMainSync { zvm.interactDecline(zSession, zFormInteraction) }
        assertTrue(awaitZ { "i-form" in it.interactSubmitting })
        zApp.failCommits = true
        try {
            gate.countDown()
            assertTrue(awaitZ { it.interactionNotice.contains("无法保存确认") })
            assertEquals(1, zRecords().size)
            assertTrue("i-form" in zState.value.interactSubmitting)
        } finally { zApp.failCommits = false }
        zBridge.scripted["command/query"] = { obj("ack" to obj("status" to "accepted")) }
        zBridge.scripted["session/read"] = { zReadResult(checked = mapOf("i-form" to false)) }
        instrumentation.runOnMainSync { zvm.refresh() }
        assertTrue(awaitZ { zRecords().isEmpty() })
    }

    @Test fun snoozeUnknownFailureRetriesWithSameIdAndStopsAfterSuccess() = runBlocking {
        freshZcode()
        val interaction = zFormInteraction.copy(autoResolution = ZAutoResolution("visibleCountdown", System.currentTimeMillis() + 120_000))
        zState.value = zState.value.copy(interactions = listOf(interaction))
        val ids = java.util.concurrent.CopyOnWriteArrayList<String>()
        zBridge.scripted["session/interact/snooze"] = {
            ids.add(it.getJSONObject("params").getString("commandId"))
            if (ids.size == 1) throw FakeRpcBridge.FakeRpcError(-32000, "unknown")
            obj("status" to "accepted")
        }
        instrumentation.runOnMainSync { zvm.noteInteractionTouch(zSession, interaction) }
        assertTrue(awaitZ { "i-form" in it.snoozeUnconfirmed })
        delay(1200)
        instrumentation.runOnMainSync { zvm.noteInteractionTouch(zSession, interaction) }
        withTimeout(10_000) { while (ids.size < 2) delay(20) }
        assertTrue(awaitZ { it.snoozeUnconfirmed.isEmpty() })
        instrumentation.runOnMainSync { zvm.noteInteractionTouch(zSession, interaction) }
        delay(200)
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
    }

    @Test fun simpleDraftSurvivesDismissAndIncompleteWindowAndFocusSnoozes() = runBlocking {
        freshCodex(); freshZcode()
        val interaction = zFreeTextInteraction.copy(autoResolution = ZAutoResolution("visibleCountdown", System.currentTimeMillis() + 120_000))
        val snoozes = AtomicInteger()
        zBridge.scripted["session/interact/snooze"] = { snoozes.incrementAndGet(); obj("status" to "accepted") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                codexState.value = UiState(engine = "zcode", saved = false)
                zState.value = zState.value.copy(interactions = listOf(interaction), interactionCount = 5, interactionsComplete = false)
                ZcodeViewModel::class.java.getDeclaredField("historySyncedAt").apply { isAccessible = true }.set(zvm, System.currentTimeMillis())
                activity.setContent { PocketHelmTheme { EngineApp(codex, zvm) } }
            }
            reviewOpenBanner("ZCode 等待输入")
            instrumentation.runOnMainSync { zvm.leave() } // Freeze the scripted snapshot while exercising drafts.
            reviewAwait { reviewNodes().any { it.isEditable && it.isPassword } }
            val editor = reviewNodes().first { it.isEditable && it.isPassword }
            assertTrue(editor.isPassword)
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            reviewAwait { snoozes.get() == 1 }
            assertTrue(reviewNodes().any { it.text?.contains("剩余约 2 分钟") == true })
            val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Draft stays private") }
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            instrumentation.runOnMainSync { zState.value = zState.value.copy(interactions = listOf(zFormInteraction)) }
            reviewAwait { reviewNodes().none { it.text?.toString() == "输入（敏感内容不落盘）" } }
            instrumentation.runOnMainSync { zState.value = zState.value.copy(interactions = listOf(interaction)) }
            reviewAwait { reviewNodes().any { it.isEditable && it.isPassword && it.text?.length == 19 } }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            // Back may dismiss the keyboard first; a second back dismisses the sheet.
            Thread.sleep(300)
            if (reviewNodes().any { it.isEditable && it.isPassword })
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            reviewAwait { reviewNodes().none { it.isPassword } }
            reviewOpenBanner("ZCode 等待输入")
            reviewAwait { reviewNodes().any { it.isEditable && it.isPassword && it.text?.length == 19 } }
            assertEquals(1, snoozes.get())
            reviewScreenshot("zcode-sensitive-draft")
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun sensitiveEditorsExposePasswordInputTypeToIme() = runBlocking {
        freshCodex(); freshZcode()
        val variants = listOf(
            zFreeTextInteraction.copy(id = "plain-simple", sensitive = false),
            zFreeTextInteraction.copy(id = "secret-simple"),
            zFormInteraction.copy(id = "secret-form", sensitive = true),
            zFormInteraction.copy(id = "plain-form", sensitive = false))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                codexState.value = UiState(engine = "zcode", saved = false)
                zState.value = zState.value.copy(interactions = listOf(variants.first()), interactionCount = 1, interactionsComplete = true)
                ZcodeViewModel::class.java.getDeclaredField("historySyncedAt").apply { isAccessible = true }.set(zvm, System.currentTimeMillis())
                activity.setContent { PocketHelmTheme { EngineApp(codex, zvm) } }
            }
            reviewOpenBanner("ZCode 等待输入")
            instrumentation.runOnMainSync { zvm.leave() }
            variants.forEach { interaction ->
                instrumentation.runOnMainSync { zState.value = zState.value.copy(interactions = listOf(interaction)) }
                val label = if (interaction.questions.isNotEmpty()) "自定义回答"
                    else if (interaction.sensitive) "输入（敏感内容不落盘）" else "输入回答"
                reviewAwait { reviewNodes().any { it.text?.toString() == label } }
                instrumentation.waitForIdleSync(); delay(400)
                // The same Compose node is reused across variants. Select the visible
                // editor; assert the IME contract below instead of cached accessibility flags.
                reviewAwait { reviewNodes().any { it.isEditable } }
                val editor = reviewNodes().first { it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                var actual: android.view.inputmethod.EditorInfo? = null
                reviewAwait { actual = focusedEditorInfo(); actual != null }
                val info = actual!!
                assertEquals(android.text.InputType.TYPE_CLASS_TEXT, info.inputType and android.text.InputType.TYPE_MASK_CLASS)
                assertEquals(interaction.id,
                    if (interaction.sensitive) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
                    info.inputType and android.text.InputType.TYPE_MASK_VARIATION)
                if (interaction.sensitive) assertEquals("Sensitive input must disable autocorrect", 0,
                    info.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
                else assertTrue("Normal input keeps autocorrect", info.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0)
            }
        }
    }

    /** Ask the focused Compose view for the EditorInfo delivered to IME, including sheet windows. */
    @androidx.annotation.RequiresApi(29)
    private fun focusedEditorInfo(): android.view.inputmethod.EditorInfo? {
        var result: android.view.inputmethod.EditorInfo? = null
        instrumentation.runOnMainSync {
            fun visit(view: android.view.View) {
                if (result != null || !view.hasWindowFocus()) return
                if (view.onCheckIsTextEditor()) {
                    val info = android.view.inputmethod.EditorInfo()
                    if (view.onCreateInputConnection(info) != null) result = info
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return result
    }

    @Test fun bridgeProjectedEmptyArraysAllowFreeTextSubmissionUi() = runBlocking {
        freshCodex(); freshZcode()
        val cases = JSONArray(instrumentation.context.assets.open("free-text-interactions.json").bufferedReader().use { it.readText() }).objects()
        val snapshot = AtomicReference(cases.first().getJSONObject("sessionRead"))
        val sent = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        zBridge.scripted["session/read"] = { JSONObject(snapshot.get().toString()) }
        zBridge.scripted["session/interact"] = { request ->
            sent.add(request.getJSONObject("params"))
            snapshot.set(JSONObject(snapshot.get().toString()).apply {
                put("revision", optLong("revision") + 1); put("interactions", JSONArray()); put("interactionCount", 0)
            })
            obj("status" to "accepted")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                codexState.value = UiState(engine = "zcode", saved = false)
                zState.value = zState.value.copy(interactions = emptyList(), interactionCount = 0)
                ZcodeViewModel::class.java.getDeclaredField("historySyncedAt").apply { isAccessible = true }.set(zvm, System.currentTimeMillis())
                activity.setContent { PocketHelmTheme { EngineApp(codex, zvm) } }
            }
            cases.forEachIndexed { index, fixture ->
                snapshot.set(fixture.getJSONObject("sessionRead"))
                instrumentation.runOnMainSync { zvm.retryLoad() }
                assertTrue(awaitZ { it.interactions.singleOrNull()?.id == "free-text-$index" })
                reviewOpenBanner("ZCode 等待输入")
                instrumentation.runOnMainSync { zvm.leave() }
                reviewAwait { reviewNodes().any { it.text?.toString() == "输入回答" } }
                instrumentation.waitForIdleSync(); delay(300)
                val text = "fixture-answer-$index"
                val editor = reviewNodes().first { it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }))
                // ACTION_SET_TEXT returns before recomposition enables Send.
                reviewAwait {
                    var button = reviewNodes().firstOrNull { it.text?.toString() == "发送" }
                    while (button != null && !button.isClickable && button.parent != null) button = button.parent
                    button?.isClickable == true && button.isEnabled
                }
                reviewClick("发送")
                assertTrue(awaitZ { it.interactions.isEmpty() && it.interactSubmitting.isEmpty() })
                assertEquals(index + 1, sent.size)
                assertEquals("free-text-$index", sent[index].getString("interactionId"))
                val answer = sent[index].getJSONObject("answer")
                assertEquals(setOf("freeText"), answer.keys().asSequence().toSet())
                assertEquals(text, answer.getString("freeText"))
                reviewAwait { reviewNodes().none { it.text?.toString() == "输入回答" } }
            }
        }
    }

    /** Optional live fixture, transported over an adb reverse tunnel to a loopback-only
     * bridge. Only the provisioned test session is allowed by the fixture server. */
    @Test fun realZcodeQuestionUi() = runBlocking {
        val fixture = java.io.File(instrumentation.targetContext.filesDir, "interaction-live-target.json")
        org.junit.Assume.assumeTrue("Dedicated live interaction fixture is not installed", fixture.isFile)
        val target = JSONObject(fixture.readText())
        freshCodex(); freshZcode()
        val socket = java.net.Socket("127.0.0.1", target.getInt("port"))
        val client = RpcClient(socket.getInputStream(), socket.getOutputStream(), {}, {})
        try {
            assertTrue(client.call("initialize", obj("clientId" to "pockethelm-live-ui-${UUID.randomUUID()}"))
                .getJSONObject("capabilities").getBoolean("interactions"))
            zBridge.close()
            val session = ZSession(target.getString("sessionId"), "交互验收", target.getString("workspacePath"), target.optString("workspaceIdentity"))
            instrumentation.runOnMainSync {
                ZcodeViewModel::class.java.getDeclaredField("rpc").apply { isAccessible = true }.set(zvm, client)
                zState.value = ZcodeState(connected = true, selected = session, interactionsEnabled = true)
                zvm.retryLoad()
            }
            assertTrue(awaitZ(60_000) { it.interactions.any { question -> question.questions.isNotEmpty() } })
            val previousReplies = zState.value.items.filter { it.kind == "agentMessage" }.map { it.id }.toSet()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    codexState.value = UiState(engine = "zcode", saved = false)
                    activity.setContent { PocketHelmTheme { EngineApp(codex, zvm) } }
                }
                reviewOpenBanner("ZCode 提问")
                reviewAwait { reviewNodes().any { it.text?.toString() == "自定义回答" } }
                reviewScreenshot("zcode-live-question")
                val editor = reviewNodes().first { it.isEditable }
                editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                // The server may publish autoResolution after its 60-second hidden grace.
                withTimeout(90_000) {
                    while (zState.value.interactions.none { it.autoResolution?.state == "snoozed" }) {
                        instrumentation.runOnMainSync { zvm.retryLoad() }; delay(800)
                    }
                }
                reviewScreenshot("zcode-live-snoozed")
                editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "SQLite")
                })
                reviewClick("提交")
                assertTrue(awaitZ(60_000) { it.interactions.isEmpty() && it.interactSubmitting.isEmpty() })
                withTimeout(120_000) {
                    while (zState.value.items.none { it.kind == "agentMessage" && it.id !in previousReplies && it.text.contains("INTERACTION_E2E_OK") && it.text.contains("SQLite") }) {
                        instrumentation.runOnMainSync { zvm.retryLoad() }; delay(1500)
                    }
                }
                reviewScreenshot("zcode-live-continued")
            }
        } finally { client.close(); socket.close() }
    }

    private fun reviewOpenBanner(label: String) {
        reviewAwait { reviewNodes().any { it.text?.toString()?.contains(label) == true } }
        var node = reviewNodes().first { it.text?.toString()?.contains(label) == true }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun reviewNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; result.add(n); for (i in 0 until n.childCount) walk(n.getChild(i)) }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return result.filter { it.isVisibleToUser }
    }
    private fun reviewAwait(test: () -> Boolean) { val end = System.currentTimeMillis() + 10000; while(!test() && System.currentTimeMillis() < end) Thread.sleep(100); assertTrue(test()) }
    private fun reviewClick(text: String) {
        reviewAwait { reviewNodes().any { it.text?.toString() == text } }
        var n = reviewNodes().first { it.text?.toString() == text }
        while (!n.isClickable && n.parent != null) n = n.parent
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun reviewScreenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(700)
        val folder = java.io.File(instrumentation.targetContext.cacheDir, "interaction-review").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    }
    @Test fun reviewSingleChoiceCustomAnswerUi() = runBlocking {
        freshCodex(); freshZcode()
        val sent = AtomicReference<JSONObject>()
        zBridge.scripted["session/interact"] = { sent.set(it); obj("status" to "accepted") }
        zBridge.scripted["session/read"] = { zReadResult(listOf("i-form")).apply {
            getJSONArray("interactions").getJSONObject(0).put("questions", JSONArray().put(obj("question" to zQuestion.question,
                "header" to "数据库", "options" to JSONArray().put(obj("value" to "postgres", "label" to "PostgreSQL"))
                    .put(obj("value" to "mysql", "label" to "MySQL")), "multiSelect" to false)))
        } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                codexState.value = UiState(engine = "zcode", saved = false)
                zState.value = zState.value.copy(interactions = listOf(zFormInteraction), interactionCount = 1, interactionsComplete = true, phase = "waiting")
                ZcodeViewModel::class.java.getDeclaredField("historySyncedAt").apply { isAccessible = true }.set(zvm, System.currentTimeMillis())
                activity.setContent { PocketHelmTheme { EngineApp(codex, zvm) } }
            }
            reviewAwait { reviewNodes().any { it.text?.toString()?.contains("ZCode 提问") == true } }
            val banner = reviewNodes().first { it.text?.toString()?.contains("ZCode 提问") == true }
            var clickNode = banner; while (!clickNode.isClickable && clickNode.parent != null) clickNode = clickNode.parent
            clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            reviewClick("PostgreSQL")
            reviewAwait { reviewNodes().any { it.isEditable } }
            val editor = reviewNodes().first { it.isEditable }
            val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "SQLite") }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            Thread.sleep(300)
            reviewScreenshot("zcode-custom-answer")
            reviewClick("提交")
            reviewAwait { sent.get() != null }
            val content = sent.get().getJSONObject("params").getJSONObject("answer").getJSONObject("content")
            assertEquals("Custom answer replaces single choice", "SQLite", content.getString("answer_0"))
            assertEquals("SQLite", content.getJSONObject("answers").getString(zQuestion.question))
        }
    }

    @Test fun reviewCodexApprovalSheetUi() = runBlocking {
        freshCodex(); freshZcode()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                codexState.value = codexState.value.copy(saved = false, foreground = true)
                activity.setContent { PocketHelmTheme { EngineApp(codex, zvm) } }
            }
            codexDispatch(commandApproval(7, "accept", "decline"))
            reviewAwait { reviewNodes().any { it.text?.toString()?.contains("命令审批") == true } }
            var node = reviewNodes().first { it.text?.toString()?.contains("命令审批") == true }
            while (!node.isClickable && node.parent != null) node = node.parent
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            reviewAwait { reviewNodes().any { it.text?.toString() == "允许" } }
            reviewScreenshot("codex-approval")
            reviewClick("允许")
            reviewAwait { codexBridge.clientWrites.isNotEmpty() }
            reviewScreenshot("codex-approval-sent")
            assertEquals("accept", codexBridge.clientWrites.single().getJSONObject("result").getString("decision"))
        }
    }

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
