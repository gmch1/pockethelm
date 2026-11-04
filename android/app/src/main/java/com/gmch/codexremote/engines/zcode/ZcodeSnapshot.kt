package com.gmch.codexremote.engines.zcode

import com.gmch.codexremote.*
import org.json.JSONObject

/** Typed native extension: runtime/interaction facts stay outside the common history contract. */
internal data class ZcodeSnapshot(val title: String, val logEpoch: String, val revision: Long,
    val items: List<TimelineItem>, val queue: List<QueuedMessage>, val selection: ZSelection?,
    val mode: String, val phase: String, val canStop: Boolean, val executionId: String,
    val firstRowId: Double?, val hasMore: Boolean, val interactionCount: Int,
    val interactions: List<ZInteraction>, val interactionIds: Set<String>, val interactionsComplete: Boolean,
    val presence: Map<String, Boolean>, val turn: ZTurn?, val lastError: String) {
    companion object {
        fun parse(j: JSONObject) = ZcodeSnapshot(j.string("title"), j.string("logEpoch"), j.optLong("revision"),
            zcodeItems(j), queueEntries(j),
            ZSelection.parse(j.optJSONObject("selection")), j.string("mode"), j.string("phase"), j.optBoolean("canStop"), j.string("executionId"),
            if (j.isNull("firstRowId")) null else j.optDouble("firstRowId"), j.optBoolean("hasMore"), j.optInt("interactionCount"),
            zcodeInteractions(j), j.optJSONArray("interactions").objects().map { it.string("interactionId") }.toSet(), j.optBoolean("interactionsComplete"),
            j.optJSONArray("interactionPresence").objects().mapNotNull {
                val pending = it.opt("pending") as? Boolean ?: return@mapNotNull null
                it.string("interactionId") to pending
            }.toMap(), ZTurn.parse(j.optJSONObject("turn")), j.string("lastError"))
    }
}
