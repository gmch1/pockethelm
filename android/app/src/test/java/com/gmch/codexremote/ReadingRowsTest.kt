package com.gmch.codexremote

import org.junit.Assert.*
import org.junit.Test

class ReadingRowsTest {
    @Test fun groupsOnlyConsecutiveProcessItemsAndPreservesConversationAnchors() {
        val source = listOf(
            TimelineItem("u", "userMessage", "需求"),
            TimelineItem("r", "reasoning", "摘要"),
            TimelineItem("c", "commandExecution", "$ make\nlong output", "completed"),
            TimelineItem("f", "fileChange", "diff", "failed"),
            TimelineItem("a", "agentMessage", "结论"),
            TimelineItem("p", "plan", "下一步"),
            TimelineItem("t", "mcpToolCall", "details"),
            TimelineItem("unknown", "futureEvent", "must stay visible"))
        val outgoing = OutgoingMessage("s", "pending", "补充", OutgoingState.SENDING)
        val rows = readingRows(buildChatRows(source, listOf(outgoing)))
        assertEquals(listOf("u", "activity-r", "a", "p", "activity-t", "unknown", "outgoing-pending"), rows.map { it.key })
        assertEquals(source.subList(1, 4), rows[1].activity)
        assertTrue(activityHasFailure(rows[1].activity))
        assertEquals("工作过程 · 思考 · 2 项操作 · 有失败", activityLabel(rows[1].activity))
        assertEquals(listOf("u", "pending"), rows.filter { it.isUserRow }.map { it.id })
        assertEquals("结论", rows[2].text)
    }

    @Test fun growingProcessKeepsStableKeyAndDoesNotHideQueueOrBlankAnswer() {
        val tool = TimelineItem("c", "commandExecution", "output")
        val queue = listOf(QueuedMessage("q", "cmd", "queued"))
        val first = readingRows(buildChatRows(listOf(tool), emptyList(), queue))
        val next = readingRows(buildChatRows(listOf(tool, TimelineItem("c2", "fileChange", "diff")), emptyList(), queue))
        assertEquals(first.first().key, next.first().key)
        assertTrue(next.last().isUserRow)
        val blank = readingRows(buildChatRows(listOf(TimelineItem("r", "reasoning", ""),
            TimelineItem("a", "agentMessage", "")), emptyList()))
        assertEquals(listOf("a"), blank.map { it.key })
    }

    @Test fun projectGroupsAndChildrenFollowLatestActivityAndStableTies() {
        val data = listOf(ZSession("a", "", "/a", updatedAt = 10),
            ZSession("b", "", "/b", updatedAt = 30), ZSession("c", "", "/a", updatedAt = 40),
            ZSession("d", "", "/a", updatedAt = 10), ZSession("e", "", "/c"))
        val groups = recentProjectGroups(data, { it.updatedAt }, { it.path })
        assertEquals(listOf("/a", "/b", "/c"), groups.keys.toList())
        assertEquals(listOf("c", "a", "d"), groups.getValue("/a").map { it.id })
        assertEquals("", relativeActivityTime(0, 100_000))
        assertEquals("1分", relativeActivityTime(1, 60_001))
        assertEquals("刚刚", relativeActivityTime(200_000, 100_000))
    }
}
