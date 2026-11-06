package com.gmch.codexremote

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Native item timestamps are Unix milliseconds. Zero means unavailable, never now. */
internal fun messageTimestamp(value: Any?): Long = (value as? Number)?.toDouble()?.let {
    if (it.isFinite() && it > 0 && it <= 253402300799999.0) it.toLong() else 0L
} ?: 0L

internal fun messageTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (millis <= 0) "时间未提供" else runCatching {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(millis).atZone(zone))
    }.getOrDefault("时间未提供")
