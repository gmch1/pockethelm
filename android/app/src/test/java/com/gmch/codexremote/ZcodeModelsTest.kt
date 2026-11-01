package com.gmch.codexremote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZcodeModelsTest {
    @Test fun modelSelectionKeepsBillingProviderAndReasoning() {
        val first = ZSelection("account:bigmodel-individual-coding-plan", "GLM-5.3", "max")
        val other = first.copy(provider = "account:bigmodel-team-coding-plan")
        assertNotEquals(first, other)
        assertEquals(first, ZSelection.parse(first.json()))
        assertFalse(first.copy(level = "").json().has("options"))
    }
    @Test fun timelineSeparatesMessagesAndCompactTools() {
        val data = JSONObject("""{"items":[
          {"id":"2","kind":"userInput","text":"question","commandId":"pending-id"},
          {"id":"3","kind":"toolCall","text":"Bash\nsleep 1","status":"success"},
          {"id":"4","kind":"assistantText","text":"answer","status":"streaming"}
        ]}""")
        val items = zcodeItems(data)
        assertEquals(listOf("userMessage", "mcpToolCall", "agentMessage"), items.map { it.kind })
        assertEquals("pending-id", items[0].clientId)
        assertEquals("completed", items[1].status)
        assertEquals("running", items[2].status)
    }
    @Test fun missingQuotaIsNotPresentedAsZeroRemaining() {
        assertEquals("服务端未返回额度窗口", zcodeQuota(JSONObject("""{"limits":[]}""")))
        assertTrue(zcodeQuota(JSONObject("""{"limits":[{"type":"CREDIT_LIMIT","unit":3,"number":5,"remaining":547}]}""")).contains("剩余 547"))
        assertFalse(zcodeQuota(JSONObject("""{"limits":[{"type":"UNKNOWN"}]}""")).contains("剩余 0"))
    }
    @Test fun sessionListOrdersByMostRecentActivity() {
        val data = JSONObject("""{"sessions":[
          {"taskId":"a","title":"旧","updatedAt":1700000000},
          {"taskId":"b","title":"毫秒","updatedAt":1800000000000},
          {"taskId":"c","title":"ISO","updatedAt":"2023-11-14T22:13:20Z"},
          {"taskId":"d","title":"无时间"},
          {"taskId":"e","title":"坏值","updatedAt":"not-a-time"}
        ]}""")
        val sessions = data.optJSONArray("sessions").objects().map(ZSession::parse).sortedByDescending { it.updatedAt }
        // Seconds are normalized to millis and ISO strings parse, so mixed formats compare
        // correctly; peers without a usable timestamp keep their relative order at the end.
        assertEquals(listOf("b", "a", "c", "d", "e"), sessions.map { it.id })
        assertEquals(1700000000000L, sessions.first { it.id == "a" }.updatedAt)
        assertEquals(1800000000000L, sessions.first { it.id == "b" }.updatedAt)
        assertEquals(1700000000000L, sessions.first { it.id == "c" }.updatedAt)
        assertEquals(0L, sessions.first { it.id == "e" }.updatedAt)
    }
    @Test fun directoryListingDropsMalformedAndRelativeEntries() {
        val listing = ZDirectoryListing.parse(JSONObject("""{"path":"home/dev","parent":"/home",
          "directories":[
            {"name":"ok","path":"/opt/ok"},
            {"path":"/opt/noname"},
            {"name":"nopath"},
            {"name":7,"path":"/opt/badname"},
            {"name":"badpath","path":7},
            {"name":"relative","path":"dev/relative"},
            {"name":"","path":"/opt/empty"},
            "not-a-dict"
          ]}"""))
        assertEquals("home/dev", listing.path)
        assertEquals(listOf("ok"), listing.directories.map { it.name })
        assertEquals(listOf("/opt/ok"), listing.directories.map { it.path })
    }
}
