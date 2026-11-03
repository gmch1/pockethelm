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

fun codexAgentRun(connected: Boolean, runtime: String, pendingApprovals: Int = 0): AgentRun = when {
    !connected -> AgentRun.Offline
    pendingApprovals > 0 -> AgentRun.WaitingInteraction
    runtime == "active" -> AgentRun.Running
    else -> AgentRun.Idle
}

/** A confirmed running engine is the only state that earns an activity spinner. */
fun zcodeAgentRun(connected: Boolean, phase: String, interactionCount: Int = 0): AgentRun = when {
    !connected -> AgentRun.Offline
    interactionCount > 0 -> AgentRun.WaitingInteraction
    phase in setOf("running", "prewarming") -> AgentRun.Running
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

/**
 * User-facing text for a connection failure (task 2026-11-04-connection-error-leaks):
 * DNS resolution right after unlock usually means the network is not ready yet, so
 * the raw exception class name is replaced with an actionable message that keeps the
 * failing host. JSch wraps UnknownHostException into JSchException
 * ("Session.connect: java.net.UnknownHostException: host"), so the cause chain is
 * walked with a bounded depth before falling back to the original message.
 * [endpointLabel] names what failed to resolve (跳板机/开发机) so all DNS failures
 * are not mislabelled as the jump host.
 */
internal fun connectionFailureMessage(t: Throwable, endpointLabel: String = "连接"): String {
    var cause: Throwable? = t
    var depth = 0
    while (cause != null && depth < 8) {
        if (cause is java.net.UnknownHostException) {
            val host = cause.message?.trim().orEmpty()
            return if (host.isBlank() || host.length > 200)
                "无法解析${endpointLabel}地址：设备网络可能尚未就绪，请稍后点击重连"
            else "无法解析${endpointLabel}地址 $host：设备网络可能尚未就绪，请稍后点击重连"
        }
        cause = cause.cause
        depth += 1
    }
    return t.message ?: t.javaClass.simpleName
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

/**
 * Authoritative per-turn change summary for the shared timeline badge (ZCode P1). Numbers
 * come from the CLI's turnHeader aggregation — the client never sums tool diffs itself.
 */
data class TurnDiffSummary(
    val additions: Int, val deletions: Int, val files: Int,
    val reverted: Boolean, val running: Boolean, val activeMs: Long,
    val detailAvailable: Boolean
)

/** "更改 +318 −0 · 2 文件 · 已撤销" — counters pair up whenever the turn reported any change. */
fun turnDiffBadgeLabel(summary: TurnDiffSummary): String {
    val parts = mutableListOf<String>()
    if (summary.additions > 0 || summary.deletions > 0 || summary.files > 0) {
        parts += "+${summary.additions} −${summary.deletions}"
        if (summary.files > 0) parts += "${summary.files} 文件"
    }
    if (summary.reverted) parts += "已撤销"
    return if (parts.isEmpty()) "本轮修改" else "更改 " + parts.joinToString(" · ")
}

/** Authoritative active work time; formatted as 秒/分秒/分 only when the turn reports it. */
fun turnDurationLabel(activeMs: Long): String = when {
    activeMs <= 0 -> ""
    activeMs < 60_000 -> "${activeMs / 1000} 秒"
    else -> "${activeMs / 60_000} 分 ${activeMs % 60_000 / 1000} 秒"
}

private val transientReceiptNotices = setOf(
    "已开始执行", "ZCode 已排队，等待当前任务完成", "已插入当前任务", "命令已接收")

/**
 * Status of a ZCode session card, from the bridge's bulk readTree listing. The upstream
 * enum is not part of this repo, so known variants map to short Chinese and anything
 * unknown (including blank) returns empty for the UI to fall back to its placeholder.
 */
fun zcodeSessionStatusLabel(status: String): String = when (status.trim().lowercase()) {
    "running", "prewarming", "active", "inprogress", "executing" -> "执行中"
    "waiting", "pending" -> "等待中"
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
