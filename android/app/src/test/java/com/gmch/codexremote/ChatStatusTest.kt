package com.gmch.codexremote

import org.junit.Assert.*
import org.junit.Test

class ChatStatusTest {
    @Test fun loadingSessionIsNotPresentedAsNewThreadEmptyState() {
        // Opening an existing session clears items first; the loading view must not read as "开始这次工作".
        assertEquals(TimelineView.Loading, timelineView(0, loading = true, error = null))
        assertEquals(TimelineView.Empty, timelineView(0, loading = false, error = null))
    }
    @Test fun loadFailureShowsRetryInsteadOfEmptyState() {
        assertEquals(TimelineView.Error, timelineView(0, loading = true, error = "SSH 通道已关闭"))
        assertEquals(TimelineView.Error, timelineView(0, loading = false, error = "连接已断开"))
        assertEquals(TimelineView.Empty, timelineView(0, loading = false, error = ""))
    }
    @Test fun refreshKeepsExistingMessagesEvenWhileReloadingOrFailed() {
        assertEquals(TimelineView.Content, timelineView(5, loading = true, error = null))
        assertEquals(TimelineView.Content, timelineView(5, loading = false, error = "超时"))
    }
    @Test fun disconnectStopsPretendingAgentIsStillRunning() {
        assertEquals(AgentRun.Offline, codexAgentRun(connected = false, runtime = "active"))
        assertEquals(AgentRun.Offline, zcodeAgentRun(connected = false, phase = "running"))
        assertEquals(AgentRun.Running, codexAgentRun(connected = true, runtime = "active"))
        assertEquals(AgentRun.Idle, codexAgentRun(connected = true, runtime = "idle"))
        assertEquals(AgentRun.Running, zcodeAgentRun(connected = true, phase = "running"))
        assertEquals(AgentRun.Idle, zcodeAgentRun(connected = true, phase = "completedSuccess"))
        assertEquals(AgentRun.Idle, zcodeAgentRun(connected = true, phase = "error"))
    }
    @Test fun waitingStatesNeverClaimToBeGenerating() {
        // Waiting for the user's interaction is not generation: static label, no spinner state.
        assertEquals(AgentRun.WaitingInteraction, zcodeAgentRun(connected = true, phase = "running", interactionCount = 1))
        assertEquals(AgentRun.WaitingInteraction, zcodeAgentRun(connected = true, phase = "waiting", interactionCount = 2))
        assertEquals("等待交互", agentRunLabel(AgentRun.WaitingInteraction, ""))
        // A bare waiting phase is static too; only confirmed running spins.
        assertEquals(AgentRun.Waiting, zcodeAgentRun(connected = true, phase = "waiting"))
        assertEquals("等待回复", agentRunLabel(AgentRun.Waiting, ""))
        assertEquals("准备中", agentRunLabel(AgentRun.Idle, "准备中"))
    }
    @Test fun inFlightSendDoesNotTriggerUncertainProtection() {
        // The receipt is persisted before dispatch, so pending=true alone would false-alarm
        // during every ordinary request.
        assertFalse(pendingProtectionVisible(pending = true, inFlight = true))
        assertFalse(pendingProtectionVisible(pending = false, inFlight = true))
    }
    @Test fun protectionReturnsOnceRequestSettlesWithoutDefiniteResult() {
        // One request over time: persisted → in flight (hidden) → settled unknown (protected again).
        val oneRequest = listOf(
            pendingProtectionVisible(pending = true, inFlight = true),
            pendingProtectionVisible(pending = true, inFlight = false))
        assertEquals(listOf(false, true), oneRequest)
        assertTrue(pendingProtectionVisible(pending = true, inFlight = false))
    }
    @Test fun definiteRejectionClearsPendingSoNoProtectionRemains() {
        assertFalse(pendingProtectionVisible(pending = false, inFlight = false))
    }

    // --- RequestScope: simulate real async lifecycles against the guard the ViewModels use ---

    private class FakeChat {
        var epoch = 0; var selection = 0; var selected: String? = null
        var historyLoading = false; var historyError: String? = null; var sending = false
        fun startFirstLoad(id: String): Boolean {
            if (selected != id) return false
            historyLoading = true; historyError = null
            return true
        }
        fun failFirstLoad(scope: RequestScope, message: String, codex: Boolean = true) {
            val applies = if (codex) scope.appliesTo(epoch, selection, selected)
                else scope.appliesToSession(epoch, selected)
            if (!applies) return
            historyLoading = false; historyError = message
        }
        fun endPagination(scope: RequestScope, codex: Boolean = true) {
            val applies = if (codex) scope.appliesTo(epoch, selection, selected)
                else scope.appliesToSession(epoch, selected)
            if (applies) historyLoading = false
        }
    }

    @Test fun lateFailureFromOldSessionNeverClearsNewSessionsLoading() {
        val chat = FakeChat()
        // Open session A and start its first history load, capturing its scope.
        chat.selected = "A"; assertTrue(chat.startFirstLoad("A"))
        val scope = RequestScope(chat.epoch, chat.selection, "A")
        // User opens session B before A's history returns: B owns the loading state now.
        chat.selection++; chat.selected = "B"; assertTrue(chat.startFirstLoad("B"))
        // A's failure finally arrives — it must not clear or error B's view.
        chat.failFirstLoad(scope, "加载失败")
        assertTrue(chat.historyLoading)
        assertNull(chat.historyError)
    }

