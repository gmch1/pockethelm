package com.gmch.codexremote

import android.view.accessibility.AccessibilityNodeInfo
import com.gmch.codexremote.app.LocalEngineDescriptors
import com.gmch.codexremote.engines.contract.EngineDescriptor
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
        await { matching(text).isNotEmpty() }
        var node: AccessibilityNodeInfo? = matching(text).first()
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("No clickable node for $text", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun failedBubbleStaysBeforeLaterReplyAndRemovalKeepsUncertain() {
        val failed = OutgoingMessage("s", "failed", "旧失败文本", OutgoingState.FAILED,
            precedingItemIds = emptyList())
        val uncertain = OutgoingMessage("s", "unknown", "待确认文本", OutgoingState.UNCERTAIN)
        val outgoing = mutableStateOf(listOf(failed, uncertain))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                PocketHelmTheme {
                    ChatTimeline("s", "ZCode", listOf(
                        TimelineItem("user", "userMessage", "后续成功消息"),
                        TimelineItem("answer", "agentMessage", "后续回复")), outgoing.value, emptyList(),
                        false, false, null, true, false, Modifier.fillMaxSize(), {}, {}, { _, _ -> },
                        dismissFailed = { m -> outgoing.value = outgoing.value.filterNot { it.clientId == m.clientId } })
                }
            } }
            await { matching("旧失败文本").isNotEmpty() && matching("后续回复").isNotEmpty() }
            fun top(text: String): Int = android.graphics.Rect().also { matching(text).first().getBoundsInScreen(it) }.top
            assertTrue(top("旧失败文本") < top("后续成功消息"))
            assertTrue(top("后续成功消息") < top("后续回复"))
            click("移除失败记录")
            await { matching("旧失败文本").isEmpty() }
            assertTrue(matching("后续回复").isNotEmpty())
            assertTrue(matching("待确认文本").isNotEmpty())
            assertTrue(matching("移除失败记录").isEmpty())
        }
    }

    private fun captureList(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = java.io.File(instrumentation.targetContext.cacheDir, "list-review").apply { mkdirs() }
        try { java.io.File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
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

    private fun deliveryMode(): String? {
        // Compose exposes the content description as a virtual child; the button
        // ancestor owns its state description and click action.
        var node = matching("发送方式").firstOrNull()
        while (node != null) {
            node.stateDescription?.toString()?.let { return it }
            node = node.parent
        }
        return null
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

    @Test fun deliveryToggleKeepsKeyboardFocusSelectionAndDraftWhileTyping() {
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
            captureList("composer-queue")
            for (label in listOf("插入", "排队", "插入", "排队")) {
                var target: AccessibilityNodeInfo? = matching("发送方式").first()
                while (target != null && !target.isClickable) target = target.parent
                touch(target!!)
                await { inserted.get() == (label == "插入") && deliveryMode() == label }
                assertTrue(matching(if (label == "插入") "排队" else "插入").isEmpty())
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
                if (label == "插入") captureList("composer-insert")
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
                PocketHelmTheme { ZcodeTimeline(ZcodeState(items = history), androidx.lifecycle.ViewModelProvider(activity)[ZcodeViewModel::class.java], Modifier.fillMaxSize().safeDrawingPadding(), {}, {}) { _, _ -> } }
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
                fun assertChatToolbar() {
                    val bottom = bounds("返回会话").bottom
                    assertFalse(nodes().any { node -> node.isVisibleToUser &&
                        node.text?.toString() in listOf("Codex", "ZCode") &&
                        android.graphics.Rect().also { node.getBoundsInScreen(it) }.top < bottom })
                    assertTrue(matching("刷新").isNotEmpty())
                }
                assertChatToolbar()
                assertTrue(matching("重命名").isNotEmpty())
                captureList("codex-chat-toolbar")
                // Chat deliberately has no engine tabs. Switch the fixture's engine
                // directly here to compare retained chat states; list tabs are tested separately.
                scenario.onActivity { c.engine("zcode") }
                await { matching("ZCode草稿").isNotEmpty() }
                assertChatToolbar()
                captureList("zcode-chat-toolbar")
                assertEquals(send, bounds("发送消息")); assertEquals(back, bounds("返回会话"))
                assertEquals(question, bounds("选择要跳转的消息"))
                click("发送方式")
                await { z.state.value.delivery == "guide" }
                await { deliveryMode() == "插入" }
                assertTrue(matching("排队").isEmpty())
                scenario.onActivity { c.engine("codex") }
                await { matching("Codex草稿").isNotEmpty() }
                await { deliveryMode() == "排队" }
                assertTrue(matching("插入").isEmpty())
                scenario.onActivity { c.engine("zcode") }
                await { matching("ZCode草稿").isNotEmpty() }
                await { deliveryMode() == "插入" }
                assertTrue(matching("排队").isEmpty())
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
                click("Codex")
                await { matching("返回会话").isNotEmpty() }
                click("返回会话")
                await { matching("新建会话").isNotEmpty() }
                assertTrue(matching("任务").isEmpty())
                click("连接")
                await {
                    var node = nodes().firstOrNull { it.contentDescription?.toString() == "连接" }
                    var selected = false
                    while (node != null) { selected = selected || node.isSelected; node = node.parent }
                    selected
                }
            }
        } finally {
            instrumentation.runOnMainSync { c.viewModelScope.cancel(); z.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean()
            app.noBackupFilesDir.deleteRecursively()
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }

    @Test fun startPlanQuotaLoadsAndClearsWhenSwitchingPlan() {
        val app = TestPrefsApp(java.util.UUID.randomUUID().toString()).apply { attach(instrumentation.targetContext) }
        lateinit var c: RemoteViewModel
        lateinit var z: ZcodeViewModel
        fun field(vm: Any, name: String) = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }
        instrumentation.runOnMainSync { c = RemoteViewModel(app); z = ZcodeViewModel(app) }
        kotlinx.coroutines.runBlocking {
            (field(c, "restoreJob").get(c) as kotlinx.coroutines.Job).join()
            (field(z, "restoreJob").get(z) as kotlinx.coroutines.Job).join()
        }
        val bridge = FakeRpcBridge(z).also { it.install() }
        bridge.scripted["session/read"] = { obj("title" to "额度显示验证", "phase" to "idle", "items" to org.json.JSONArray()) }
        bridge.scripted["quota"] = { request ->
            if (request.getJSONObject("params").getJSONObject("selection").getString("providerId") == "free")
                org.json.JSONObject("""{"planKind":"start-plan","limits":[{"number":500000000,"unit":500000000,
                    "remaining":225000000,"currentValue":275000000,"unitType":"token","period":"one_time",
                    "nextResetTime":1793581200000,"usageDetails":[{"modelCode":"glm-flash","displayName":"GLM Flash"}]}]}""")
            else org.json.JSONObject("""{"planKind":"individual-coding-plan","limits":[{"unit":3,"number":5,"percentage":30}]}""")
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    (field(c, "mutable").get(c) as kotlinx.coroutines.flow.MutableStateFlow<UiState>).value = UiState(engine = "zcode", saved = false)
                    @Suppress("UNCHECKED_CAST")
                    (field(z, "mutable").get(z) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState>).value = ZcodeState(
                        connected = true, selected = ZSession("z", "额度显示验证", "/work"), phase = "idle",
                        providers = listOf(ZProvider("free", "免费计划", "start-plan", "free-guard", listOf(ZModel("glm-flash", emptyList()))),
                            ZProvider("paid", "付费计划", "individual-coding-plan", "paid-guard", listOf(ZModel("glm", emptyList())))),
                        selection = ZSelection("free", "glm-flash"), selectionGuard = "free-guard")
                    field(z, "historySyncedAt").set(z, System.currentTimeMillis())
                    activity.setContent { PocketHelmTheme { EngineApp(c, z) } }
                }
                click("额度 ▾")
                await { matching("剩余 225,000,000 Tokens · 45%").isNotEmpty() }
                assertTrue(matching("已用 275,000,000 Tokens / 总量 500,000,000 Tokens").isNotEmpty())
                assertTrue(matching("一次性额度").isNotEmpty())
                assertTrue(matching("到期").isNotEmpty())
                assertEquals("剩余 45%", z.state.value.quotaSummary)
                captureList("start-plan-quota")
                click("Start Plan")
                click("付费计划")
                await { matching("点击读取额度").isNotEmpty() }
                assertTrue(matching("225,000,000").isEmpty())
                assertEquals("额度 ▾", z.state.value.quotaSummary)
                click("点击读取额度")
                await { matching("5 小时额度").isNotEmpty() && matching("剩余 70%").isNotEmpty() }
                assertEquals("剩余 70%", z.state.value.quotaSummary)
            }
        } finally {
            instrumentation.runOnMainSync { c.viewModelScope.cancel(); z.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); app.noBackupFilesDir.deleteRecursively()
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }

    @Test fun mergedSessionPageCreatesAndPreservesProjectFilter() {
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
        bridge.scripted["projects"] = { obj("projects" to org.json.JSONArray()
            .put(obj("id" to "p", "name" to "合成项目", "roots" to org.json.JSONArray().put("/work")))
            .put(obj("id" to "q", "name" to "其他项目", "roots" to org.json.JSONArray().put("/other"))),
            "sessions" to org.json.JSONArray()
                .put(obj("taskId" to "new", "title" to "新建测试会话", "workspacePath" to "/work", "projectId" to "p"))
                .put(obj("taskId" to "old", "title" to "已有会话", "workspacePath" to "/work", "projectId" to "p"))
                .put(obj("taskId" to "other", "title" to "其他会话", "workspacePath" to "/other", "projectId" to "q"))) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    (field(c, "mutable").get(c) as kotlinx.coroutines.flow.MutableStateFlow<UiState>).value = UiState(engine = "zcode", saved = false)
                    @Suppress("UNCHECKED_CAST")
                    (field(z, "mutable").get(z) as kotlinx.coroutines.flow.MutableStateFlow<ZcodeState>).value = ZcodeState(
                        connected = true, recoveryPhase = RecoveryPhase.Ready, sessionsFresh = true,
                        projects = listOf(ZProject("p", "合成项目", listOf("/work")), ZProject("q", "其他项目", listOf("/other"))),
                        sessions = listOf(ZSession("old", "已有会话", "/work", project = "p"),
                            ZSession("other", "其他会话", "/other", project = "q")),
                        providers = listOf(ZProvider("p", "计划", "subscription", "guard", listOf(ZModel("m", emptyList())))),
                        selection = ZSelection("p", "m"), selectionGuard = "guard")
                    field(z, "listSyncedAt").set(z, System.currentTimeMillis())
                    activity.setContent { PocketHelmTheme { EngineApp(c, z) } }
                }
                await { nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == "新建会话" } }
                // Icons remain accessible, without adding visible bottom-nav text.
                assertFalse(nodes().any { it.isVisibleToUser && it.text?.toString() == "会话" })
                assertTrue(matching("任务").isEmpty())
                assertTrue(nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == "会话" })
                assertTrue(matching("连接").isNotEmpty())
                await { matching("2 个会话").isNotEmpty() }
                captureList("merged-sessions")
                click("筛选项目")
                await { matching("关闭项目列表").isNotEmpty() }
                click("合成项目")
                await { matching("1 个会话").isNotEmpty() }
                click("新建会话")
                await { matching("取消").isNotEmpty() && matching("新会话默认完全访问").isNotEmpty() }
                click("合成项目")
                await { z.state.value.selected?.id == "new" && matching("发送消息").isNotEmpty() }
                assertEquals(1, creates.get())
                // The chat page replaces the two-row list header entirely.
                assertFalse(matching("个会话").isNotEmpty())
                click("返回会话")
                // One list owns the filter across creation, chat and connection navigation.
                await { matching("2 个会话").isNotEmpty() }
                assertTrue(matching("其他会话").isEmpty())
                click("连接")
                await { matching("ZCode · 已连接").isNotEmpty() }
                click("会话")
                await { matching("2 个会话").isNotEmpty() }
            }
        } finally {
            instrumentation.runOnMainSync { c.viewModelScope.cancel(); z.viewModelScope.cancel() }
            bridge.close(); bridge.assertClean(); app.noBackupFilesDir.deleteRecursively()
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
    }

    @Test fun listMenuSortAndSearchReturnToTopAndProjectSheetScrolls() {
        val added = java.util.concurrent.atomic.AtomicBoolean(false)
        val data = (0 until 35).map { i -> WorkspaceSession("s$i", "测试会话%02d".format(i),
            "/test/project%02d".format(i), "项目%02d".format(i), if (i % 2 == 0) "执行中" else "已完成", 1000L + i) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                PocketHelmTheme {
                    var engine by remember { mutableStateOf("codex") }
                    CompositionLocalProvider(LocalEngineDescriptors provides listOf(EngineDescriptor("codex", "Codex"), EngineDescriptor("zcode", "ZCode"))) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        WorkspaceListPage(engine, { require(it in setOf("codex", "zcode")); engine = it },
                            data, connected = true, busy = false, canCreate = true, canOpen = true,
                            open = {}, create = {}, reconnect = {}, refresh = {}, addProject = { added.set(true) },
                            error = null, dismissError = {}, detail = { _, _ -> }, selectTab = {})
                    }
                    }
                }
            } }
            await { matching("测试会话34").isNotEmpty() }
            captureList("list")
            assertTrue(tabSelected("Codex")); assertFalse(tabSelected("ZCode"))
            click("ZCode")
            await { tabSelected("ZCode") }; assertFalse(tabSelected("Codex"))
            fun scroll(): Boolean {
                val scroller = nodes().lastOrNull { node -> node.isVisibleToUser && node.isScrollable &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                val moved = scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                instrumentation.waitForIdleSync()
                return moved
            }
            repeat(3) { assertTrue(scroll()); Thread.sleep(350) }
            await { matching("测试会话34").isEmpty() }
            click("列表菜单"); await { matching("较早活动优先").isNotEmpty() }; click("较早活动优先")
            await { matching("测试会话00").isNotEmpty() }
            repeat(2) { assertTrue(scroll()); Thread.sleep(350) }
            click("搜索会话")
            await { nodes().any { it.isEditable && it.isFocused } }
            val editor = nodes().first { it.isEditable && it.isVisibleToUser }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "会话34")
            }))
            assertTrue(editor.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            await { matching("1 个会话").isNotEmpty() && matching("测试会话34").isNotEmpty() }
            captureList("search")
            click("退出搜索")
            await { matching("35 个会话").isNotEmpty() }
            click("筛选项目")
            await { matching("关闭项目列表").isNotEmpty() && matching("添加项目").isNotEmpty() }
            Thread.sleep(350) // Capture the settled sheet, not its entrance animation.
            captureList("projects")
            // Long lists must scroll while the add action stays reachable.
            assertTrue(matching("添加项目").isNotEmpty())
            for (attempt in 0 until 20) {
                // Accessibility removes the forward action at the boundary before
                // its next tree snapshot exposes the final row. Verify that row below.
                if (matching("项目34").isNotEmpty() || !scroll()) break
                Thread.sleep(350)
            }
            await { matching("项目34").isNotEmpty() }
            captureList("projects-end")
            click("添加项目")
            await { added.get() && matching("关闭项目列表").isEmpty() }
        }
    }

}
