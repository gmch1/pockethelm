package com.gmch.codexremote

import org.json.JSONObject

/**
 * A locally-sent message that has been shown in the chat immediately, before (or without)
 * the server confirming it into history. These are display/reconciliation records only —
 * never faked server history. The UI merges them into the timeline; the ViewModel owns
 * the per-session state transitions and drops each entry once the real message (or its
 * queue entry) shows up under the same client id.
 */
enum class OutgoingState { SENDING, ACCEPTED, QUEUED, FAILED, UNCERTAIN }

/** Map key isolating local records by dev-environment identity and session. */
data class OutgoingKey(val env: String, val sessionId: String)

data class OutgoingMessage(
    val sessionId: String,
    val clientId: String,
    val text: String,
    val state: OutgoingState,
    /** Receipt from the server ack, e.g. 已接收 / 已排队 / 已插入; may be refined per engine. */
    val receipt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** Server rows visible at submission; null denotes a restored record without an anchor. */
    val precedingItemIds: List<String>? = null
)

/**
 * Label shown on the local bubble. ACK receipts state what the server did with the
 * submission, never that the model has read it. A failed dispatch (explicit server
 * rejection) shows 发送失败; only a lost connection/response is 结果待确认.
 */
fun outgoingStatusLabel(m: OutgoingMessage): String = when (m.state) {
    OutgoingState.SENDING -> "发送中"
    OutgoingState.UNCERTAIN -> "结果待确认"
    OutgoingState.FAILED -> "发送失败"
    OutgoingState.QUEUED -> m.receipt.ifBlank { "已排队" }
    OutgoingState.ACCEPTED -> m.receipt.ifBlank { "已接收" }
}

/**
 * ZCode ack receipt by actual server delivery; a requested guide may genuinely come back
 * as queue. startNow shows plain 已接收 — the server receiving the submission says nothing
 * about whether the model has seen it. (finishAck keeps its own richer notice.)
 */
fun deliveryReceipt(delivery: String): String = when (delivery) {
    "guide" -> "已插入当前任务"; "queue" -> "已排队，等待当前任务完成"
    "startNow" -> "已接收"; else -> "已接收"
}

/**
 * Drop local records that have been reconciled into real history/queue entries by client id.
 * Text is never compared: identical text may be sent multiple times on purpose. Client ids
 * from non-user items do not count as reconciliation — only a real userMessage (or the
 * matching queue entry) proves the sent message landed.
 */
fun reconcileOutgoingList(
    outgoing: List<OutgoingMessage>, itemClientIds: Set<String>, queueClientIds: Set<String>
): List<OutgoingMessage> = outgoing.filter { it.clientId !in itemClientIds && it.clientId !in queueClientIds }

/** One renderable row of the chat timeline: server item, local outgoing bubble, or queue entry. */
data class ChatRow(
    val key: String, val item: TimelineItem? = null,
    val outgoing: OutgoingMessage? = null, val queued: QueuedMessage? = null,
    val activity: List<TimelineItem> = emptyList()
) {
    val id: String get() = item?.id ?: outgoing?.clientId ?: queued?.id.orEmpty()
    val text: String get() = outgoing?.text ?: queued?.text ?: item?.text.orEmpty()
    val timestampMs: Long get() = outgoing?.createdAt ?: item?.timestampMs ?: activity.firstOrNull()?.timestampMs ?: 0
    val isUserRow: Boolean get() = item?.kind == "userMessage" || outgoing != null || queued != null
}

