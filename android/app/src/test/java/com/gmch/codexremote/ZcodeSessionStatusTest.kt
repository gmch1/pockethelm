package com.gmch.codexremote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZcodeSessionStatusTest {
    private val session = ZSession("s", "任务", "/work", "host-a", status = "completed")
    private fun live(phase: String) = listOf(ZLiveSessionStatus("s", "/work", "host-a", phase))

    @Test fun livePhasesOverrideHistoricalOutcomeAndKeepTerminalMeaning() {
        for ((phase, label) in mapOf("running" to "执行中", "prewarming" to "执行中", "waiting" to "等待中",
            "completedSuccess" to "已完成", "completedInterrupted" to "已中断", "error" to "失败", "draft" to "空闲"))
            assertEquals(label, zcodeListStatus(session, true, live(phase)))
    }
    @Test fun offlineNeverClaimsRunningAndReadTreeIsHistoricalOnly() {
        assertEquals("", zcodeListStatus(session, false, live("running")))
        assertEquals("上次已完成", zcodeListStatus(session, true, emptyList()))
        assertEquals("", zcodeListStatus(session.copy(status = "running"), true, emptyList()))
    }
    @Test fun same_path_and_session_id_on_different_hosts_are_isolated() {
        assertEquals("上次已完成", zcodeListStatus(session.copy(identity = "host-b"), true, live("running")))
        assertEquals("上次已完成", zcodeListStatus(session.copy(path = "/other"), true, live("running")))
    }
    @Test fun replacement_drops_missing_and_stale_workspaces() {
        val data = JSONObject("""{"workspaces":[
            {"workspacePath":"/work","workspaceIdentity":"host-a","fresh":true,"sessions":[{"sessionId":"s","phase":"running"}]},
            {"workspacePath":"/work","workspaceIdentity":"host-b","fresh":false,"sessions":[{"sessionId":"s","phase":"running"}]},
            {"workspacePath":"/other","fresh":true,"sessions":[{"sessionId":"x","phase":"unknown"}]}]}""")
        assertEquals(live("running"), zcodeLiveStatuses(data))
        assertTrue(zcodeLiveStatuses(JSONObject("""{"workspaces":[]}""")).isEmpty())
    }
    // ---- P1 (2026-11-02 zcode-p1-parity): live metadata & unread join ----

    @Test fun liveStatusesCarryP1MetadataTolerantly() {
        val data = JSONObject("""{"workspaces":[{"workspacePath":"/work","workspaceIdentity":"host-a","fresh":true,
            "sessions":[{"sessionId":"s","phase":"waiting","lastActivityAt":1730000000000,
            "lastAssistantPreview":"最近的回复","pendingInteractionSummary":{"permissionCount":1,"userInputCount":2},
            "hasBackgroundWork":true,"lastOutcome":"error"},
            {"sessionId":"t","phase":"running"}]}]}""")
        val statuses = zcodeLiveStatuses(data)
        val rich = statuses.first { it.id == "s" }
        assertEquals(1730000000000L, rich.lastActivityAt)
        assertEquals("最近的回复", rich.preview)
        assertEquals(1, rich.pendingPermissions)
        assertEquals(2, rich.pendingInputs)
        assertTrue(rich.hasBackgroundWork)
        assertEquals("error", rich.lastOutcome)
        val plain = statuses.first { it.id == "t" }
        assertEquals(0, plain.lastActivityAt); assertEquals("", plain.preview)
        assertEquals(0, plain.pendingPermissions); assertFalse(plain.hasBackgroundWork)
    }

    @Test fun listMetaUnreadOnlyForKnownSessionsWithNewerActivity() {
        val sessions = listOf(session, session.copy(id = "t"), session.copy(id = "u"))
        val live = listOf(ZLiveSessionStatus("s", "/work", "host-a", "running", lastActivityAt = 200),
            ZLiveSessionStatus("t", "/work", "host-a", "running", lastActivityAt = 300),
            ZLiveSessionStatus("u", "/work", "host-a", "running", lastActivityAt = 300))
        val seen = mapOf("s" to 100L, "t" to 400L)
        val meta = buildListMeta(sessions, live, seen, openSessionId = "u")
        assertTrue(meta.getValue("s").unread)      // known + newer activity
        assertFalse(meta.getValue("t").unread)     // known but activity already seen
        assertFalse(meta.getValue("u").unread)     // never opened on this device: calm first view
        assertEquals(200L, meta.getValue("s").lastActivityAt)
        // The open session is never unread even when its seen record is stale.
        val openNewer = buildListMeta(sessions, live, seen, openSessionId = "t")
        assertFalse(openNewer.getValue("t").unread)
    }

    @Test fun listMetaPendingSumsCountsAndSkipsForeignWorkspaces() {
        val live = listOf(ZLiveSessionStatus("s", "/other", "host-a", "waiting",
            pendingPermissions = 1, pendingInputs = 2, hasBackgroundWork = true))
        val meta = buildListMeta(listOf(session), live, emptyMap(), null)
        assertEquals(0, meta.getValue("s").pending)
        assertFalse(meta.getValue("s").backgroundWork)
        val own = listOf(ZLiveSessionStatus("s", "/work", "host-a", "waiting",
            pendingPermissions = 1, pendingInputs = 2, hasBackgroundWork = true))
        val rich = buildListMeta(listOf(session), own, emptyMap(), null)
        assertEquals(3, rich.getValue("s").pending)
        assertTrue(rich.getValue("s").backgroundWork)
    }
}
