package com.gmch.codexremote

import org.json.JSONObject

/**
 * Workspace identity is part of the key: equal paths on different hosts never share status.
 * P1 display metadata rides the same live stream: pending interaction counts, the protocol
 * bounded assistant preview, activity time and background-work mark. Every field is
 * optional and tolerant — an older bridge or a partial summary only degrades the extras.
 */
data class ZLiveSessionStatus(
    val id: String, val path: String, val identity: String, val phase: String,
    val lastActivityAt: Long = 0, val preview: String = "",
    val pendingPermissions: Int = 0, val pendingInputs: Int = 0,
    val hasBackgroundWork: Boolean = false, val lastOutcome: String = ""
)

internal fun zcodeLiveStatuses(data: JSONObject): List<ZLiveSessionStatus> =
    data.optJSONArray("workspaces").objects().filter { it.optBoolean("fresh") }.flatMap { workspace ->
        val path = workspace.string("workspacePath")
        val identity = workspace.string("workspaceIdentity")
        workspace.optJSONArray("sessions").objects().mapNotNull { session ->
            val phase = session.string("phase")
            if (!path.startsWith('/') || session.string("sessionId").isBlank() || phase !in zcodePhases) null
            else {
                val pending = session.optJSONObject("pendingInteractionSummary")
                ZLiveSessionStatus(session.string("sessionId"), path, identity, phase,
                    (session.opt("lastActivityAt") as? Number)?.toLong() ?: 0,
                    session.string("lastAssistantPreview").take(512),
                    (pending?.opt("permissionCount") as? Number)?.toInt() ?: 0,
                    (pending?.opt("userInputCount") as? Number)?.toInt() ?: 0,
                    session.optBoolean("hasBackgroundWork"), session.string("lastOutcome"))
            }
        }
    }

private val zcodePhases = setOf("running", "prewarming", "waiting", "completedSuccess", "completedInterrupted", "error", "draft")

internal fun zcodeListStatus(session: ZSession, fresh: Boolean, live: List<ZLiveSessionStatus>): String {
    if (!fresh) return ""
    val phase = live.find { it.id == session.id && it.path == session.path && it.identity == session.identity }?.phase
    if (phase != null) return zcodeSessionStatusLabel(phase)
    // readTree can supply a historical outcome, never evidence of a running task.
    return when (zcodeSessionStatusLabel(session.status)) {
        "已完成" -> "上次已完成"; "已中断" -> "上次已中断"; "失败" -> "上次失败"; else -> ""
    }
}

/** Per-session list metadata (ZCode P1): what the row shows beyond title/status/time. */
data class ZListMeta(
    val pending: Int = 0, val backgroundWork: Boolean = false,
    val preview: String = "", val lastActivityAt: Long = 0, val unread: Boolean = false
)

/**
 * Pure join of the live index with local seen timestamps. Unread follows the protocol's
 * own rule — lastActivityAt newer than the last time this device read the session — with
 * one local refinement: a session never opened on this device has no seen record and is
 * not marked unread (first launch stays calm; new desktop-side sessions still surface via
 * status text). The currently-open session is never unread (its reads update seen).
 */
internal fun buildListMeta(
    sessions: List<ZSession>, live: List<ZLiveSessionStatus>,
    seen: Map<String, Long>, openSessionId: String?
): Map<String, ZListMeta> {
    val liveById = live.associateBy { it.id }
    return sessions.associate { session ->
        val status = liveById[session.id]?.takeIf { it.path == session.path && it.identity == session.identity }
        val seenAt = seen[session.id]
        session.id to ZListMeta(
            pending = (status?.pendingPermissions ?: 0) + (status?.pendingInputs ?: 0),
            backgroundWork = status?.hasBackgroundWork ?: false,
            preview = status?.preview.orEmpty(),
            lastActivityAt = status?.lastActivityAt ?: 0,
            unread = session.id != openSessionId && seenAt != null && (status?.lastActivityAt ?: 0) > seenAt)
    }
}
