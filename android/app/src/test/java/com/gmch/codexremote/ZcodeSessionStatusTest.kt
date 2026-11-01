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
}
