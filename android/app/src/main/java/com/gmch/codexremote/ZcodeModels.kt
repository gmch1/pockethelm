package com.gmch.codexremote

import org.json.JSONArray
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
    // Attachment rows carry names only; image bytes stay behind the read-back API (P2).
    val text = if (kind == "userMessage")
        it.string("text") + AttachmentUploadPlan.rowAttachmentText(it.optJSONArray("attachments"))
    else it.string("text")
    TimelineItem(it.getString("id"), kind, text, status, it.string("commandId"), it.string("entityId"), messageTimestamp(it.opt("createdAt")))
}

/**
 * Latest turnHeader projection from session/read: authoritative work time and CLI-aggregated
 * change totals. fileChanges/addiveMs are optional upstream — the rowId/entityId pair is the
 * target for the session/diff detail query and is always present when the turn exists.
 */
data class ZTurn(val rowId: Long, val entityId: String, val state: String, val activeMs: Long = 0,
                 val additions: Int = -1, val deletions: Int = -1, val files: Int = -1,
                 val reverted: Boolean = false) {
    val hasChanges get() = additions >= 0 || deletions >= 0
    companion object {
        fun parse(j: JSONObject?): ZTurn? {
            if (j == null || j.optLong("rowId", -1) < 0) return null
            val changes = j.optJSONObject("fileChanges")
            // org.json may surface counters as Integer or Long; accept any JSON number.
            fun counter(key: String) = (changes?.opt(key) as? Number)?.toInt() ?: -1
            return ZTurn(j.optLong("rowId"), j.string("entityId"), j.string("state"), j.optLong("activeMs"),
                counter("additions"), counter("deletions"), counter("files"), changes?.string("state") == "reverted")
        }
    }
}

/** Badge-facing summary for the shared ChatTimeline; engine-agnostic by design. */
fun ZTurn?.summary(): TurnDiffSummary? = this?.let { turn ->
    if (!turn.hasChanges && turn.entityId.isBlank()) null
    else TurnDiffSummary(turn.additions.coerceAtLeast(0), turn.deletions.coerceAtLeast(0),
        turn.files.coerceAtLeast(0), turn.reverted, turn.state == "running", turn.activeMs,
        turn.entityId.isNotBlank())
}

/** session/diff detail sheet text: totals, per-file counters and bounded patch hunks. */
fun formatFileChanges(j: JSONObject): String {
    val lines = mutableListOf<String>()
    val files = j.optJSONArray("items").objects()
    val additions = (j.opt("additions") as? Number)?.toInt() ?: -1
    val deletions = (j.opt("deletions") as? Number)?.toInt() ?: -1
    val total = (j.opt("files") as? Number)?.toInt() ?: files.size
    val head = buildString {
        append("$total 个文件")
        if (additions >= 0) append(" · +$additions")
        if (deletions >= 0) append(" · −$deletions")
        if (j.string("state") == "reverted") append(" · 已撤销")
    }
    lines.add(head)
    files.forEach { file ->
        lines.add("")
        var counters = listOfNotNull(
            (file.opt("additions") as? Number)?.let { "+${it.toInt()}" },
            (file.opt("deletions") as? Number)?.let { "−${it.toInt()}" },
            (file.opt("writeCount") as? Number)?.takeIf { it.toInt() > 0 }?.let { "改写 ${it.toInt()} 次" })
            .joinToString(" · ")
        val tools = file.optJSONArray("toolNames")?.strings()?.filter { it.isNotBlank() }.orEmpty()
        if (tools.isNotEmpty()) counters = listOf(counters, tools.joinToString("/")).filter { it.isNotBlank() }.joinToString(" · ")
        lines.add("${file.string("path")}${if (counters.isBlank()) "" else "\n$counters"}")
        file.optJSONArray("patches").objects().forEach { hunk ->
            val oldStart = (hunk.opt("oldStart") as? Number)?.toLong()
            val newStart = (hunk.opt("newStart") as? Number)?.toLong()
            lines.add("@@ -${oldStart ?: "?"} +${newStart ?: "?"} @@")
            hunk.optJSONArray("lines").strings().forEach { lines.add(it) }
        }
    }
    return lines.joinToString("\n").take(200_000)
}
// Start Plan uses raw token balances; Coding Plan uses rolling windows and percent used.
private fun JSONObject.quotaNumber(key: String): Double? = (opt(key) as? Number)?.toDouble()
    ?.takeIf { it.isFinite() && it >= 0 }
private fun quotaNumberText(value: Double): String = java.text.DecimalFormat("#,##0.##",
    java.text.DecimalFormatSymbols(java.util.Locale.US)).format(value)
