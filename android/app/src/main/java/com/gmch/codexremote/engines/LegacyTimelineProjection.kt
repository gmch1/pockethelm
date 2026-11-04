package com.gmch.codexremote.engines

import com.gmch.codexremote.TimelineItem
import com.gmch.codexremote.engines.contract.EngineTimelineItem

// Transition boundary: retain current presentation IDs/fields without moving cache formats.
internal fun TimelineItem.engineItem() = EngineTimelineItem(id, kind, text, status, clientId, entityId)
internal fun EngineTimelineItem.presentationItem() = TimelineItem(id, kind, text, status, clientId, entityId)
