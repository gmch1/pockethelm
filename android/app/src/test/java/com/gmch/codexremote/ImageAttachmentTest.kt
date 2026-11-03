package com.gmch.codexremote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure strategy layer of the image pipeline (task 2026-11-03-image-attachments). */
class ImageAttachmentTest {
    @Test fun samplingCoversTheCapWithoutOversampling() {
        assertEquals(1, ImagePolicy.sampleSize(2048, 1024))      // already inside cap
        assertEquals(2, ImagePolicy.sampleSize(4096, 4096))      // one step to cover 2048
        assertEquals(8, ImagePolicy.sampleSize(16384, 2000))     // 16384/8=2048: exact cover
        assertEquals(32, ImagePolicy.sampleSize(65536, 100))     // 65536/32=2048: exact cover
        assertEquals(1, ImagePolicy.sampleSize(3000, 3000))      // 1500 after /2 < cap → keep 1
    }
    @Test fun sourceGuardsRejectImpossibleDimensionsAndSizes() {
        assertNull(ImagePolicy.checkSource(100, 100, 1024))
        assertEquals("无法识别的图片", ImagePolicy.checkSource(0, 100, 10))
        assertEquals("图片尺寸过大", ImagePolicy.checkSource(40000, 100, 10))
        assertEquals("图片像素过多", ImagePolicy.checkSource(20000, 20000, 10))
        assertEquals("图片文件过大", ImagePolicy.checkSource(100, 100, ImagePolicy.SOURCE_MAX_BYTES + 1))
    }
    @Test fun qualityLadderIsBounded() {
        assertEquals(85, ImagePolicy.qualityAt(0))
        assertEquals(50, ImagePolicy.qualityAt(4))
        assertNull("降质循环必须有终点", ImagePolicy.qualityAt(5))
    }
    @Test fun namesAreNormalizedAndPathsAreAppGenerated() {
        assertEquals("photo", ImagePolicy.cleanDisplayName("/storage/0/DCIM/photo.HEIC"))
        assertEquals("截图图", ImagePolicy.cleanDisplayName("  截图\t图.png "))
        assertEquals("图片", ImagePolicy.cleanDisplayName(".png"))  // stem-only garbage falls back
        assertEquals("pockethelm-abc123.jpg", ImagePolicy.outputFileName("abc123"))
        // Codex temp path comes only from the App-generated id: no user text, no separators.
        val path = ImagePolicy.codexPath("abc123")
        assertTrue(path.startsWith("/tmp/pockethelm-img-") && path.endsWith(".jpg"))
        assertFalse(path.substringAfter("pockethelm-img-").dropLast(4).any { it in "/\\\u0000 " })
    }
    @Test fun phoneChunkPlanCoversBoundaries() {
        assertEquals(1, AttachmentUploadPlan.totalChunks(100))
        assertEquals(1, AttachmentUploadPlan.totalChunks(96 * 1024))
        assertEquals(2, AttachmentUploadPlan.totalChunks(96 * 1024 + 1))
        assertEquals(86, AttachmentUploadPlan.totalChunks(8 * 1024 * 1024))  // 8MiB cap
        assertEquals(96 * 1024, AttachmentUploadPlan.chunkSize(8 * 1024 * 1024, 0))
        assertEquals(96 * 1024, AttachmentUploadPlan.chunkSize(8 * 1024 * 1024, 84))
        // 8MiB = 85*96KiB + 16KiB: last chunk is the remainder.
        assertEquals(8 * 1024 * 1024 - 85 * 96 * 1024, AttachmentUploadPlan.chunkSize(8 * 1024 * 1024, 85))
        assertNull(AttachmentUploadPlan.chunkSize(100, 1))
        assertNull(AttachmentUploadPlan.chunkSize(100, -1))
    }
    @Test fun beginParamsCarryIdentityAndChecksumOnly() {
        val attachment = ChatAttachment("id123", ByteArray(300), "显示名")
        val target = JSONObject().put("workspacePath", "/w").put("sessionId", "s")
        val params = AttachmentUploadPlan.beginParams(target, attachment)
        assertEquals("upl-id123", params.string("uploadId"))
        assertEquals("pockethelm-id123.jpg", params.string("fileName"))
        assertEquals("image/jpeg", params.string("mime"))
        assertEquals(300, params.optInt("totalBytes"))
        assertEquals(1, params.optInt("totalChunks"))
        assertEquals("sha256:${AttachmentUploadPlan.sha256Hex(attachment.data)}", params.string("checksum"))
        // The display name never becomes the upload filename.
        assertFalse(params.toString().contains("显示名"))
    }
    @Test fun rowAttachmentTextShowsNamesWithoutRefs() {
        val row = JSONObject("""{"attachments":[
            {"index":0,"fileName":"pockethelm-a.jpg","mime":"image/jpeg","bytes":10},
            {"index":1,"fileName":"pockethelm-b.jpg","mime":"image/jpeg","bytes":20}]}""")
        val text = AttachmentUploadPlan.rowAttachmentText(row.optJSONArray("attachments"))
        assertTrue(text.contains("[图片] pockethelm-a.jpg"))
        assertTrue(text.contains("[图片] pockethelm-b.jpg"))
        assertFalse("ref 不得出现在时间线", text.contains("zcode-artifact"))
    }
    @Test fun codexMessageInputMixesTextAndImages() {
        val mixed = messageInput("看这张", listOf("/tmp/a.jpg", "/tmp/b.jpg"))
        assertEquals(3, mixed.length())
        assertEquals("text", mixed.getJSONObject(0).string("type"))
        assertEquals("localImage", mixed.getJSONObject(1).string("type"))
        assertEquals("auto", mixed.getJSONObject(1).string("detail"))
        // Image-only sends never fabricate an empty text item.
        val only = messageInput("", listOf("/tmp/a.jpg"))
        assertEquals(1, only.length())
        assertEquals("localImage", only.getJSONObject(0).string("type"))
    }
    @Test fun codexTimelineLabelsImagesWithoutPathsOrData() {
        assertEquals("[图片] photo.jpg", imageItemLabel(obj("type" to "localImage", "path" to "/tmp/x/photo.jpg")))
        assertEquals("[图片]", imageItemLabel(obj("type" to "image", "url" to "data:image/png;base64,AAA")))
        assertEquals("hello", imageItemLabel(obj("type" to "text", "text" to "hello")))
        val item = TimelineItem.parse(obj("id" to "m", "type" to "userMessage",
            "content" to JSONArray().put(obj("type" to "text", "text" to "看"))
                .put(obj("type" to "localImage", "path" to "/tmp/pockethelm-img-1.jpg"))))
        assertEquals("看\n[图片] pockethelm-img-1.jpg", item.text)
    }
    @Test fun zcodeUserRowsAppendAttachmentPlaceholders() {
        val items = zcodeItems(JSONObject("""{"items":[
            {"id":"1","kind":"userInput","text":"看看","attachments":[
                {"index":0,"fileName":"pockethelm-a.jpg","mime":"image/jpeg","bytes":10}]},
            {"id":"2","kind":"assistantText","text":"好"}]}"""))
        assertTrue(items[0].text.contains("看看") && items[0].text.contains("[图片] pockethelm-a.jpg"))
        assertEquals("好", items[1].text)
    }
}
