package com.gmch.codexremote

import org.json.JSONObject

/** Workspace identity is part of the key: equal paths on different hosts never share status. */
data class ZLiveSessionStatus(val id: String, val path: String, val identity: String, val phase: String)

internal fun zcodeLiveStatuses(data: JSONObject): List<ZLiveSessionStatus> =
    data.optJSONArray("workspaces").objects().filter { it.optBoolean("fresh") }.flatMap { workspace ->
        val path = workspace.string("workspacePath")
        val identity = workspace.string("workspaceIdentity")
        workspace.optJSONArray("sessions").objects().mapNotNull { session ->
            val phase = session.string("phase")
            if (!path.startsWith('/') || session.string("sessionId").isBlank() || phase !in zcodePhases) null
            else ZLiveSessionStatus(session.string("sessionId"), path, identity, phase)
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
