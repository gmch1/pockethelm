package com.gmch.codexremote

import com.gmch.codexremote.engines.engineItem
import com.gmch.codexremote.engines.presentationItem
import com.gmch.codexremote.engines.codex.CodexEngineClient
import com.gmch.codexremote.engines.contract.EngineOrigin
import com.gmch.codexremote.transport.RpcEndpoint
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MessageTimeTest {
    private val time = Instant.parse("2026-11-05T16:30:00Z").toEpochMilli()
    @Test fun formatsPhoneTimezoneWithFullDateAndDoesNotInventMissingTime() {
        assertEquals("2026-11-06 00:30", messageTime(time, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-11-05 16:30", messageTime(time, ZoneId.of("UTC")))
        assertEquals("时间未提供", messageTime(0))
        for (invalid in listOf(null, JSONObject.NULL, -1, 0, "2026-11-06", Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE))
            assertEquals(0L, messageTimestamp(invalid))
    }
    @Test fun timelineAndCacheRoundTripPreserveServerTimeAndOldCachesStayUnknown() {
        val item = TimelineItem.parse(obj("id" to "i", "type" to "agentMessage", "text" to "answer", "startedAtMs" to time))
        assertEquals(time, item.timestampMs)
        assertEquals(item, item.engineItem().presentationItem())
        val snapshot = ResumeSnapshot("env", items = listOf(item))
        assertEquals(time, ResumeSnapshot.parse(snapshot.json()).items.single().timestampMs)
        val old = snapshot.json(); old.getJSONArray("items").getJSONObject(0).remove("timestampMs")
        assertEquals(0L, ResumeSnapshot.parse(old).items.single().timestampMs)
    }
    @Test fun historyWithoutTimingDoesNotEraseKnownServerTime() {
        val previous = TimelineItem("a", "agentMessage", "old", timestampMs = time)
        val timeline = linkedMapOf(previous.id to previous)
        applyHistorySnapshot(timeline, listOf(previous.copy(text = "new", timestampMs = 0)), timeline.toMap())
        assertEquals(time, timeline.getValue("a").timestampMs)
        assertEquals("new", timeline.getValue("a").text)
    }
    @Test fun zcodeUsesRowCreatedTime() {
        val rows = obj("items" to JSONArray().put(obj("id" to "i", "kind" to "userInput", "text" to "hello", "createdAt" to time)))
        assertEquals(time, zcodeItems(rows).single().timestampMs)
        rows.getJSONArray("items").getJSONObject(0).remove("createdAt")
        assertEquals(0L, zcodeItems(rows).single().timestampMs)
    }
    @Test fun codexReadsEntryTimestampNotOnlyNestedItemAndPreservesCallbackOrder() = runBlocking {
        val rpc = object : RpcEndpoint {
            override suspend fun call(method: String, params: JSONObject, timeout: Long, onResult: (suspend (JSONObject) -> Unit)?): JSONObject {
                val result = obj("data" to JSONArray().put(obj("startedAtMs" to time,
                    "item" to obj("id" to "i", "type" to "agentMessage", "text" to "answer"))))
                onResult?.invoke(result); return result
            }
        }
        val client = CodexEngineClient(EngineOrigin("env", "codex", "one"), rpc, "/work")
        var applied = 0L
        val page = client.history(client.session("s"), onPage = { applied = it.items.single().timestampMs })
        assertEquals(time, applied); assertEquals(time, page.items.single().timestampMs)
    }
}
