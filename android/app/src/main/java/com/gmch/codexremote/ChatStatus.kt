package com.gmch.codexremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared, pure chat status helpers for both engines (Codex and ZCode).
 * ViewModels own the state transitions; these functions decide what the UI
 * presents for a given state so the transitions stay unit-testable on the JVM.
 */

enum class TimelineView { Loading, Error, Empty, Content }

/** Empty state ("开始新会话") must only show after history truly loaded nothing. */
fun timelineView(itemCount: Int, loading: Boolean, error: String?): TimelineView = when {
    itemCount > 0 -> TimelineView.Content
    !error.isNullOrBlank() -> TimelineView.Error
    loading -> TimelineView.Loading
    else -> TimelineView.Empty
}

enum class AgentRun { Idle, Running, Waiting, WaitingInteraction, Offline }

fun codexAgentRun(connected: Boolean, runtime: String): AgentRun = when {
    !connected -> AgentRun.Offline
    runtime == "active" -> AgentRun.Running
    else -> AgentRun.Idle
}

/** A confirmed running engine is the only state that earns an activity spinner. */
fun zcodeAgentRun(connected: Boolean, phase: String, interactionCount: Int = 0): AgentRun = when {
    !connected -> AgentRun.Offline
    interactionCount > 0 -> AgentRun.WaitingInteraction
    phase == "running" -> AgentRun.Running
    phase == "waiting" -> AgentRun.Waiting
    else -> AgentRun.Idle
}

fun agentRunLabel(run: AgentRun, idleLabel: String) = when (run) {
    AgentRun.Offline -> "离线 · 状态不更新"
    AgentRun.Running -> "执行中"
    AgentRun.Waiting -> "等待回复"
    AgentRun.WaitingInteraction -> "等待交互"
    AgentRun.Idle -> idleLabel
}

/** Short Chinese label for a timeline item status; unknown values hide instead of leaking English. */
fun timelineStatusLabel(status: String) = when (status) {
    "inProgress", "running" -> "执行中"
    "streaming" -> "生成中"
    "completed", "success" -> "已完成"
    "failed", "error" -> "失败"
    "declined" -> "已拒绝"
    "interrupted" -> "已中断"
    else -> ""
}

private val transientReceiptNotices = setOf(
    "已开始执行", "ZCode 已排队，等待当前任务完成", "已插入当前任务", "命令已接收")

/**
 * Status of a ZCode session card, from the bridge's bulk readTree listing. The upstream
 * enum is not part of this repo, so known variants map to short Chinese and anything
 * unknown (including blank) returns empty for the UI to fall back to its placeholder.
 */
fun zcodeSessionStatusLabel(status: String): String = when (status.trim().lowercase()) {
    "running", "active", "inprogress", "executing" -> "执行中"
    "waiting", "pending" -> "等待中"
    "prewarming" -> "准备中"
    "completed", "completedsuccess", "done", "success", "finished" -> "已完成"
    "completedinterrupted", "interrupted", "cancelled", "canceled", "stopped" -> "已中断"
    "error", "failed" -> "失败"
    "draft", "idle" -> "空闲"
    else -> ""
}

/**
 * Once the engine reaches a terminal phase, an earlier send receipt ("已开始执行") is stale
 * and must not contradict 已完成/失败/已中断. Model-selection hints, real errors, and the
 * pending protection live elsewhere and pass through untouched.
 */
fun noticeForPhase(phase: String, notice: String): String = when {
    phase in setOf("completedSuccess", "completedInterrupted", "error", "failed") && notice in transientReceiptNotices -> ""
    else -> notice
}

/**
 * Captured at dispatch: the connection generation, selection epoch, and session a
 * request was started for. A late result may only touch that exact scope — never a
 * session the user has since opened, nor state surviving a reconnect.
 */
data class RequestScope(val epoch: Int, val selection: Int = 0, val sessionId: String) {
    fun appliesTo(epoch: Int, selection: Int, sessionId: String?) =
        this.epoch == epoch && this.selection == selection && this.sessionId == sessionId
    /** For engines without a selection epoch (ZCode). */
    fun appliesToSession(epoch: Int, sessionId: String?) =
        this.epoch == epoch && this.sessionId == sessionId
}

/**
 * A command persisted before dispatch shows as pending immediately, but while the
 * request is still in flight that is not an uncertain outcome — the protection
 * prompt only applies once the request has settled without a definite result.
 */
fun pendingProtectionVisible(pending: Boolean, inFlight: Boolean) = pending && !inFlight

/**
 * Shared guard for every async chat result (history load, send, cleanup in finally):
 * a late result may only touch the connection generation, selection epoch, and
 * session it was started for — never a session the user has since opened.
 */


@Composable
fun RunStatusDot(run: AgentRun, modifier: Modifier = Modifier) {
    when (run) {
        AgentRun.Running ->
            CircularProgressIndicator(modifier.size(10.dp), strokeWidth = 1.5.dp)
        else -> Box(modifier.size(8.dp).background(
            if (run == AgentRun.Offline) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), CircleShape))
    }
}

@Composable
fun RunStatusRow(run: AgentRun, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RunStatusDot(run)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
