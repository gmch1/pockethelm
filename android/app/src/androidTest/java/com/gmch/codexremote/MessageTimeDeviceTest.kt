package com.gmch.codexremote

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MessageTimeDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val time = Instant.parse("2026-11-05T16:30:00Z").toEpochMilli()
    private fun text(): String {
        val result = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; if (n.isVisibleToUser) result += n.text?.toString().orEmpty(); for (i in 0 until n.childCount) walk(n.getChild(i)) }
        walk(inst.uiAutomation.rootInActiveWindow); return result.joinToString("\n")
    }
    @Test fun sharedCardsShowServerAndLocalTimesAndNeverInventQueueTime() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { PocketHelmTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.25f)) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        SessionRow("时间展示测试", "test", "空闲", time) {}
                        TimelineCard(TimelineItem("u", "userMessage", "这条消息何时发送？", timestampMs = time)) { _, _ -> }
                        TimelineCard(TimelineItem("a", "agentMessage", "消息时间显示在标题行。", timestampMs = time + 60_000), "ZCode") { _, _ -> }
                        OutgoingCard(OutgoingMessage("s", "local", "待确认的提交", OutgoingState.UNCERTAIN, createdAt = time + 120_000)) { _, _ -> }
                        OutgoingCard(OutgoingMessage("s", "queue", "旧排队记录", OutgoingState.QUEUED, createdAt = 0)) { _, _ -> }
                    }
                }
            } } }
            val deadline = System.nanoTime() + 10_000_000_000
            while (!text().contains("提交时间未提供") && System.nanoTime() < deadline) Thread.sleep(30)
            val actual = text()
            assertTrue(actual.contains("更新于 ${messageTime(time)}"))
            assertTrue(actual.contains("你 · ${messageTime(time)}"))
            assertTrue(actual.contains("ZCode · ${messageTime(time + 60_000)}"))
            assertTrue(actual.contains("本机提交 ${messageTime(time + 120_000)}"))
            assertTrue(actual.contains("提交时间未提供"))
            inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(inst.targetContext.cacheDir, "message-time-review.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
    @Test fun codexStreamKeepsOriginalStartTimeThroughCompletion() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var vm: RemoteViewModel
        inst.runOnMainSync { vm = RemoteViewModel(app) }
        fun field(name: String) = RemoteViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field("restoreJob").get(vm) as Job).join()
        try {
            inst.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val state = field("mutable").get(vm) as MutableStateFlow<UiState>
                state.value = state.value.copy(selected = SessionInfo("s", "test", "/test", "", "idle", 0))
                val handle = RemoteViewModel::class.java.getDeclaredMethod("handle", org.json.JSONObject::class.java).apply { isAccessible = true }
                handle.invoke(vm, obj("method" to "item/started", "params" to obj("threadId" to "s", "startedAtMs" to time,
                    "item" to obj("id" to "a", "type" to "agentMessage", "text" to "start"))))
                handle.invoke(vm, obj("method" to "item/completed", "params" to obj("threadId" to "s", "completedAtMs" to time + 60_000,
                    "item" to obj("id" to "a", "type" to "agentMessage", "text" to "done"))))
                @Suppress("UNCHECKED_CAST") val timeline = field("timeline").get(vm) as Map<String, TimelineItem>
                assertEquals(time, timeline.getValue("a").timestampMs)
            }
        } finally {
            inst.runOnMainSync { vm.viewModelScope.cancel() }
            app.noBackupFilesDir.deleteRecursively()
            inst.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }
    @Test fun zcodeRestoresSubmissionTimeAndOldReceiptRemainsUnknown() = runBlocking {
        val app = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var vm: ZcodeViewModel
        inst.runOnMainSync { vm = ZcodeViewModel(app) }
        fun field(name: String) = ZcodeViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        (field("restoreJob").get(vm) as Job).join()
        try {
            inst.runOnMainSync {
                val select = ZcodeViewModel::class.java.getDeclaredMethod("select", ZSession::class.java).apply { isAccessible = true }
                for ((id, stamp) in listOf("new" to time, "old" to 0L)) {
                    val pending = obj("method" to "session/send", "params" to obj("sessionId" to id, "commandId" to id, "text" to "test"))
                    if (stamp > 0) pending.put("submittedAtMs", stamp)
                    field("pending").set(vm, pending)
                    select.invoke(vm, ZSession(id, "test", "/test"))
                    assertEquals(stamp, vm.state.value.outgoing.single().createdAt)
                }
            }
        } finally {
            inst.runOnMainSync { vm.viewModelScope.cancel() }
            app.noBackupFilesDir.deleteRecursively()
            inst.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }
}
