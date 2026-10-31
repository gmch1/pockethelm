package com.gmch.codexremote

import org.json.JSONObject

data class SpeedOption(val id: String, val name: String, val description: String)
data class ModelOption(val id: String, val name: String, val description: String, val efforts: List<String>,
    val defaultEffort: String, val speeds: List<SpeedOption>) {
    companion object {
        fun parse(j: JSONObject) = ModelOption(j.string("model", j.string("id")), j.string("displayName", j.string("model")),
            j.string("description"), j.optJSONArray("supportedReasoningEfforts").objects().map { it.string("reasoningEffort") }.filter { it.isNotBlank() },
            j.string("defaultReasoningEffort"), j.optJSONArray("serviceTiers").objects().map {
                SpeedOption(it.string("id"), it.string("name"), it.string("description"))
            }.filter { it.id.isNotBlank() && it.id != "default" })
    }
}

data class ModelSettings(val model: String, val effort: String?, val tier: String, val confirmed: Boolean = true) {
    companion object {
        fun parse(j: JSONObject) = ModelSettings(j.string("model"),
            j.string(if (j.has("reasoningEffort")) "reasoningEffort" else "effort").ifBlank { null },
            j.string("serviceTier").ifBlank { "default" })
        fun metadata(j: JSONObject) = parse(j).copy(confirmed = false)
    }
    fun request(threadId: String, models: List<ModelOption>): JSONObject {
        val option = models.firstOrNull { it.id == model } ?: error("模型列表已变化，请重新选择")
        require(effort != null && effort in option.efforts) { "该模型不支持所选思考深度" }
        require(tier == "default" || option.speeds.any { it.id == tier }) { "该模型不支持所选速度" }
        // Explicit default is essential: omission would leave Fast enabled on the thread.
        return obj("threadId" to threadId, "model" to model, "effort" to effort, "serviceTier" to tier)
    }
}

fun effortLabel(value: String?) = when (value) {
    null, "" -> "默认"; "none" -> "无"; "minimal" -> "最少"; "low" -> "低"; "medium" -> "中"
    "high" -> "高"; "xhigh" -> "很高"; "max" -> "最高"; "ultra" -> "Ultra"; else -> value
}

data class QuotaWindow(val remaining: Double, val minutes: Long?, val resetsAt: Long?) {
    val label get() = when (minutes) {
        10080L -> "周额度"; null -> "额度窗口"
        else -> if (minutes % 1440L == 0L) "${minutes / 1440} 天额度" else if (minutes % 60L == 0L) "${minutes / 60} 小时额度" else "$minutes 分钟额度"
    }
    companion object {
        fun parse(j: JSONObject?): QuotaWindow? {
            j ?: return null
            val used = j.optDouble("usedPercent", Double.NaN)
            if (!used.isFinite()) return null
            return QuotaWindow((100.0 - used).coerceIn(0.0, 100.0),
                if (j.isNull("windowDurationMins")) null else j.optLong("windowDurationMins").takeIf { it > 0 },
                if (j.isNull("resetsAt")) null else j.optLong("resetsAt").takeIf { it > 0 })
        }
    }
}
data class QuotaBucket(val id: String, val name: String, val windows: List<QuotaWindow>, val credits: String?,
    val unlimitedCredits: Boolean, val blocked: Boolean)
data class AccountQuota(val buckets: List<QuotaBucket>, val ordinaryAllowed: Boolean?, val updatedAt: Long) {
    val remaining get() = (buckets.firstOrNull { it.id == "codex" } ?: buckets.firstOrNull())?.windows?.minOfOrNull { it.remaining }
    val blocked get() = ordinaryAllowed == false || (buckets.firstOrNull { it.id == "codex" } ?: buckets.firstOrNull())?.blocked == true
    companion object {
        fun parse(j: JSONObject, now: Long = System.currentTimeMillis()): AccountQuota {
            val all = j.optJSONObject("rateLimitsByLimitId")
            val raw = if (all != null && all.length() > 0) all.keys().asSequence().mapNotNull { key ->
                all.optJSONObject(key)?.let { key to it }
            }.toList() else listOfNotNull(j.optJSONObject("rateLimits")?.let { it.string("limitId", "codex") to it })
            val buckets = raw.map { (key, b) ->
                val credits = b.optJSONObject("credits")
                QuotaBucket(b.string("limitId", key), b.string("limitName").ifBlank { if (key == "codex") "Codex" else key },
                    listOfNotNull(QuotaWindow.parse(b.optJSONObject("primary")), QuotaWindow.parse(b.optJSONObject("secondary"))),
                    credits?.string("balance")?.ifBlank { null }, credits?.optBoolean("unlimited") == true,
                    b.optBoolean("spendControlReached") || !b.isNull("rateLimitReachedType"))
            }
            return AccountQuota(buckets, if (j.isNull("ordinaryUsageAllowed")) null else j.optBoolean("ordinaryUsageAllowed"), now)
        }
    }
}
