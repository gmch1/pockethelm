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

    @Test fun listFiltersComposeAndSortFollowsPrototypeOrdering() {
        fun row(id: String, path: String, updated: Long, status: String = "", title: String = id) =
            WorkspaceSession(id, title, path, path.trimEnd('/').substringAfterLast('/'), status, updated)
        val data = listOf(row("a", "/pocket", 10, "已完成"), row("b", "/reader", 30, "执行中"),
            row("c", "/pocket", 40, "失败"), row("d", "/pocket", 10, "执行中"), row("e", "/store", 0))
        // Newest first across projects; running rows are not pinned, the prototype sorts by time only.
        assertEquals(listOf("c", "b", "a", "d", "e"), selectSessions(data, "", "", false, true).map { it.id })
        assertEquals(listOf("e", "a", "d", "b", "c"), selectSessions(data, "", "", false, false).map { it.id })
        assertEquals(listOf("b", "d"), selectSessions(data, "", "", true, true).map { it.id })
        assertEquals(listOf("c", "a", "d"), selectSessions(data, "", "/pocket", false, true).map { it.id })
        // Search hits title, project name and path; blank rows have no status until data is fresh.
        assertEquals(listOf("b"), selectSessions(data, "reader", "", false, true).map { it.id })
        assertEquals(listOf("c", "a", "d"), selectSessions(data, "pocket", "", false, true).map { it.id })
        assertEquals(listOf("d"), selectSessions(data, "pocket", "/pocket", true, true).map { it.id })
        assertTrue(selectSessions(data, "reader", "/pocket", true, true).isEmpty())
        // Two native workspaces can share a directory without sharing a project filter.
        val identities = listOf(row("one", "/shared", 10).copy(group = "p:one:/shared"),
            row("two", "/shared", 20).copy(group = "p:two:/shared"))
        assertEquals(listOf("one"), selectSessions(identities, "", "p:one:/shared", false, true).map { it.id })
    }

    @Test fun relativeTimeUsesPrototypeSpacingAndYesterday() {
        assertEquals("", relativeActivityTime(0, 100_000))
        assertEquals("刚刚", relativeActivityTime(200_000, 100_000))
        assertEquals("1 分钟", relativeActivityTime(1, 60_001))
        assertEquals("59 分钟", relativeActivityTime(59, 60 * 60_000 + 1))
        assertEquals("2 小时", relativeActivityTime(120, 121 * 60_000))
        assertEquals("昨天", relativeActivityTime(1, 1440 * 60_000 + 30_000))
        assertEquals("3 天", relativeActivityTime(1, 3 * 1440 * 60_000 + 30_000))
    }
}
