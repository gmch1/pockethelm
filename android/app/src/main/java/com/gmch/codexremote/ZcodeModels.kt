package com.gmch.codexremote

import org.json.JSONObject

data class ZModel(val id: String, val levels: List<String>)
data class ZProvider(val id: String, val name: String, val kind: String, val guard: String, val models: List<ZModel>)
data class ZSelection(val provider: String, val model: String, val level: String = "") {
    fun json() = obj("providerId" to provider, "modelId" to model,
        "options" to level.takeIf { it.isNotBlank() }?.let { obj("reasoningLevel" to it) })
    companion object {
        fun parse(j: JSONObject?) = j?.takeIf { it.string("providerId").isNotBlank() && it.string("modelId").isNotBlank() }
            ?.let { ZSelection(it.string("providerId"), it.string("modelId"), it.optJSONObject("options")?.string("reasoningLevel").orEmpty()) }
    }
}
data class ZProject(val id: String, val name: String, val roots: List<String>)
data class ZDirectoryEntry(val name: String, val path: String)
data class ZDirectoryListing(val path: String, val parent: String, val hasParent: Boolean, val directories: List<ZDirectoryEntry>) {
    companion object {
        fun parse(j: JSONObject) = ZDirectoryListing(j.string("path"), j.string("parent"),
            j.optBoolean("hasParent"), j.optJSONArray("directories").objects().mapNotNull { e ->
                // optString silently coerces numbers to text, so require the JSON types explicitly.
                val name = (e.opt("name") as? String).orEmpty()
                val path = (e.opt("path") as? String).orEmpty()
                if (name.isNotBlank() && path.startsWith('/')) ZDirectoryEntry(name, path) else null
            })
    }
}
data class ZSession(val id: String, val title: String, val path: String, val identity: String = "", val project: String = "",
                    val status: String = "", val updatedAt: Long = 0) {
    fun target() = obj("sessionId" to id, "workspacePath" to path, "workspaceIdentity" to identity.takeIf { it.isNotBlank() })
    companion object {
        fun parse(j: JSONObject) = ZSession(j.string("taskId"), j.string("title", "新会话").ifBlank { "新会话" },
            j.string("workspacePath"), j.string("workspaceIdentity"), j.string("projectId"), j.string("status"),
            zcodeTimestamp(j.opt("updatedAt")))
    }
}

/**
 * Tolerant timestamp from the bulk task listing: epoch seconds or millis as a number,
 * ISO-8601 as a string; anything absent or unparseable becomes 0 so it sorts last
 * without disturbing the relative order of its peers.
 */
internal fun zcodeTimestamp(value: Any?): Long = when (value) {
    is Number -> value.toLong().let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
    is String -> runCatching { java.time.Instant.parse(value.trim()).toEpochMilli() }
        .getOrElse { runCatching { java.time.LocalDateTime.parse(value.trim().replace(" ", "T"))
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0L) }
    else -> 0L
}
fun zcodeProviders(j: JSONObject) = j.optJSONArray("providers").objects().map { p ->
    ZProvider(p.getString("id"), p.getString("name"), p.string("planKind"), p.getString("guard"),
        p.optJSONArray("models").objects().map { ZModel(it.getString("id"), it.optJSONArray("levels").strings()) })
}
fun zcodeItems(j: JSONObject) = j.optJSONArray("items").objects().map {
    val kind = when (it.string("kind")) {
        "userInput" -> "userMessage"; "assistantText" -> "agentMessage"; "reasoning" -> "reasoning"
        "toolCall" -> "mcpToolCall"; else -> "dynamicToolCall"
    }
    val status = when (it.string("status")) {
        "success", "complete" -> "completed"; "streaming", "inputStreaming" -> "running"
        "error" -> "failed"; "cancelled" -> "interrupted"; else -> it.string("status")
    }
    TimelineItem(it.getString("id"), kind, it.string("text"), status, it.string("commandId"))
}
fun zcodeQuota(j: JSONObject): String {
    val limits = j.optJSONArray("limits").objects()
    if (limits.isEmpty()) return "服务端未返回额度窗口"
    return limits.joinToString(" · ") { l ->
        val window = when (l.optInt("unit")) {
            3 -> "${l.optInt("number")} 小时"; 6 -> "${l.optInt("number")} 周"; else -> l.string("type", "额度")
        }
        when {
            l.has("remaining") -> "$window 剩余 ${l.optDouble("remaining").toInt()}"
            l.has("percentage") -> "$window 已用 ${l.optDouble("percentage").toInt()}%"
            else -> "$window 暂无剩余值"
        }
    }
}
