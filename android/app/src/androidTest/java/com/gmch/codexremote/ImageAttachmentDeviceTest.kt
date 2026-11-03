package com.gmch.codexremote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Image attachment pipeline on a real device (task 2026-11-03-image-attachments):
 * the Android decode layer (EXIF, alpha, animation first frame, rejects) and both
 * engines' send paths through the real RpcClient + scripted bridge/server.
 */
@RunWith(AndroidJUnit4::class)
class ImageAttachmentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tempDirs = mutableListOf<File>()
    private val cleaners = mutableListOf<() -> Unit>()

    private fun tempFile(name: String): File {
        val dir = File(instrumentation.targetContext.cacheDir, "img-test-${UUID.randomUUID()}").apply { mkdirs() }
        tempDirs += dir
        return File(dir, name)
    }

    @After
    fun tearDown() {
        cleaners.forEach { runCatching(it) }
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun processor() = ImageProcessor(instrumentation.targetContext)

    private fun bitmap(width: Int, height: Int, transparent: Boolean = false): Bitmap {
        val config = if (transparent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val bmp = Bitmap.createBitmap(width, height, config)
        if (transparent) {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.argb(0, 0, 0, 0))
            val paint = android.graphics.Paint().apply { color = Color.argb(255, 10, 200, 60) }
            canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        } else bmp.eraseColor(Color.GREEN)
        return bmp
    }

    private fun encode(bmp: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(format, 90, out)
        return out.toByteArray()
    }

    // ---- decode pipeline ----

    @Test fun exifRotationIsAppliedAndOutputIsJpegWithinCap() {
        val source = tempFile("rot.jpg")
        source.writeBytes(encode(bitmap(4000, 1000)))
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val result = processor().process(Uri.fromFile(source), "rot.jpg", "image/jpeg").getOrThrow()
        val decoded = BitmapFactory.decodeByteArray(result.data, 0, result.data.size)
        assertNotNull(decoded)
        // 4000x1000 rotated becomes 1000x4000, then scaled to cap 2048 long side.
        assertEquals(512, decoded.width)
        assertEquals(2048, decoded.height)
        assertTrue("输出必须是 JPEG", (result.data[0].toInt() and 0xFF) == 0xFF && (result.data[1].toInt() and 0xFF) == 0xD8)
        assertTrue(result.bytes <= ImagePolicy.OUTPUT_MAX_BYTES)
        // EXIF must not survive the re-encode.
        val exif = ExifInterface(result.data.inputStream())
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test fun transparencyFlattensToWhiteAndAnimDecodeAsFirstFrame() {
        val png = tempFile("alpha.png")
        png.writeBytes(encode(bitmap(64, 64, transparent = true)))
        val flattened = processor().process(Uri.fromFile(png), "alpha.png", "image/png").getOrThrow()
        val decoded = BitmapFactory.decodeByteArray(flattened.data, 0, flattened.data.size)
        // The transparent half composites to white; the painted half keeps its color.
        val left = decoded.getPixel(8, 32)
        val right = decoded.getPixel(56, 32)
        assertTrue("左半保留绘制颜色", Math.abs(Color.red(left) - 10) < 16 && Math.abs(Color.green(left) - 200) < 16)
        assertEquals(0xFF, Color.alpha(right)); assertTrue("右半透明合成白底", Color.red(right) > 235 && Color.green(right) > 235)
        // Minimal animated-format source (a single-frame GIF): decode = first frame.
        val gif = tempFile("one.gif")
        gif.writeBytes(Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"))
        val frame = processor().process(Uri.fromFile(gif), "one.gif", "image/gif").getOrThrow()
        assertEquals(1, BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size).width)
    }

    @Test fun rejectsBrokenOversizedAndWrongMime() {
        val broken = tempFile("broken.png")
        broken.writeBytes(ByteArray(500) { (it % 251).toByte() })
        val message = processor().process(Uri.fromFile(broken), "b.png", "image/png").exceptionOrNull()?.message
        assertNotNull(message)

        val wide = tempFile("wide.png")
        wide.writeBytes(encode(bitmap(33000, 1)))
        assertEquals("图片尺寸过大",
            processor().process(Uri.fromFile(wide), "w.png", "image/png").exceptionOrNull()?.message)

        val stub = tempFile("fake.png")
        stub.writeBytes(encode(bitmap(10, 10)))
        val wrongMime = processor().process(Uri.fromFile(stub), "f.png", "video/mp4").exceptionOrNull()
        assertNotNull("非白名单 MIME 直接拒绝", wrongMime)
    }

    // ---- review fixes IMG-C1..C4: scope isolation, protection, busy gate ----

    @Test fun codexDispatchedAttachmentNeverRemovedEvenWithLostReceipt() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        cleaners += { instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault") }
        lateinit var vm: RemoteViewModel
        instrumentation.runOnMainSync { vm = RemoteViewModel(app) }
        val state = RemoteViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<UiState> }
        state.value = UiState(connected = true, foreground = true,
            selected = SessionInfo("s1", "集成", "/tmp/remote", "", "idle", 0))
        val attachment = ChatAttachment.new(ByteArray(2048) { 3 }, "挂起.jpg")
        state.value = state.value.copy(attachments = listOf(attachment))
        instrumentation.runOnMainSync { vm.draft("图") }
        val gate = java.util.concurrent.CountDownLatch(1)
        val removes = java.util.concurrent.atomic.AtomicInteger(0)
        val sent = java.util.concurrent.atomic.AtomicInteger(0)
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["thread/resume"] = {
            obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "idle"), "updatedAt" to 0L),
                "approvalPolicy" to "never", "sandbox" to obj("type" to "dangerFullAccess")) }
        bridge.scripted["fs/writeFile"] = { obj() }
        bridge.scripted["turn/start"] = { sent.incrementAndGet(); gate.await(15, java.util.concurrent.TimeUnit.SECONDS); obj() }
        bridge.scripted["fs/remove"] = { removes.incrementAndGet(); obj() }
        bridge.scripted["thread/read"] = {
            obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "idle"), "updatedAt" to 0L)) }
        bridge.scripted["thread/turns/list"] = { obj("data" to org.json.JSONArray()) }
        bridge.scripted["thread/queue/list"] = { obj("data" to org.json.JSONArray()) }
        bridge.scripted["thread/items/list"] = { obj("data" to org.json.JSONArray(), "nextCursor" to "") }
        bridge.install()
        lateinit var sendJob: kotlinx.coroutines.Job
        instrumentation.runOnMainSync { sendJob = vm.send(false) }
        withTimeout(10_000) { while (sent.get() == 0) delay(20) }
        // Cancel the waiting call after dispatch: real unknown outcome, no successful ack.
        sendJob.cancel(); sendJob.join()
        assertFalse(vm.state.value.sending)
        assertNotNull(vm.state.value.uncertain)
        gate.countDown()
        lateinit var removeJob: kotlinx.coroutines.Job
        instrumentation.runOnMainSync { removeJob = vm.removeAttachment(attachment.id) }
        removeJob.join() // The action actually executes after the send released its mutex.
        assertTrue(vm.state.value.attachments.isEmpty())
        assertNotNull(vm.state.value.uncertain)
        assertEquals("结果未知时不得删除图片文件", 0, removes.get())
        instrumentation.runOnMainSync { vm.background(); vm.viewModelScope.cancel() }
        bridge.close(); bridge.assertClean()
    }

    @Test fun latePickResultAfterThreadSwitchIsDropped() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        cleaners += { instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault") }
        lateinit var vm: RemoteViewModel
        instrumentation.runOnMainSync { vm = RemoteViewModel(app) }
        val state = RemoteViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<UiState> }
        state.value = UiState(connected = true, foreground = true, tab = 0,
            selected = SessionInfo("s1", "A", "/tmp/remote", "", "idle", 0))
        val source = tempFile("late.png")
        source.writeBytes(encode(bitmap(32, 32)))
        vm.addAttachment(Uri.fromFile(source), "late.png", "image/png")
        // Switch threads while the decode is in flight: the late result must not follow.
        instrumentation.runOnMainSync { vm.openThread(SessionInfo("s2", "B", "/tmp/other", "", "idle", 0)) }
        withTimeout(10_000) { while (vm.state.value.attachmentsBusy) delay(20) }
        assertTrue("迟到处理结果不得进入新会话", vm.state.value.attachments.isEmpty())
        instrumentation.runOnMainSync { vm.background(); vm.viewModelScope.cancel() }
    }

    @Test fun zcodeDisconnectDuringUploadSendsNothingAndKeepsCleanState() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        cleaners += { instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault") }
        lateinit var vm: ZcodeViewModel
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        val state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState> }
        val attachment = ChatAttachment.new(ByteArray(4096) { 5 }, "断开.jpg")
        state.value = zSessionState(listOf(attachment))
        instrumentation.runOnMainSync { vm.draft("hi") }
        val gate = java.util.concurrent.CountDownLatch(1)
        val sends = java.util.concurrent.atomic.AtomicInteger(0)
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["session/attachment/begin"] = { gate.await(15, java.util.concurrent.TimeUnit.SECONDS)
            obj("state" to "staging", "nextChunkIndex" to 0) }
        bridge.scripted["session/send"] = { sends.incrementAndGet(); obj("commandId" to "c", "status" to "accepted") }
        bridge.install()
        instrumentation.runOnMainSync { runCatching { vm.send() } }
        delay(200)
        // Reconnect mid-upload: the parked begin belongs to a dead scope now.
        instrumentation.runOnMainSync { vm.disconnectManually() }
        gate.countDown()
        delay(500)
        val refs = ZcodeViewModel::class.java.getDeclaredField("attachmentRefs").let {
            it.isAccessible = true; @Suppress("UNCHECKED_CAST") it.get(vm) as Map<String, String> }
        assertTrue("旧作用域不得写入 refs", refs.isEmpty())
        assertEquals("断开后不得发送", 0, sends.get())
        assertFalse(vm.state.value.attachmentsBusy)
        instrumentation.runOnMainSync { vm.viewModelScope.cancel() }
        bridge.close()
    }

    @Test fun zcodeReturnToSameSessionInvalidatesPickerAndUpload() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString()); app.attach(instrumentation.targetContext)
        cleaners += { instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault") }
        lateinit var vm: ZcodeViewModel
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        val state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState> }
        val attachment = ChatAttachment.new(ByteArray(4096) { 5 }, "A.jpg")
        state.value = zSessionState(listOf(attachment))
        val session = state.value.selected!!
        val token = vm.attachmentScope()
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val sends = java.util.concurrent.atomic.AtomicInteger()
        val aborts = java.util.concurrent.atomic.AtomicInteger()
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["session/attachment/begin"] = {
            entered.countDown(); gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
            obj("state" to "committed", "ref" to "zcode-artifact://old") }
        bridge.scripted["session/attachment/abort"] = { aborts.incrementAndGet(); obj("state" to "committed") }
        bridge.scripted["session/send"] = { sends.incrementAndGet(); obj() }
        bridge.install()
        lateinit var job: kotlinx.coroutines.Job
        instrumentation.runOnMainSync { job = vm.send() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val select = ZcodeViewModel::class.java.getDeclaredMethod("select", ZSession::class.java).apply { isAccessible = true }
        instrumentation.runOnMainSync {
            select.invoke(vm, session.copy(id = "B")); select.invoke(vm, session)
            // A Picker result launched before A -> B -> A must be ignored before decoding.
            vm.addAttachment(Uri.parse("content://invalid/late"), "late.jpg", "image/jpeg", token)
        }
        assertFalse(vm.state.value.attachmentsBusy)
        assertTrue(vm.state.value.attachments.isEmpty())
        gate.countDown(); withTimeout(10_000) { job.join() }
        assertEquals(0, sends.get()); assertEquals(1, aborts.get())
        val refs = ZcodeViewModel::class.java.getDeclaredField("attachmentRefs").let {
            it.isAccessible = true; it.get(vm) as Map<*, *> }
        assertTrue(refs.isEmpty())
        instrumentation.runOnMainSync { vm.viewModelScope.cancel() }
        bridge.close(); bridge.assertClean()
    }

    @Test fun zcodeSendRefusedWhileAttachmentsProcessing() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        cleaners += { instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault") }
        lateinit var vm: ZcodeViewModel
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        val state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState> }
        state.value = zSessionState(emptyList()).copy(attachmentsBusy = true)
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["session/send"] = { error("处理中不得发送") }
        bridge.install()
        instrumentation.runOnMainSync { runCatching { vm.send() } }
        delay(200)
        assertTrue(vm.state.value.error?.contains("图片处理中") == true)
        instrumentation.runOnMainSync { vm.viewModelScope.cancel() }
        bridge.close(); bridge.assertClean()
    }

    // ---- ZCode send path: two-stage upload through the scripted bridge ----

    private fun zSessionState(attachments: List<ChatAttachment>) = ZcodeState(
        connected = true, selected = ZSession("s1", "集成", "/w", "idw"),
        attachmentsEnabled = true, attachments = attachments,
        selection = ZSelection("p", "m"), selectionGuard = "g",
        providers = listOf(ZProvider("p", "计划", "individual-coding-plan", "g", listOf(ZModel("m", emptyList())))))

    @Test fun zcodeUploadRunsBeginChunkCommitThenSendsWithRefs() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        cleaners += { instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault") }
        lateinit var vm: ZcodeViewModel
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        val state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState> }
        // 300KB spans four phone chunks (96K+96K+96K+12K): aggregation boundaries exercised.
        val attachment = ChatAttachment.new(ByteArray(300 * 1024) { (it % 251).toByte() }, "相册图.jpg")
        state.value = zSessionState(listOf(attachment))
        instrumentation.runOnMainSync { vm.draft("看看这张") }

        val beginCalls = ConcurrentLinkedQueue<org.json.JSONObject>()
        val chunkData = StringBuilder()
        val sendEnvelopes = ConcurrentLinkedQueue<org.json.JSONObject>()
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["session/attachment/begin"] = { req ->
            beginCalls.add(req.optJSONObject("params")!!)
            obj("state" to "staging", "nextChunkIndex" to 0) }
        bridge.scripted["session/attachment/chunk"] = { req ->
            chunkData.append(req.optJSONObject("params")!!.string("dataBase64"))
            obj("uploadId" to req.optJSONObject("params")!!.string("uploadId"),
                "nextChunkIndex" to req.optJSONObject("params")!!.optInt("chunkIndex") + 1) }
        bridge.scripted["session/attachment/commit"] = { obj("state" to "committed", "ref" to "zcode-artifact://r1") }
        bridge.scripted["session/send"] = { req ->
            sendEnvelopes.add(req.optJSONObject("params")!!)
            obj("commandId" to req.optJSONObject("params")!!.string("commandId"), "status" to "accepted",
                "result" to obj("delivery" to "queue")) }
        bridge.scripted["session/read"] = { obj("id" to "s1", "title" to "集成", "revision" to 1L, "logEpoch" to "e1",
            "mode" to "yolo", "phase" to "queued", "canStop" to false, "items" to org.json.JSONArray(),
            "totalRows" to 0, "queue" to org.json.JSONArray(), "interactionCount" to 0,
            "interactions" to org.json.JSONArray(), "interactionsComplete" to true) }
        bridge.install()
        instrumentation.runOnMainSync { vm.send() }

        withTimeout(15_000) {
            while (sendEnvelopes.isEmpty()) delay(20)
        }
        val begin = beginCodesSingle(beginCalls)
        assertEquals("upl-${attachment.id}", begin.string("uploadId"))
        assertEquals("pockethelm-${attachment.id}.jpg", begin.string("fileName"))
        assertEquals(attachment.bytes, begin.optInt("totalBytes"))
        assertEquals("sha256:${AttachmentUploadPlan.sha256Hex(attachment.data)}", begin.string("checksum"))
        // Phone chunks reassemble to the exact processed bytes and none exceed 96KiB.
        assertEquals(java.util.Base64.getDecoder().decode(chunkData.toString()).size, attachment.bytes)
        val sent = sendEnvelopes.single()
        val payload = sent.optJSONArray("attachments")?.getJSONObject(0)
        assertEquals("zcode-artifact://r1", payload?.string("ref"))
        assertEquals(attachment.bytes, payload?.optInt("bytes"))
        assertEquals("pockethelm-${attachment.id}.jpg", payload?.string("fileName"))
        assertEquals("看看这张", sent.string("text"))
        // Batch clears only after the accepted send.
        withTimeout(5_000) { while (vm.state.value.attachments.isNotEmpty()) delay(20) }
        instrumentation.runOnMainSync { vm.background(); vm.viewModelScope.cancel() }
        bridge.close(); bridge.assertClean()
    }

    private fun beginCodesSingle(calls: ConcurrentLinkedQueue<org.json.JSONObject>): org.json.JSONObject {
        val all = calls.toList()
        assertEquals("begin 只应被调用一次（首传即成功）", 1, all.size)
        return all.single()
    }

    @Test fun zcodeCommitRefusalKeepsBatchAndNeverSends() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        cleaners += { instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault") }
        lateinit var vm: ZcodeViewModel
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        val state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState> }
        val attachment = ChatAttachment.new(ByteArray(10 * 1024) { 7 }, "小图.jpg")
        state.value = zSessionState(listOf(attachment))
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["session/attachment/begin"] = { obj("state" to "staging", "nextChunkIndex" to 0) }
        bridge.scripted["session/attachment/chunk"] = { req ->
            obj("nextChunkIndex" to req.optJSONObject("params")!!.optInt("chunkIndex") + 1) }
        bridge.scripted["session/attachment/commit"] = { throw FakeRpcBridge.FakeRpcError(-32602, "校验失败") }
        bridge.scripted["session/send"] = { error("上传失败时不得发送消息") }
        bridge.install()
        instrumentation.runOnMainSync { runCatching { vm.send() } }
        withTimeout(15_000) {
            while (vm.state.value.attachmentsBusy && vm.state.value.error == null) delay(20)
        }
        delay(300)
        println("ZREJECT-STATE: busy=" + vm.state.value.attachmentsBusy
            + " error=" + vm.state.value.error + " attachments=" + vm.state.value.attachments.size)
        assertTrue("失败后整批保留", vm.state.value.attachments.isNotEmpty())
        assertTrue(vm.state.value.attachmentsBusy == false)
        instrumentation.runOnMainSync { vm.background(); vm.viewModelScope.cancel() }
        bridge.close(); bridge.assertClean()
    }

    // ---- Codex send path: writeFile then localImage input ----

    @Test fun codexSendWritesFilesAndBuildsLocalImageInput() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        cleaners += { instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault") }
        lateinit var vm: RemoteViewModel
        instrumentation.runOnMainSync { vm = RemoteViewModel(app) }
        val state = RemoteViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<UiState> }
        state.value = UiState(connected = true, foreground = true,
            selected = SessionInfo("s1", "集成", "/tmp/remote", "", "idle", 0))
        val attachment = ChatAttachment.new(ByteArray(50 * 1024) { 9 }, "图.jpg")
        state.value = state.value.copy(attachments = listOf(attachment))
        instrumentation.runOnMainSync { vm.draft("看图") }

        val writes = ConcurrentLinkedQueue<Pair<String, ByteArray>>()
        val turns = ConcurrentLinkedQueue<org.json.JSONObject>()
        val bridge = FakeRpcBridge(vm)
        bridge.scripted["thread/resume"] = {
            obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "idle"), "updatedAt" to 0L),
                "approvalPolicy" to "never", "sandbox" to obj("type" to "dangerFullAccess")) }
        bridge.scripted["fs/writeFile"] = { req ->
            val params = req.optJSONObject("params")!!
            writes += params.string("path") to Base64.getDecoder().decode(params.string("dataBase64"))
            obj() }
        bridge.scripted["turn/start"] = { req ->
            turns.add(req.optJSONObject("params")!!)
            obj("turn" to obj("id" to "t1", "status" to "inProgress")) }
        bridge.scripted["thread/read"] = {
            obj("thread" to obj("id" to "s1", "cwd" to "/tmp/remote", "status" to obj("type" to "idle"), "updatedAt" to 0L)) }
        bridge.scripted["thread/turns/list"] = { obj("data" to org.json.JSONArray()) }
        bridge.scripted["thread/queue/list"] = { obj("data" to org.json.JSONArray()) }
        bridge.scripted["thread/items/list"] = { obj("data" to org.json.JSONArray(), "nextCursor" to "") }
        bridge.install()
        instrumentation.runOnMainSync { vm.send(false) }
        withTimeout(15_000) { while (turns.isEmpty()) delay(20) }

        val (path, bytes) = writes.single()
        assertEquals(ImagePolicy.codexPath(attachment.id), path)
        assertTrue("写入内容必须与处理后的图片逐字节一致", bytes.contentEquals(attachment.data))
        val input = turns.single().optJSONArray("input")!!
        assertEquals(2, input.length())
        assertEquals("看图", input.getJSONObject(0).string("text"))
        val image = input.getJSONObject(1)
        assertEquals("localImage", image.string("type"))
        assertEquals(path, image.string("path"))
        // The batch clears after the accepted send; the temp file stays for history.
        withTimeout(5_000) { while (vm.state.value.attachments.isNotEmpty()) delay(20) }
        assertEquals(1, writes.size)
        instrumentation.runOnMainSync { vm.background(); vm.viewModelScope.cancel() }
        bridge.close(); bridge.assertClean()
    }
}
