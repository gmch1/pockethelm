package com.gmch.codexremote

import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises creation together with the first send, rather than seeding a stored thread.
 * The scripted server reproduces the real legacy-vs-paginated empty-history contract.
 * The optional loopback fixture runs the same ViewModel and Compose UI against app-server.
 */
@RunWith(AndroidJUnit4::class)
class RemoteNewThreadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestPrefsApp
    private lateinit var vm: RemoteViewModel
    private lateinit var state: MutableStateFlow<UiState>
    private lateinit var bridge: FakeRpcBridge
    private val requests = CopyOnWriteArrayList<JSONObject>()
    private val messages = CopyOnWriteArrayList<JSONObject>()
    private var mode = "legacy"
    private var title = ""
    private var materialized = false

    private fun fresh() {
        app = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(instrumentation.targetContext) }
        instrumentation.runOnMainSync { vm = RemoteViewModel(app) }
        @Suppress("UNCHECKED_CAST")
        state = RemoteViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<UiState>
        state.value = UiState(connected = true, foreground = true)
        bridge = FakeRpcBridge(vm).also { it.install() }
    }

    private fun metadata() = obj("id" to "new-thread", "cwd" to "/tmp/new-thread", "name" to title,
        "historyMode" to mode, "status" to obj("type" to "idle"), "updatedAt" to 1L)
    private fun resumed() = obj("thread" to metadata(), "model" to "test-model", "reasoningEffort" to "low",
        "approvalPolicy" to "never", "sandbox" to obj("type" to "dangerFullAccess"))

    private fun script(persistName: Boolean = true) {
        fun on(method: String, handler: (JSONObject) -> JSONObject) {
            bridge.scripted[method] = { request -> requests.add(request); handler(request.getJSONObject("params")) }
        }
        on("thread/start") { mode = it.string("historyMode", "legacy"); materialized = false; resumed() }
        on("thread/name/set") { title = it.getString("name"); if (persistName) materialized = true; obj() }
        on("thread/list") { obj("data" to JSONArray()) }
        on("thread/read") { obj("thread" to metadata()) }
        on("thread/resume") {
            if (!materialized)
                throw FakeRpcBridge.FakeRpcError(-32600, "no rollout found for thread id new-thread")
            resumed()
        }
        on("thread/turns/list") {
            if (!materialized)
                throw FakeRpcBridge.FakeRpcError(-32600,
                    "thread new-thread is not materialized yet; thread/turns/list is unavailable before first user message")
            obj("data" to JSONArray())
        }
        on("thread/items/list") {
            if (mode != "paginated") throw FakeRpcBridge.FakeRpcError(-32601, "thread/items/list is not supported yet")
            obj("data" to JSONArray(messages.reversed().map { obj("item" to it) }))
        }
        on("thread/queue/list") { obj("data" to JSONArray()) }
        on("turn/start") { params ->
            assertEquals("never", params.getString("approvalPolicy"))
            assertEquals("dangerFullAccess", params.getJSONObject("sandboxPolicy").getString("type"))
            materialized = true
            messages.add(obj("id" to "message-${messages.size}", "type" to "userMessage",
                "clientId" to params.getString("clientUserMessageId"), "content" to params.getJSONArray("input")))
            obj("turn" to obj("id" to "turn-${messages.size}", "status" to "inProgress"))
        }
    }

    private suspend fun settled() = withTimeout(15_000) {
        do { delay(30) } while (state.value.busy)
    }

    private fun calls(method: String) = requests.filter { it.string("method") == method }

    @After fun cleanup() {
        if (::vm.isInitialized) instrumentation.runOnMainSync { vm.disconnect(); vm.viewModelScope.cancel() }
        if (::bridge.isInitialized) { bridge.close(); bridge.assertClean() }
        if (::app.isInitialized) {
            instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
            app.prefsContext.noBackupFilesDir.deleteRecursively()
        }
    }

    @Test fun namedNewThreadCanRefreshEmptyHistoryThenSendAndContinue() = runBlocking {
        fresh(); script()
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "[测试]首次发送") }
        settled()
        assertEquals("[测试]首次发送", state.value.selected?.title)
        val start = calls("thread/start").single().getJSONObject("params")
        assertEquals("paginated", start.getString("historyMode"))
        assertEquals("never", start.getString("approvalPolicy"))
        assertEquals("danger-full-access", start.getString("sandbox"))
        instrumentation.runOnMainSync { vm.retryHistory() }
        settled()
        assertNull(state.value.error); assertNull(state.value.historyError)
        assertFalse(state.value.historyLoading); assertTrue(state.value.items.isEmpty())
        for (text in listOf("首条消息", "续聊消息")) {
            instrumentation.runOnMainSync { vm.draft(text); vm.send(false) }
            settled()
            assertNull(state.value.error); assertNull(state.value.uncertain)
            assertTrue(state.value.outgoing.isEmpty())
            assertEquals(text, state.value.items.last().text)
        }
        assertEquals(1, calls("thread/start").size)
        assertEquals(2, calls("turn/start").size)
        assertEquals(2, calls("turn/start").map { it.getJSONObject("params").getString("clientUserMessageId") }.distinct().size)
    }

    @Test fun unnamedCreationClearsPreviousHistoryAndSendPresentation() = runBlocking {
        fresh(); script()
        state.value = state.value.copy(selected = SessionInfo("old", "旧会话", "/tmp/old", "", "idle", 0),
            historyError = "旧会话加载失败", historyLoading = true, historyCached = true, sending = true,
            outgoing = listOf(OutgoingMessage("old", "old-message", "旧消息", OutgoingState.FAILED)))
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "") }
        settled()
        assertEquals("新会话", state.value.selected?.title)
        assertEquals(RecoveryPhase.Ready, state.value.recoveryPhase)
        assertEquals("新会话", calls("thread/name/set").single().getJSONObject("params").getString("name"))
        assertNull(state.value.historyError); assertFalse(state.value.historyLoading)
        assertFalse(state.value.historyCached); assertFalse(state.value.sending)
        assertTrue(state.value.outgoing.isEmpty())
        // The default title now persists the empty thread before the first input.
        // Refresh and first send must work without creating a replacement thread.
        instrumentation.runOnMainSync { vm.retryHistory() }
        settled()
        assertNull(state.value.error); assertNull(state.value.historyError)
        assertFalse(state.value.historyLoading)
        assertTrue(materialized)
        instrumentation.runOnMainSync { vm.draft("首条消息"); vm.send(false) }
        settled()
        assertNull(state.value.error)
        assertEquals("首条消息", state.value.items.single().text)
    }

    @Test fun emptyThreadSurvivesConnectionReplacementAndReentry() = runBlocking {
        fresh(); script()
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "[测试]重连") }
        settled()
        val selected = state.value.selected!!
        instrumentation.runOnMainSync { vm.disconnect() }
        bridge.close()
        bridge = FakeRpcBridge(vm).also { it.install() }; script()
        state.value = state.value.copy(connected = true)
        instrumentation.runOnMainSync { vm.back(); vm.openThread(selected) }
        settled()
        assertNull(state.value.historyError); assertFalse(state.value.historyLoading)
        instrumentation.runOnMainSync { vm.draft("重连后首条消息"); vm.send(false) }
        settled()
        assertNull(state.value.error)
        assertEquals("重连后首条消息", state.value.items.single().text)
        assertEquals(1, calls("thread/start").size)
    }

    @Test fun defaultTitleSurvivesBackgroundAndConnectionReplacementWithDraft() = runBlocking {
        fresh(); script()
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "  ") }
        settled()
        assertEquals("新会话", state.value.selected?.title)
        assertTrue(materialized)
        instrumentation.runOnMainSync { vm.draft("切后台前尚未发送的草稿"); vm.background() }
        // Model daemon eviction: only persisted threads can be read on the next
        // connection. Old unnamed creation fails this contract with thread not loaded.
        instrumentation.runOnMainSync { vm.disconnect() }
        bridge.close()
        bridge = FakeRpcBridge(vm).also { it.install() }; script()
        bridge.scripted["thread/read"] = {
            if (!materialized) throw FakeRpcBridge.FakeRpcError(-32600, "thread not loaded: new-thread")
            obj("thread" to metadata())
        }
        state.value = state.value.copy(connected = true, saved = true)
        RemoteViewModel::class.java.getDeclaredField("backgroundAt").apply { isAccessible = true }
            .setLong(vm, System.currentTimeMillis() - 6_000)
        val before = calls("thread/items/list").size
        instrumentation.runOnMainSync { vm.foreground() }
        withTimeout(15_000) {
            while (calls("thread/items/list").size == before || state.value.historyLoading) delay(20)
        }
        assertEquals("new-thread", state.value.selected?.id)
        assertEquals("切后台前尚未发送的草稿", state.value.draft)
        assertNull(state.value.error); assertNull(state.value.historyError)
        assertTrue(state.value.items.isEmpty())
        assertEquals(1, calls("thread/start").size)
        assertTrue(calls("turn/start").isEmpty())
    }

    @Test fun legacyLiveUnmaterializedThreadStillRefreshesAndSends() = runBlocking {
        fresh(); script(persistName = false)
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "") }
        settled()
        assertFalse(materialized)
        instrumentation.runOnMainSync { vm.retryHistory() }
        settled()
        assertNull(state.value.error); assertNull(state.value.historyError)
        instrumentation.runOnMainSync { vm.draft("旧版内存空会话首条消息"); vm.send(false) }
        settled()
        assertNull(state.value.error)
        assertEquals("旧版内存空会话首条消息", state.value.items.single().text)
        assertEquals(1, calls("turn/start").size)
    }

    @Test fun defaultTitlePersistenceFailureDoesNotPretendCreationSucceeded() = runBlocking {
        fresh(); script()
        bridge.scripted["thread/name/set"] = { throw FakeRpcBridge.FakeRpcError(-32603, "持久化失败") }
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "") }
        settled()
        assertNull(state.value.selected)
        assertEquals("持久化失败", state.value.error)
        assertTrue(calls("turn/start").isEmpty())
    }

    @Test fun missingRolloutForStoredHistoryIsNotTreatedAsANewThread() = runBlocking {
        fresh(); script()
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "[测试]已有历史") }
        settled()
        bridge.scripted["thread/resume"] = { throw FakeRpcBridge.FakeRpcError(-32600,
            "no rollout found for thread id new-thread") }
        // A valid history response, even an empty one, does not prove the special
        // unmaterialized state. The original rejection must remain authoritative.
        instrumentation.runOnMainSync { vm.draft("不能擅自发送"); vm.send(false) }
        settled()
        assertEquals("no rollout found for thread id new-thread", state.value.error)
        assertEquals("不能擅自发送", state.value.draft)
        assertEquals(OutgoingState.FAILED, state.value.outgoing.single().state)
        assertTrue(calls("turn/start").isEmpty())
    }

    @Test fun anotherThreadsUnmaterializedErrorCannotAuthorizeThisSend() = runBlocking {
        fresh(); script(persistName = false)
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "") }
        settled()
        bridge.scripted["thread/turns/list"] = { throw FakeRpcBridge.FakeRpcError(-32600,
            "thread other-thread is not materialized yet; thread/turns/list is unavailable before first user message") }
        instrumentation.runOnMainSync { vm.draft("保留当前草稿"); vm.send(false) }
        settled()
        assertEquals("no rollout found for thread id new-thread", state.value.error)
        assertEquals("保留当前草稿", state.value.draft)
        assertTrue(calls("turn/start").isEmpty())
    }

    @Test fun activeUnmaterializedThreadDoesNotStartOrQueueAnotherTurn() = runBlocking {
        fresh(); script(persistName = false)
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "") }
        settled()
        bridge.scripted["thread/read"] = { obj("thread" to metadata().put("status", obj("type" to "active"))) }
        instrumentation.runOnMainSync { vm.draft("初始化期间保留"); vm.send(false) }
        settled()
        assertEquals("会话正在初始化，请稍后刷新再发送；草稿已保留", state.value.error)
        assertEquals("初始化期间保留", state.value.draft)
        assertNull(state.value.uncertain)
        assertTrue(calls("turn/start").isEmpty())
        assertTrue(calls("thread/queue/add").isEmpty())
    }

    @Test fun resumeFailureIsStillAuthoritativeAndDoesNotCreateOrSendAgain() = runBlocking {
        fresh(); script()
        instrumentation.runOnMainSync { vm.createThread("/tmp/new-thread", "[测试]拒绝") }
        settled()
        bridge.scripted["thread/resume"] = { throw FakeRpcBridge.FakeRpcError(-32600, "thread not found") }
        instrumentation.runOnMainSync { vm.draft("保留草稿"); vm.send(false) }
        settled()
        assertEquals("thread not found", state.value.error)
        assertEquals("保留草稿", state.value.draft)
        assertEquals(OutgoingState.FAILED, state.value.outgoing.single().state)
        assertNull(state.value.uncertain)
        assertTrue(calls("turn/start").isEmpty())
        assertEquals(1, calls("thread/start").size)
    }

    @Test fun invalidProjectNeverCreatesAThread() = runBlocking {
        fresh(); script()
        instrumentation.runOnMainSync { vm.createThread("relative/project", "[测试]无效项目") }
        settled()
        assertNull(state.value.selected)
        assertEquals("请选择项目绝对路径", state.value.error)
        assertTrue(requests.isEmpty())
    }

    /** Requires a deliberately provisioned, restricted app-server fixture, never credentials. */
    @Test fun realAppServerCreateAndFirstSendUi(): Unit = runBlocking {
        val fixture = File(instrumentation.targetContext.filesDir, "new-thread-live-target.json")
        assumeTrue("Dedicated live new-thread fixture is not installed", fixture.isFile)
        val target = JSONObject(fixture.readText())
        val unnamed = target.optBoolean("unnamed")
        val singleMessage = target.optBoolean("singleMessage")
        fresh(); bridge.close()
        fun attach(): Pair<Socket, RpcClient> {
            val socket = Socket("127.0.0.1", target.getInt("port"))
            val client = RpcClient(socket.getInputStream(), socket.getOutputStream(), {}, {})
            RemoteViewModel::class.java.getDeclaredField("rpc").apply { isAccessible = true }.set(vm, client)
            return socket to client
        }
        var connection = attach()
        try {
            state.value = state.value.copy(projects = listOf(target.getString("cwd")))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { PocketHelmTheme { RemoteApp(vm) } } }
                click("新建会话")
                awaitUi { nodes().count { it.isEditable } == 2 }
                if (!unnamed) setText(nodes().first { it.isEditable }, "[测试]新会话首次发送")
                click("创建会话")
                withTimeout(20_000) { while (state.value.selected == null || state.value.busy) delay(50) }
                val id = state.value.selected!!.id
                instrumentation.runOnMainSync { vm.retryHistory() }; settled()
                assertNull(state.value.error); assertNull(state.value.historyError)
                assertTrue(state.value.items.isEmpty())

                // Naming materializes the empty rollout. The unnamed case must send
                // without naming first, rather than accidentally hiding that regression.
                if (!unnamed) {
                    instrumentation.runOnMainSync { vm.disconnect() }
                    connection.second.close(); connection.first.close()
                    connection = attach()
                    state.value = state.value.copy(connected = true)
                    instrumentation.runOnMainSync { vm.retryHistory() }; settled()
                    assertNull(state.value.error); assertNull(state.value.historyError)
                }
                val markers = listOf("FIRST_SEND_OK", "CONTINUE_OK").take(if (singleMessage) 1 else 2)
                for ((index, marker) in markers.withIndex()) {
                    instrumentation.waitForIdleSync()
                    awaitUi { nodes().any { it.isEditable } }
                    val previousReplies = state.value.items.filter { it.kind == "agentMessage" }.map { it.id }.toSet()
                    val prompt = if (unnamed && index == 0) "123" else "Reply exactly $marker. Do not use tools."
                    // Reentry replaces the Compose editor. Do not write to a detached node
                    // left over from the preceding chat frame, even if its action succeeds.
                    awaitUi {
                        if (state.value.draft != prompt) nodes().firstOrNull { it.isEditable }?.let { setText(it, prompt) }
                        state.value.draft == prompt
                    }
                    click("发送消息")
                    withTimeout(120_000) {
                        while (state.value.selected?.lastTurnStatus != "completed" || state.value.items.none {
                            it.kind == "agentMessage" && it.id !in previousReplies &&
                                (if (unnamed && index == 0) it.text.isNotBlank() else it.text.trim() == marker)
                        }) {
                            delay(700)
                            if (!state.value.busy) instrumentation.runOnMainSync { vm.retryHistory() }
                        }
                    }
                    settled()
                    assertNull(state.value.error); assertNull(state.value.historyError)
                    assertNull(state.value.uncertain)
                    assertEquals(id, state.value.selected!!.id)
                    assertEquals(index + 1, state.value.items.count { it.kind == "userMessage" })
                    assertEquals("gpt-6-luna", state.value.modelSettings?.model)
                    if (unnamed && index == 0) {
                        // Apply the user's test marker only after the unnamed first-send
                        // assertion has passed. The fixture forbids earlier naming.
                        connection.second.call("thread/name/set", obj("threadId" to id, "name" to "[测试]未命名首次发送回归"))
                        instrumentation.runOnMainSync { vm.disconnect() }
                        connection.second.close(); connection.first.close()
                        connection = attach()
                        state.value = state.value.copy(connected = true)
                        instrumentation.runOnMainSync { vm.retryHistory() }; settled()
                        assertNull(state.value.error); assertNull(state.value.historyError)
                        assertEquals("123", state.value.items.single { it.kind == "userMessage" }.text)
                    }
                    // Reenter the stored thread, then continue in the same native session.
                    if (index == 0) {
                        val session = state.value.selected!!
                        instrumentation.runOnMainSync { vm.back(); vm.openThread(session) }; settled()
                    }
                }
                awaitUi { nodes().any { it.text?.toString() == if (unnamed) "123" else markers.last() } }
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    File(instrumentation.targetContext.cacheDir, "new-thread-success.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
        } finally { connection.second.close(); connection.first.close() }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) result.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun awaitUi(test: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!test() && System.currentTimeMillis() < until) Thread.sleep(100)
        val passed = test()
        if (!passed) {
            val dir = instrumentation.targetContext.cacheDir
            File(dir, "new-thread-ui-failure.txt").writeText(
                "draft=${state.value.draft}, busy=${state.value.busy}, sending=${state.value.sending}, uncertain=${state.value.uncertain != null}\n" +
                    nodes().joinToString("\n") { "text=${it.text}, description=${it.contentDescription}, editable=${it.isEditable}, enabled=${it.isEnabled}, clickable=${it.isClickable}" })
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try { File(dir, "new-thread-ui-failure.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { screenshot.recycle() }
        }
        assertTrue("UI condition timed out", passed)
    }
    private fun click(label: String) {
        fun button(): AccessibilityNodeInfo? {
            var n = nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            while (n != null && !n.isClickable && n.parent != null) n = n.parent
            return n?.takeIf { it.isClickable && it.isEnabled }
        }
        awaitUi { button() != null }
        assertTrue(button()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
}
