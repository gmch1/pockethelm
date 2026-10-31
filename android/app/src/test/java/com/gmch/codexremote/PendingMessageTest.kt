package com.gmch.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure reconciliation/display rules for locally-sent messages, shared by both engines. */
class PendingMessageTest {
    private fun outgoing(clientId: String, text: String, state: OutgoingState, receipt: String = "") =
        OutgoingMessage("s1", clientId, text, state, receipt)

    private fun userItem(id: String, clientId: String, text: String) =
        TimelineItem(id, "userMessage", text, "completed", clientId)

    @Test fun `sending bubble is visible before any rpc returns`() {
        val rows = buildChatRows(emptyList(), listOf(outgoing("c1", "任务文本", OutgoingState.SENDING)))
        assertEquals(1, rows.size)
        assertEquals("outgoing-c1", rows[0].key)
        assertEquals("发送中", outgoingStatusLabel(rows[0].outgoing!!))
        assertTrue(rows[0].isUserRow)
    }

    @Test fun `ack receipts never claim the model has read the message`() {
        assertEquals("已接收", outgoingStatusLabel(outgoing("c1", "t", OutgoingState.ACCEPTED)))
        assertEquals("已接收", deliveryReceipt("startNow"))
        assertEquals("已插入当前任务", deliveryReceipt("guide"))
        assertEquals("已排队，等待当前任务完成", deliveryReceipt("queue"))
        // A requested guide may genuinely come back as queue.
        assertEquals("已接收", deliveryReceipt(""))
    }

    @Test fun `event arriving first shows the message exactly once`() {
        val bubble = outgoing("c1", "任务文本", OutgoingState.ACCEPTED, "已接收")
        val rows = buildChatRows(listOf(userItem("r1", "c1", "任务文本")), listOf(bubble))
        assertEquals(listOf("r1"), rows.map { it.key })
        assertTrue(rows.all { it.outgoing == null })
    }

    @Test fun `queued then formal message never duplicates`() {
        val bubble = outgoing("c1", "任务文本", OutgoingState.QUEUED, "已排队")
        // Queued ack, queue snapshot already contains it: exactly the queue row shows.
        val queued = listOf(QueuedMessage("q1", "c1", "任务文本"))
        assertEquals(listOf("queue-q1"), buildChatRows(emptyList(), listOf(bubble), queued).map { it.key })
        // Formal userMessage arrives while the queue snapshot is still stale: no queue row,
        // no bubble — the history item is the only rendering.
        val rows = buildChatRows(listOf(userItem("r1", "c1", "任务文本")), listOf(bubble), queued)
        assertEquals(listOf("r1"), rows.map { it.key })
    }

    @Test fun `identical text with different ids stays separate`() {
        val rows = buildChatRows(emptyList(), listOf(
            outgoing("c1", "同样的文字", OutgoingState.ACCEPTED),
            outgoing("c2", "同样的文字", OutgoingState.SENDING)))
        assertEquals(listOf("outgoing-c1", "outgoing-c2"), rows.map { it.key })
        // One of them lands in history; only that one is reconciled away.
        val merged = buildChatRows(listOf(userItem("r1", "c1", "同样的文字")),
            listOf(outgoing("c1", "同样的文字", OutgoingState.ACCEPTED),
                outgoing("c2", "同样的文字", OutgoingState.SENDING)))
        assertEquals(listOf("r1", "outgoing-c2"), merged.map { it.key })
    }

    @Test fun `non-user items do not reconcile a pending message`() {
        // A tool/assistant row sharing the command id does not prove the user message landed.
        val tool = TimelineItem("t1", "mcpToolCall", "工具", "completed", "c1")
        val rows = buildChatRows(listOf(tool), listOf(outgoing("c1", "任务文本", OutgoingState.SENDING)))
        assertEquals(2, rows.size)
        assertEquals("outgoing-c1", rows[1].key)
    }

    @Test fun `failure and uncertain labels are distinct`() {
        assertEquals("发送失败", outgoingStatusLabel(outgoing("c1", "t", OutgoingState.FAILED)))
        assertEquals("结果待确认", outgoingStatusLabel(outgoing("c1", "t", OutgoingState.UNCERTAIN)))
    }

    @Test fun `reconcile list only trusts userMessage and queue client ids`() {
        val pending = listOf(outgoing("c1", "t", OutgoingState.ACCEPTED), outgoing("c2", "t", OutgoingState.SENDING))
        assertEquals(listOf("c1"), reconcileOutgoingList(pending, setOf("c2"), emptySet()).map { it.clientId })
        assertEquals(emptyList<String>(), reconcileOutgoingList(pending, emptySet(), setOf("c1", "c2")).map { it.clientId })
    }

    @Test fun `zcode queue rows use queueItemId and keep sourceCommandId`() {
        val data = obj("queue" to org.json.JSONArray()
            .put(obj("queueItemId" to "q1", "sourceCommandId" to "cmd-1", "text" to "任务A"))
            .put(obj("sourceCommandId" to "cmd-2")))
        val queue = queueEntries(data)
        assertEquals("q1", queue[0].id); assertEquals("cmd-1", queue[0].clientId); assertEquals("任务A", queue[0].text)
        // Missing queueItemId falls back to the source command id for a stable row key.
        assertEquals("cmd-2", queue[1].id); assertEquals("cmd-2", queue[1].clientId)
        assertEquals("已排队的消息", queue[1].text)
    }

    @Test fun `zcode read reconciliation ids come from userMessage items and queue only`() {
        val items = listOf(userItem("r1", "cmd-1", "任务"), TimelineItem("r2", "agentMessage", "回复", "", "cmd-9"))
        val (itemIds, queueIds) = reconcileIdsForRead(items, listOf(QueuedMessage("q1", "cmd-2", "任务")))
        assertEquals(setOf("cmd-1"), itemIds)
        assertEquals(setOf("cmd-2"), queueIds)
    }

    @Test fun `failed bubble keeps text and is not dropped by unknown ids`() {
        val failed = outgoing("c1", "会被拒绝的消息", OutgoingState.FAILED)
        val rows = buildChatRows(listOf(userItem("r1", "other", "别的消息")), listOf(failed))
        assertTrue(rows.any { it.outgoing == failed })
        assertFalse(outgoingStatusLabel(failed).contains("待确认"))
    }
}