    @Test fun lateResultAfterDisconnectReconnectIsDiscarded() {
        val chat = FakeChat()
        chat.selected = "A"; assertTrue(chat.startFirstLoad("A"))
        val scope = RequestScope(chat.epoch, chat.selection, "A")
        // Disconnect + reconnect bumps the connection generation.
        chat.epoch++
        chat.failFirstLoad(scope, "SSH 通道已关闭")
        assertTrue(chat.historyLoading)
        assertNull(chat.historyError)
    }

    @Test fun failureForTheCurrentSessionEndsLoadingWithError() {
        val chat = FakeChat()
        chat.selected = "A"; assertTrue(chat.startFirstLoad("A"))
        chat.failFirstLoad(RequestScope(chat.epoch, chat.selection, "A"), "连接已关闭")
        assertFalse(chat.historyLoading)
        assertEquals("连接已关闭", chat.historyError)
    }

    @Test fun latePaginationCleanupCannotClearAnotherSessionsLoading() {
        val chat = FakeChat()
        chat.selected = "A"; chat.historyLoading = true
        val scope = RequestScope(chat.epoch, chat.selection, "A")
        chat.selection++; chat.selected = "B"; chat.historyLoading = true
        chat.endPagination(scope)
        assertTrue(chat.historyLoading)
        // Same-scope completion does clear it.
        chat.selected = "A"; chat.selection--
        chat.endPagination(scope)
        assertFalse(chat.historyLoading)
    }

    @Test fun zcodeScopedCleanupIgnoresSelectionEpochButHonorsGenerationAndSession() {
        val chat = FakeChat()
        chat.selected = "A"; assertTrue(chat.startFirstLoad("A"))
        val scope = RequestScope(chat.epoch, sessionId = "A")
        // A different session is never touched.
        chat.selected = "B"
        chat.failFirstLoad(scope, "失败", codex = false)
        assertTrue(chat.historyLoading)
        // Same session after reconnect is ignored too.
        chat.selected = "A"; chat.epoch++
        chat.endPagination(scope, codex = false)
        assertTrue(chat.historyLoading)
        // Same session, same generation: applies.
        chat.epoch--
        chat.failFirstLoad(scope, "失败", codex = false)
        assertFalse(chat.historyLoading)
        assertEquals("失败", chat.historyError)
    }

    @Test fun inFlightSendClearsOnlyItsOwnSessionAndKeepsUncertainOwnership() {
        val chat = FakeChat()
        chat.selected = "A"; chat.sending = true
        val scope = RequestScope(chat.epoch, chat.selection, "A")
        // Switched away mid-send: finally must not touch B, and B's open already reset sending.
        chat.selection++; chat.selected = "B"; chat.sending = false
        val guardClears = scope.appliesTo(chat.epoch, chat.selection, chat.selected)
        assertFalse(guardClears)
        assertFalse(chat.sending)
        // Same session: the guarded finally does clear the sending display state.
        assertTrue(scope.appliesTo(scope.epoch, scope.selection, "A"))
    }

    @Test fun offlineOpenShowsReconnectHintInsteadOfSpinner() {
        // openThread sets loading=connected; offline must show an error/hint, never a spinner.
        val connected = false
        val historyLoading = connected
        val historyError = if (connected) null else "离线缓存 · 重连后加载会话内容"
        assertFalse(historyLoading)
        assertEquals(TimelineView.Error, timelineView(0, historyLoading, historyError))
        assertEquals(TimelineView.Loading, timelineView(0, true, null))
    }

    @Test fun terminalPhaseClearsStaleStartReceiptButKeepsRealInformation() {
        // running → completedSuccess must not keep showing "已开始执行" next to 已完成.
        val lifecycle = listOf("running" to "已开始执行", "completedSuccess" to "已开始执行").map { (phase, notice) ->
            noticeForPhase(phase, notice)
        }
        assertEquals(listOf("已开始执行", ""), lifecycle)
        assertEquals("", noticeForPhase("completedInterrupted", "ZCode 已排队，等待当前任务完成"))
        assertEquals("", noticeForPhase("failed", "已插入当前任务"))
        // Real errors, model hints, and unknown notices pass through untouched.
        assertEquals("额度读取失败", noticeForPhase("completedSuccess", "额度读取失败"))
        assertEquals("下次发送使用此计划和模型", noticeForPhase("completedSuccess", "下次发送使用此计划和模型"))
        assertEquals("已开始执行", noticeForPhase("running", "已开始执行"))
        assertEquals("已开始执行", noticeForPhase("prewarming", "已开始执行"))
    }

    @Test fun sessionListStatusMapsKnownEnumsAndFallsBackOnUnknown() {
        assertEquals("执行中", zcodeSessionStatusLabel("running"))
        assertEquals("执行中", zcodeSessionStatusLabel("InProgress"))
        assertEquals("已完成", zcodeSessionStatusLabel("done"))
        assertEquals("已中断", zcodeSessionStatusLabel("Cancelled"))
        assertEquals("失败", zcodeSessionStatusLabel("failed"))
        assertEquals("空闲", zcodeSessionStatusLabel(" idle "))
        // Unknown or blank upstream enums must hide, letting the card show its placeholder.
        assertEquals("", zcodeSessionStatusLabel(""))
        assertEquals("", zcodeSessionStatusLabel("some-future-enum"))
    }
}
