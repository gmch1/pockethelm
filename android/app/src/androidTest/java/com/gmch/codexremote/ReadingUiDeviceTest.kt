package com.gmch.codexremote

import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose rendering and accessibility actions on the device, with synthetic history. */
@RunWith(AndroidJUnit4::class)
class ReadingUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            found.add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }
    private fun matching(text: String) = nodes().filter { it.isVisibleToUser && (it.text?.toString()?.contains(text) == true || it.contentDescription?.toString() == text) }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        assertTrue("Expected UI state was not reached", predicate())
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = matching(text).first()
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("No clickable node for $text", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun tabSelected(label: String): Boolean {
        var node: AccessibilityNodeInfo? = matching(label).first { it.text?.toString() == label }
        // Android exposes an already-selected Tab as selected but not clickable.
        while (node != null) {
            if (node.isSelected) return true
            node = node.parent
        }
        return false
    }
    private fun touch(node: AccessibilityNodeInfo) {
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        assertFalse("Touch target must be visible", rect.isEmpty)
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    @Test fun deliveryTabsKeepKeyboardFocusSelectionAndDraftWhileTyping() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val inserted = java.util.concurrent.atomic.AtomicBoolean(false)
            val sends = java.util.concurrent.atomic.AtomicInteger(0)
            scenario.onActivity { activity -> activity.setContent {
                PocketHelmTheme {
                    var draft by remember { mutableStateOf("任务abcdef") }
                    var insert by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        Spacer(Modifier.weight(1f))
                        ChatComposer(draft, { draft = it }, false, true, insert,
                            { insert = it; inserted.set(it) }, { sends.incrementAndGet() })
                    }
                }
            } }
            fun editor() = nodes().first { it.isVisibleToUser && it.isEditable }
            fun keyboardVisible(): Boolean {
                var visible = false
                scenario.onActivity { activity -> visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                return visible
            }
            await { nodes().any { it.isEditable && it.isVisibleToUser } }
            touch(editor())
            await { keyboardVisible() && editor().isFocused }
            val selection = android.os.Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 4)
            }
            assertTrue(editor().performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection))
            await { editor().textSelectionStart == 2 && editor().textSelectionEnd == 4 }
            val before = android.graphics.Rect().also { editor().getBoundsInScreen(it) }
            for (label in listOf("插入", "排队", "插入", "排队")) {
                var target: AccessibilityNodeInfo? = matching(label).first { it.text?.toString() == label }
                while (target != null && !target.isClickable) target = target.parent
                touch(target!!)
                await { inserted.get() == (label == "插入") && tabSelected(label) }
                // Observe the animation interval as well as the final state; a hide/show flash fails.
                repeat(6) {
                    assertTrue("Keyboard must stay open", keyboardVisible())
                    val field = editor()
                    assertTrue("Editor must keep input focus", field.isFocused)
                    assertEquals("任务abcdef", field.text.toString())
                    assertEquals(2, field.textSelectionStart); assertEquals(4, field.textSelectionEnd)
                    assertEquals(before, android.graphics.Rect().also { field.getBoundsInScreen(it) })
                    Thread.sleep(60)
                }
            }
            instrumentation.sendStringSync("X")
            await { editor().text?.toString() == "任务Xcdef" }
            assertEquals(0, sends.get())
            assertTrue(keyboardVisible())
        }
    }

    @Test fun processDetailsHiddenUntilOpenedAndFailuresRemainVisible() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val process = listOf(TimelineItem("r", "reasoning", "测试思考摘要"),
                TimelineItem("c", "commandExecution", "$ printf fixture\nfixture output", "completed"),
                TimelineItem("f", "fileChange", "fixture.patch", "failed"))
            scenario.onActivity { activity -> activity.setContent {
                PocketHelmTheme {
                    var details by remember { mutableStateOf("") }
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        TimelineCard(TimelineItem("u", "userMessage", "用户需求保留")) { _, _ -> }
                        ActivitySummary(process) { _, text -> details = text }
                        TimelineCard(TimelineItem("a", "agentMessage", "正文结论保留")) { _, _ -> }
                        if (details.isNotEmpty()) Text("详情：$details")
                    }
                }
            } }
            await { matching("正文结论保留").isNotEmpty() }
            assertTrue(matching("有失败").isNotEmpty())
            assertTrue(matching("printf fixture").isEmpty())
            click("工作过程")
            // A short landscape sheet may show only the first (reasoning) entry.
            // Exercise scrolling instead of assuming every tool fits in the first viewport.
            await { matching("printf fixture").isNotEmpty() || nodes().any { it.isScrollable } }
            if (matching("printf fixture").isEmpty()) {
                assertTrue(nodes().first { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            }
            await { matching("printf fixture").isNotEmpty() }
            click("printf fixture")
            await { matching("详情：").isNotEmpty() }
            assertTrue(matching("fixture output").isNotEmpty())
        }
    }

    @Test fun foldedTimelineOpensAtTailAndStickyQuestionJumpsBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val history = listOf(TimelineItem("u", "userMessage", "定位用户问题")) +
                (1..30).map { TimelineItem("t$it", "mcpToolCall", "hidden command $it", "completed") } +
                TimelineItem("a", "agentMessage", (1..40).joinToString("\n") { "这是第 $it 行回答内容" })
            scenario.onActivity { activity -> activity.setContent {
                PocketHelmTheme { ZcodeTimeline(ZcodeState(items = history), Modifier.fillMaxSize().safeDrawingPadding(), {}, {}) { _, _ -> } }
            } }
            await { matching("定位用户问题").size == 1 && matching("工作过程").isEmpty() }
            click("定位用户问题")
            await { matching("定位用户问题").size >= 2 && matching("工作过程").isNotEmpty() }
            assertTrue(matching("30 项操作").isNotEmpty())
            click("回到最新")
            await { matching("定位用户问题").size == 1 && matching("工作过程").isEmpty() }
        }
    }
    @Test fun enginesShareChatGeometryAndPreserveIndependentDraftsAndDelivery() {
        val app = TestPrefsApp(java.util.UUID.randomUUID().toString()).apply { attach(instrumentation.targetContext) }
        val profile = ConnectionProfile(useJump = false, host = "example.invalid", user = "test", root = "/work",
            useDeviceKey = true, configVersion = 2, environmentId = app.namespace)
        Vault(app).put("profile", profile.json().toString())
        lateinit var c: RemoteViewModel
        lateinit var z: ZcodeViewModel
        fun field(vm: Any, name: String) = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }
        instrumentation.runOnMainSync { c = RemoteViewModel(app); z = ZcodeViewModel(app) }
        kotlinx.coroutines.runBlocking {
            (field(c, "restoreJob").get(c) as kotlinx.coroutines.Job).join()
            (field(z, "restoreJob").get(z) as kotlinx.coroutines.Job).join()
        }
        val history = listOf(TimelineItem("u", "userMessage", "统一布局验证"), TimelineItem("a", "agentMessage", "回答内容"))
        val bridge = FakeRpcBridge(z).also { it.install() }
        bridge.scripted["session/read"] = { obj("phase" to "idle", "items" to org.json.JSONArray().put(
            obj("id" to "u", "kind" to "userInput", "text" to "统一布局验证")).put(
            obj("id" to "a", "kind" to "assistantText", "text" to "回答内容"))) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    (field(c, "mutable").get(c) as kotlinx.coroutines.flow.MutableStateFlow<UiState>).value = UiState(
                        profile = profile, saved = false, connected = true, foreground = true,
                        selected = SessionInfo("c", "测试会话", "/work", "", "idle", 0), items = history, draft = "Codex草稿")
                    @Suppress("UNCHECKED_CAST")
                    (field(z, "mutable").get(z) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState>).value = ZcodeState(
                        connected = true, selected = ZSession("z", "测试会话", "/work"), items = history, draft = "ZCode草稿",
                        phase = "idle", selection = ZSelection("provider", "model", "max"))
                    field(z, "historySyncedAt").set(z, System.currentTimeMillis())
                    activity.setContent { PocketHelmTheme { EngineApp(c, z) } }
                }
                fun bounds(description: String): android.graphics.Rect {
                    val node = nodes().first { it.isVisibleToUser && it.contentDescription?.toString() == description }
                    return android.graphics.Rect().also { node.getBoundsInScreen(it) }
                }
                await { matching("Codex草稿").isNotEmpty() }
                val send = bounds("发送消息"); val back = bounds("返回会话"); val question = bounds("选择要跳转的消息")
                click("ZCode")
                await { matching("ZCode草稿").isNotEmpty() }
                assertEquals(send, bounds("发送消息")); assertEquals(back, bounds("返回会话"))
                assertEquals(question, bounds("选择要跳转的消息"))
                click("插入")
                await { z.state.value.delivery == "guide" }
                await { tabSelected("插入") }; assertFalse(tabSelected("排队"))
                click("Codex")
                await { matching("Codex草稿").isNotEmpty() }
                await { tabSelected("排队") }; assertFalse(tabSelected("插入"))
                click("ZCode")
                await { matching("ZCode草稿").isNotEmpty() }
                await { tabSelected("插入") }; assertFalse(tabSelected("排队"))
                assertFalse(z.state.value.busy)
                // Shared connection UI must act on the selected engine, not the other channel.
                scenario.onActivity {
                    @Suppress("UNCHECKED_CAST")
                    (field(z, "mutable").get(z) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState>).value =
                        z.state.value.copy(selected = null)
                }
                await { matching("新建会话").isNotEmpty() }
                click("连接")
                await { matching("ZCode · 已连接").isNotEmpty() }
                click("断开连接")
                await { matching("ZCode · 未连接").isNotEmpty() }
                assertFalse(z.state.value.connected); assertTrue(c.state.value.connected)
            }
        } finally {
            instrumentation.runOnMainSync { c.viewModelScope.cancel(); z.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean()
            app.noBackupFilesDir.deleteRecursively()
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }

    @Test fun zcodeTaskPageCreatesIntoChatUsingCompactAccessibleEntry() {
        val app = TestPrefsApp(java.util.UUID.randomUUID().toString()).apply { attach(instrumentation.targetContext) }
        lateinit var c: RemoteViewModel
        lateinit var z: ZcodeViewModel
        fun field(vm: Any, name: String) = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }
        instrumentation.runOnMainSync { c = RemoteViewModel(app); z = ZcodeViewModel(app) }
        kotlinx.coroutines.runBlocking {
            (field(c, "restoreJob").get(c) as kotlinx.coroutines.Job).join()
            (field(z, "restoreJob").get(z) as kotlinx.coroutines.Job).join()
        }
        val creates = java.util.concurrent.atomic.AtomicInteger()
        val bridge = FakeRpcBridge(z).also { it.install() }
        bridge.scripted["session/create"] = { creates.incrementAndGet(); obj("status" to "accepted", "result" to obj("sessionId" to "new")) }
        bridge.scripted["session/read"] = { obj("title" to "新建测试会话", "phase" to "draft", "items" to org.json.JSONArray()) }
        bridge.scripted["projects"] = { obj("projects" to org.json.JSONArray(), "sessions" to org.json.JSONArray()
            .put(obj("taskId" to "new", "title" to "新建测试会话", "workspacePath" to "/work"))) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    (field(c, "mutable").get(c) as kotlinx.coroutines.flow.MutableStateFlow<UiState>).value = UiState(engine = "zcode", saved = false)
                    @Suppress("UNCHECKED_CAST")
                    (field(z, "mutable").get(z) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState>).value = ZcodeState(
                        connected = true, sessionsFresh = true, projects = listOf(ZProject("p", "合成项目", listOf("/work"))),
                        providers = listOf(ZProvider("p", "计划", "subscription", "guard", listOf(ZModel("m", emptyList())))),
                        selection = ZSelection("p", "m"), selectionGuard = "guard")
                    field(z, "listSyncedAt").set(z, System.currentTimeMillis())
                    activity.setContent { PocketHelmTheme { EngineApp(c, z) } }
                }
                await { nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == "新建会话" } }
                // Icons remain accessible, without adding visible bottom-nav text.
                assertFalse(nodes().any { it.isVisibleToUser && it.text?.toString() == "任务" })
                click("任务")
                await { matching("任务中心").isNotEmpty() }
                click("新建会话")
                await { matching("合成项目").isNotEmpty() }
                click("合成项目")
                await { z.state.value.selected?.id == "new" && matching("发送消息").isNotEmpty() }
                assertEquals(1, creates.get())
                assertFalse(matching("任务中心").isNotEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { c.viewModelScope.cancel(); z.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); app.noBackupFilesDir.deleteRecursively()
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }

}
