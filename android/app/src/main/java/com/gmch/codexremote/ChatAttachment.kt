package com.gmch.codexremote

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * In-memory image attachment shared by both engines (task 2026-11-03-image-attachments).
 * Bytes are always processed JPEG output — never the source file, never persisted; the
 * stable [id] keys uploads, remote temp paths and retry reuse across one scope.
 */
data class ChatAttachment(
    val id: String,
    val data: ByteArray,
    val displayName: String,
    val mime: String = "image/jpeg"
) {
    val bytes get() = data.size
    val fileName get() = ImagePolicy.outputFileName(id)
    /** Serialized for display logs/bubbles only — data never leaves this object except to an upload. */
    fun summary() = "$displayName (${bytes / 1024}KB)"

    companion object {
        fun new(processed: ByteArray, sourceName: String): ChatAttachment {
            val id = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
            return ChatAttachment(id, processed, ImagePolicy.cleanDisplayName(sourceName))
        }
    }
}

/**
 * Pure, JVM-testable decisions of the image pipeline: sampling, quality steps, name
 * normalization, source limits and the ZCode phone-chunk plan. Android decode/EXIF work
 * lives in ImageProcessor; nothing here touches android.*.
 */
object ImagePolicy {
    const val SOURCE_MAX_BYTES = 32L * 1024 * 1024
    const val SOURCE_MAX_SIDE = 32768
    const val SOURCE_MAX_PIXELS = 100_000_000L
    const val OUTPUT_MAX_DIMENSION = 2048
    const val OUTPUT_MAX_BYTES = 8L * 1024 * 1024
    const val MESSAGE_MAX = 4
    val ALLOWED_SOURCE_MIME = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    const val OUTPUT_MIME = "image/jpeg"

    /** Quality ladder: bounded, no endless re-encode loop. Null ends the loop as failure. */
    val QUALITY_STEPS = intArrayOf(85, 75, 65, 55, 50)
    fun qualityAt(attempt: Int): Int? = QUALITY_STEPS.getOrNull(attempt)

    /** Validates decoded source bounds; null means "reject" with the given reason. */
    fun checkSource(width: Int, height: Int, sourceBytes: Long): String? = when {
        width <= 0 || height <= 0 -> "无法识别的图片"
        width > SOURCE_MAX_SIDE || height > SOURCE_MAX_SIDE -> "图片尺寸过大"
        width.toLong() * height > SOURCE_MAX_PIXELS -> "图片像素过多"
        sourceBytes > SOURCE_MAX_BYTES -> "图片文件过大"
        else -> null
    }

    /** Largest power-of-two inSampleSize whose result still covers the target dimension;
     *  the exact scale-down happens after decode, so sampling only bounds memory. */
    fun sampleSize(width: Int, height: Int, maxDimension: Int = OUTPUT_MAX_DIMENSION): Int {
        var size = 1
        while (size < 64 && maxOf(width, height) / (size * 2) >= maxDimension) size *= 2
        return size
    }

    fun outputFileName(id: String) = "pockethelm-$id.jpg"

    /** Display name for chips: strip paths/control chars, cap length, always .jpg. */
    fun cleanDisplayName(source: String): String {
        val base = source.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f\\x7f]"), "").trim()
        val stem = base.substringBeforeLast('.').ifBlank { "图片" }
        return stem.take(48).ifBlank { "图片" }
    }

    /** Codex remote temp path: App-generated only, never from user input or server data. */
    fun codexPath(id: String) = "/tmp/pockethelm-img-$id.jpg"
}

/**
 * ZCode two-stage upload plan: the phone slices processed JPEG bytes into 96KiB raw
 * chunks (base64 stays well inside the bridge's 256KiB JSONL line); the bridge
 * re-aggregates into official 384KiB chunks. Phone totals are never forwarded upstream.
 */
object AttachmentUploadPlan {
    const val PHONE_CHUNK = 96 * 1024

    fun totalChunks(totalBytes: Int): Int = (totalBytes + PHONE_CHUNK - 1) / PHONE_CHUNK

    /** Size of chunk [index] for a file of [totalBytes]; null when index is out of range. */
    fun chunkSize(totalBytes: Int, index: Int): Int? {
        if (index < 0 || index >= totalChunks(totalBytes)) return null
        return if (index < totalChunks(totalBytes) - 1) PHONE_CHUNK
        else totalBytes - (totalChunks(totalBytes) - 1) * PHONE_CHUNK
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun beginParams(sessionTarget: JSONObject, attachment: ChatAttachment): JSONObject = sessionTarget
        .put("uploadId", "upl-${attachment.id}")
        .put("fileName", attachment.fileName)
        .put("mime", attachment.mime)
        .put("totalBytes", attachment.bytes)
        .put("totalChunks", totalChunks(attachment.bytes))
        .put("checksum", "sha256:${sha256Hex(attachment.data)}")

    fun chunkParams(uploadId: String, attachment: ChatAttachment, index: Int): JSONObject? {
        val size = chunkSize(attachment.bytes, index) ?: return null
        val slice = attachment.data.copyOfRange(index * PHONE_CHUNK, index * PHONE_CHUNK + size)
        val encoded = java.util.Base64.getEncoder().encodeToString(slice)
        return JSONObject().put("uploadId", uploadId).put("chunkIndex", index).put("dataBase64", encoded)
    }

    /** Bridge row projection → display text appended to the user message. */
    fun rowAttachmentText(attachments: JSONArray?): String =
        (attachments ?: return "").objects().joinToString("") { entry ->
            val name = entry.string("fileName")
            if (entry.optBoolean("truncated")) "\n[图片] …" else "\n[图片] ${name.substringAfterLast('/')}".trimEnd()
        }
}