private fun quotaRemainingPercent(limit: JSONObject, start: Boolean): Double? = if (start) {
    val total = limit.quotaNumber("number") ?: limit.quotaNumber("unit")
    val remaining = limit.quotaNumber("remaining")
    if (total != null && total > 0 && remaining != null) remaining / total * 100
    else limit.quotaNumber("percentage")?.takeIf { it <= 1 }?.times(100)
} else limit.quotaNumber("percentage")?.takeIf { it <= 100 }?.let { 100 - it }
private fun quotaPercentText(value: Double) = java.text.DecimalFormat("0.#",
    java.text.DecimalFormatSymbols(java.util.Locale.US)).format(value.coerceIn(0.0, 100.0)) + "%"

fun zcodeQuota(j: JSONObject): String {
    val limits = j.optJSONArray("limits").objects()
    if (limits.isEmpty()) return when {
        j.optBoolean("startPlanExpired") -> "Start Plan 已到期"
        j.string("unavailableReason") == "no_plan" -> "当前账号暂无可用计划"
        j.string("unavailableReason") == "not_authenticated" -> "请在开发机登录 ZCode 后重试"
        j.string("unavailableReason") == "not_configured" -> "当前计划未连接，请在开发机检查"
        j.string("unavailableReason") == "unavailable" -> "额度暂不可用，请重试"
        else -> "服务端未返回额度窗口"
    }
    val start = j.string("planKind") == "start-plan"
    return limits.joinToString("\n\n") { l ->
        val remaining = l.quotaNumber("remaining")
        val used = l.quotaNumber("currentValue") ?: if (start) l.quotaNumber("usage") else null
        // number/unit are time-window identifiers on Coding Plan, never totals.
        val total = if (start) l.quotaNumber("number") ?: l.quotaNumber("unit") else null
        val suffix = if (start) when (l.string("unitType")) {
            "token" -> " Tokens"; "request" -> " 次"; else -> ""
        } else ""
        val percent = quotaRemainingPercent(l, start)
        val title = if (start) {
            val names = l.optJSONArray("usageDetails").objects().mapNotNull { detail ->
                detail.string("displayName").ifBlank { detail.string("modelCode").removePrefix("model:") }
                    .takeIf { it.isNotBlank() }
            }.distinct().joinToString(" / ").ifBlank { "模型额度" }
            names + when (l.string("period")) { "daily" -> " · 每日额度"; "one_time" -> " · 一次性额度"; else -> "" }
        } else when (l.optInt("unit")) {
            3 -> "${l.optInt("number")} 小时额度"; 6 -> "${l.optInt("number")} 周额度"
            else -> "额度"
        }
        val balance = listOfNotNull(remaining?.let { "剩余 ${quotaNumberText(it)}$suffix" },
            percent?.let { (if (remaining == null) "剩余 " else "") + quotaPercentText(it) }).joinToString(" · ")
        val consumption = listOfNotNull(used?.let { "已用 ${quotaNumberText(it)}$suffix" },
            total?.let { "总量 ${quotaNumberText(it)}$suffix" }).joinToString(" / ")
        val reset = l.quotaNumber("nextResetTime")?.takeIf { it > 0 }?.let { value ->
            runCatching { java.time.Instant.ofEpochMilli(value.toLong()).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrNull()
        }?.let { time -> time + if (start) {
            when (l.string("period")) { "daily" -> " 重置"; "one_time" -> " 到期"; else -> " 周期结束" }
        } else " 重置" }
        listOfNotNull(title, balance.ifBlank { "剩余量暂不可用" }, consumption.takeIf { it.isNotBlank() }, reset).joinToString("\n")
    }
}

fun zcodeQuotaSummary(j: JSONObject, model: String): String {
    val limits = j.optJSONArray("limits").objects()
    val start = j.string("planKind") == "start-plan"
    val limit = if (start) {
        val matching = limits.filter { l -> l.optJSONArray("usageDetails").objects().any {
            it.string("modelCode").removePrefix("model:").equals(model, ignoreCase = true)
        } }
        // Do not aggregate unrelated buckets, models, or expiry windows into one balance.
        matching.singleOrNull()
    } else limits.firstOrNull { it.optInt("unit") == 3 } ?: limits.firstOrNull()
    return limit?.let { quotaRemainingPercent(it, start) }?.let { "剩余 ${quotaPercentText(it)}" } ?: "额度 ▾"
}

data class ZInteractionOption(val optionId: String, val label: String, val kind: String = "")
data class ZQuestionOption(val value: String, val label: String, val description: String = "")
data class ZQuestion(val question: String, val header: String, val options: List<ZQuestionOption>, val multiSelect: Boolean)
data class ZAutoResolution(val state: String, val deadlineAt: Long = 0, val snoozedAt: Long = 0)
data class ZInteraction(
    val id: String, val kind: String, val supported: Boolean, val unsupportedReason: String = "",
    val toolName: String = "", val summary: String = "", val prompt: String = "",
    val freeText: Boolean = false, val sensitive: Boolean = false,
    val options: List<ZInteractionOption> = emptyList(), val questions: List<ZQuestion> = emptyList(),
    val currentQuestionIndex: Int = 0, val autoResolution: ZAutoResolution? = null,
    val answerDrafts: Map<String, List<String>> = emptyMap()
) {
    /** Display title for banner and form header. */
    val title get() = when (kind) {
        "permission" -> "ZCode 权限确认"
        "userInput" -> if (questions.isNotEmpty()) "ZCode 提问" else "ZCode 等待输入"
        "workspaceHookReview" -> "工作区变更审查"
        else -> "待交互"
    }
    companion object {
        fun parse(j: JSONObject) = ZInteraction(
            id = j.string("interactionId"), kind = j.string("kind"),
            supported = j.optBoolean("supported"), unsupportedReason = j.string("unsupportedReason"),
            toolName = j.string("toolName"), summary = j.string("summary"), prompt = j.string("prompt"),
            freeText = j.optBoolean("freeText"), sensitive = j.optBoolean("sensitive"),
            options = j.optJSONArray("options").objects().map {
                ZInteractionOption(it.string("optionId"), it.string("label"), it.string("kind")) },
            questions = j.optJSONArray("questions").objects().map { q ->
                ZQuestion(q.string("question"), q.string("header"),
                    q.optJSONArray("options").objects().map { ZQuestionOption(it.string("value"), it.string("label"), it.string("description")) },
                    q.optBoolean("multiSelect")) },
            currentQuestionIndex = j.optInt("currentQuestionIndex"),
            autoResolution = j.optJSONObject("autoResolution")
                ?.let { ZAutoResolution(it.string("state"), it.optLong("deadlineAt"), it.optLong("snoozedAt")) },
            answerDrafts = j.optJSONObject("answerDrafts")?.let { drafts ->
                drafts.keys().asSequence().associateWith { key -> drafts.optJSONArray(key)?.strings() ?: emptyList() }
            } ?: emptyMap())
    }
}

fun zcodeInteractions(j: JSONObject): List<ZInteraction> =
    j.optJSONArray("interactions").objects().map { ZInteraction.parse(it) }

/** Per-question in-memory form state; never persisted, even between snapshots of one interaction. */
data class ZFormDraft(val selectedValues: List<String> = emptyList(), val customAnswer: String = "")

private fun formAnswers(question: ZQuestion, draft: ZFormDraft?): List<String> {
    val custom = draft?.customAnswer?.trim().orEmpty()
    val selected = draft?.selectedValues.orEmpty()
    if (!question.multiSelect) return if (custom.isNotEmpty()) listOf(custom) else selected.take(1)
    return selected + (if (custom.isNotEmpty()) listOf(custom) else emptyList())
}

/**
 * Builds the accept content exactly in the official client's shape
 * (ElicitationDialog.buildElicitationResponseContent): only questions the user actually
 * answered appear; `answers` joins with ", "; `answer_N` keeps multi-select as an array and
 * single-select as one string; a single question also gets the legacy `answer` key.
 * Callers must ensure at least one question is answered — this client disables submit
 * otherwise (the bridge rejects an all-empty accept).
 */
fun buildInteractionContent(questions: List<ZQuestion>, drafts: Map<String, ZFormDraft>): JSONObject {
    fun answersFor(question: ZQuestion) = formAnswers(question, drafts[question.question])
    val content = JSONObject()
    val answers = JSONObject()
    questions.forEach { question ->
        val values = answersFor(question)
        if (values.isNotEmpty()) answers.put(question.question, values.joinToString(", "))
    }
    content.put("answers", answers)
    questions.forEachIndexed { index, question ->
        val values = answersFor(question)
        if (values.isNotEmpty()) content.put("answer_$index", if (question.multiSelect) JSONArray(values) else values.first())
    }
    if (questions.size == 1) {
        val values = answersFor(questions.first())
        if (values.isNotEmpty()) content.put("answer", if (questions.first().multiSelect) JSONArray(values) else values.first())
    }
    return content
}

/** True when at least one question has a real answer — gates the submit button. */
fun interactionFormAnswered(questions: List<ZQuestion>, drafts: Map<String, ZFormDraft>): Boolean =
    questions.any { formAnswers(it, drafts[it.question]).isNotEmpty() }


/** Upstream drafts use answer_N (or numeric index), not the displayed question text. */
fun initialInteractionDrafts(interaction: ZInteraction): Map<String, ZFormDraft> =
    interaction.questions.mapIndexed { index, question ->
        val values = interaction.answerDrafts["answer_$index"] ?: interaction.answerDrafts[index.toString()].orEmpty()
        val options = question.options.map { it.value }.toSet()
        val custom = values.filter { it !in options }.joinToString(", ")
        question.question to ZFormDraft(
            if (!question.multiSelect && custom.isNotEmpty()) emptyList() else values.filter { it in options }, custom)
    }.toMap()

fun interactionCountdown(deadlineAt: Long, now: Long): String {
    val millis = if (deadlineAt < 1_000_000_000_000L) deadlineAt * 1000L else deadlineAt
    val remaining = millis - now
    return if (remaining <= 0) "自动结束时间已到，等待服务器确认"
    else "即将自动结束 · 剩余约 ${(remaining + 59_999) / 60_000} 分钟"
}