/** Presentation only: preserve the original history and never fold a user's message or answer. */
fun readingRows(rows: List<ChatRow>): List<ChatRow> {
    val result = mutableListOf<ChatRow>()
    val activity = mutableListOf<TimelineItem>()
    fun flush() {
        if (activity.isNotEmpty()) {
            result.add(ChatRow("activity-${activity.first().id}", activity = activity.toList()))
            activity.clear()
        }
    }
    val processKinds = setOf("reasoning", "commandExecution", "fileChange", "mcpToolCall",
        "dynamicToolCall", "webSearch", "collabAgentToolCall", "contextCompaction")
    rows.forEach { row ->
        val item = row.item
        if (item?.kind == "reasoning" && item.text.isBlank()) return@forEach
        if (item != null && item.kind in processKinds) activity.add(item)
        else { flush(); result.add(row) }
    }
    flush()
    return result
}

fun activityHasFailure(items: List<TimelineItem>) = items.any {
    it.status.lowercase() in setOf("failed", "error", "declined", "denied")
}

fun activityLabel(items: List<TimelineItem>): String {
    val reasoning = items.count { it.kind == "reasoning" }
    val operations = items.size - reasoning
    val label = listOfNotNull("思考".takeIf { reasoning > 0 },
        "$operations 项操作".takeIf { operations > 0 }).joinToString(" · ")
    return "工作过程 · $label" + if (activityHasFailure(items)) " · 有失败" else ""
}

/**
 * Merge server items with not-yet-reconciled local outgoing records and queue entries.
 * Server items come first and keep their order; local bubbles follow so a just-sent
 * message is visible immediately without mutating server history. Queue rows are also
 * deduped against history: when the real userMessage has arrived but the queue snapshot
 * is stale, the message must not show up twice.
 */
fun buildChatRows(
    items: List<TimelineItem>, outgoing: List<OutgoingMessage>, queue: List<QueuedMessage> = emptyList()
): List<ChatRow> {
    val itemClientIds = items.filter { it.kind == "userMessage" }.map { it.clientId }.filter { it.isNotBlank() }.toSet()
    val queueClientIds = queue.map { it.clientId }.filter { it.isNotBlank() }.toSet()
    val rows = items.map { ChatRow(key = it.id, item = it) }
    val pending = outgoing.filter { it.clientId !in itemClientIds && it.clientId !in queueClientIds }
        .map { ChatRow(key = "outgoing-${it.clientId}", outgoing = it) }
    val queueRows = queue.filter { it.clientId !in itemClientIds }
        .map { ChatRow(key = "queue-${it.id.ifBlank { it.clientId }}", queued = it) }
    val anchoredFailures = pending.filter { it.outgoing?.state == OutgoingState.FAILED && it.outgoing.precedingItemIds != null }
    val positions = items.mapIndexed { index, item -> item.id to index }.toMap()
    val groups = anchoredFailures.groupBy { row ->
        row.outgoing!!.precedingItemIds!!.asReversed().firstNotNullOfOrNull { positions[it] } ?: -1
    }
    // Insert failures at their submission boundary, never behind future replies. If
    // the anchor left the loaded window, show before that window rather than at its tail.
    return buildList {
        addAll(groups[-1].orEmpty())
        rows.forEachIndexed { index, row -> add(row); addAll(groups[index].orEmpty()) }
        addAll(pending.filterNot { it in anchoredFailures })
        addAll(queueRows)
    }
}

/** ZCode queue rows carry queueItemId plus sourceCommandId linking back to the sent command. */
fun queueEntries(data: JSONObject): List<QueuedMessage> =
    data.optJSONArray("queue").objects().map { j ->
        val source = j.string("sourceCommandId")
        QueuedMessage(j.string("queueItemId", source), source, j.string("text", "已排队的消息"))
    }

/** ZCode read() dedupes local bubbles against these ids (user message + queue). */
fun reconcileIdsForRead(items: List<TimelineItem>, queue: List<QueuedMessage>): Pair<Set<String>, Set<String>> =
    items.filter { it.kind == "userMessage" }.map { it.clientId }.filter { it.isNotBlank() }.toSet() to
        queue.map { it.clientId }.filter { it.isNotBlank() }.toSet()

fun textArray(text: String): org.json.JSONArray = org.json.JSONArray().put(obj("type" to "text", "text" to text))
