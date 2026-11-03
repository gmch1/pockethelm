package com.gmch.codexremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Android decode pipeline for photo picking: bounded source read, bounds validation,
 * power-of-two sampling, EXIF orientation, alpha flattening onto white, exact scale to
 * the output cap and a bounded JPEG quality ladder. Every decision value comes from
 * ImagePolicy so the strategy stays JVM-testable; this layer only executes it.
 */
class ImageProcessor(private val context: Context) {

    /** Display name and MIME of the picked item; failures degrade to generic values. */
    fun sourceInfo(uri: Uri): Pair<String, String?> {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return (name ?: "图片") to runCatching { context.contentResolver.getType(uri) }.getOrNull()
    }

    /** Returns the processed JPEG attachment, or a user-presentable reason on failure. */
    fun process(uri: Uri, sourceName: String, sourceMime: String?): Result<ChatAttachment> {
        if (sourceMime != null && sourceMime !in ImagePolicy.ALLOWED_SOURCE_MIME)
            return Result.failure(IllegalArgumentException("不支持的图片格式"))
        return runCatching {
            val source = readBounded(uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            ImagePolicy.checkSource(bounds.outWidth, bounds.outHeight, source.size.toLong())?.let {
                throw IllegalArgumentException(it)
            }
            var bitmap = decodeSampled(source, bounds.outWidth, bounds.outHeight)
            bitmap = applyExif(bitmap, source)
            bitmap = flatten(bitmap)
            bitmap = scaleToCap(bitmap)
            encode(bitmap).let { ChatAttachment.new(it, sourceName) }
        }
    }

    /** Streams the source into a bounded buffer: the provider's reported size is advisory. */
    private fun readBounded(uri: Uri): ByteArray {
        val out = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > ImagePolicy.SOURCE_MAX_BYTES)
                    throw IllegalArgumentException("图片文件过大")
                out.write(buffer, 0, read)
            }
        } ?: throw IllegalArgumentException("无法读取所选图片")
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) throw IllegalArgumentException("无法读取所选图片")
        return bytes
    }

    private fun decodeSampled(source: ByteArray, width: Int, height: Int): Bitmap {
        // Animation formats decode to their first frame — the UI hints at that on pick.
        val sample = ImagePolicy.sampleSize(width, height)
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("无法解码所选图片")
        return bitmap
    }

    /** Rotates/flips per EXIF; strips nothing else — the JPEG re-encode drops all metadata. */
    private fun applyExif(bitmap: Bitmap, source: ByteArray): Bitmap {
        val orientation = runCatching {
            source.inputStream().use { ExifInterface(it) }.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    /** Composites any alpha onto white: JPEG has no transparency. */
    private fun flatten(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
        return flat
    }

    private fun scaleToCap(bitmap: Bitmap): Bitmap {
        val cap = ImagePolicy.OUTPUT_MAX_DIMENSION
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= cap) return bitmap
        val scale = cap.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    /** Bounded quality ladder; failure after the last step rejects the image outright. */
    private fun encode(bitmap: Bitmap): ByteArray {
        var attempt = 0
        while (true) {
            val quality = ImagePolicy.qualityAt(attempt)
                ?: throw IllegalArgumentException("图片压缩后仍超出大小限制")
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= ImagePolicy.OUTPUT_MAX_BYTES) {
                bitmap.recycle()
                return bytes
            }
            attempt++
        }
    }
}
