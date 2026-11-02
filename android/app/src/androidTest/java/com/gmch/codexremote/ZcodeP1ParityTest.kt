package com.gmch.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P1 parity instrumentation (task 2026-11-02 zcode-p1-parity): the turn badge pipeline,
 * file-change detail query, CAS queue operations against the user-visible snapshot, and
 * sessions-index list metadata/unread — all through the real RpcClient over a scripted
 * bridge. Capability gating mirrors an older bridge (0.4.15) staying functional.
 */
@RunWith(AndroidJUnit4::class)
class ZcodeP1ParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: TestPrefsApp
    private lateinit var vm: ZcodeViewModel
    private lateinit var state: MutableStateFlow<ZcodeState>
    private lateinit var bridge: FakeRpcBridge

    private val session = ZSession("s1", "会话", "/tmp/zcode-p1", project = "p1")

    private fun freshViewModel() {
        app = TestPrefsApp(UUID.randomUUID().toString())
        app.attach(instrumentation.targetContext)
        instrumentation.runOnMainSync { vm = ZcodeViewModel(app) }
        state = ZcodeViewModel::class.java.getDeclaredField("mutable").let {
            it.isAccessible = true; it.get(vm) as MutableStateFlow<ZcodeState>
        }
        state.value = ZcodeState(connected = true, queueOpsEnabled = true, diffEnabled = true, indexMetaEnabled = true)
        bridge = FakeRpcBridge(vm)
        bridge.scripted["projects"] = {
            obj("projects" to JSONArray(), "sessions" to JSONArray().put(obj(
                "taskId" to "s1", "title" to "会话", "workspacePath" to "/tmp/zcode-p1",
                "workspaceIdentity" to "", "projectId" to "p1", "updatedAt" to 0, "status" to "")))
        }
        // The listMeta test enables the live index; its teardown then pauses it.
        bridge.scripted["sessions/pause"] = { obj() }
        bridge.install()
    }

    private suspend fun await(timeoutMs: Long = 15_000, test: (ZcodeState) -> Boolean): Boolean =
        withTimeout(timeoutMs) { while (!test(vm.state.value)) delay(20); true }

    private fun scriptRead(turn: JSONObject? = null, queue: JSONArray = JSONArray(), revision: Long = 5) {
        bridge.scripted["session/read"] = {
            obj("items" to JSONArray(), "logEpoch" to "e1", "revision" to revision, "title" to "会话",
                "phase" to "idle", "queue" to queue, "mode" to "yolo", "hasMore" to false,
                "turn" to (turn ?: JSONObject.NULL))
        }
    }

    private fun selectReflection(target: ZSession) {
        val method = ZcodeViewModel::class.java.getDeclaredMethod("select", ZSession::class.java)
        method.isAccessible = true
        instrumentation.runOnMainSync { method.invoke(vm, target) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun seenMap(): LinkedHashMap<String, Long> =
        ZcodeViewModel::class.java.getDeclaredField("seenActivity").let {
            it.isAccessible = true; it.get(vm) as LinkedHashMap<String, Long>
        }

    @After
    fun tearDown() {
        try {
            if (::vm.isInitialized) instrumentation.runOnMainSync {
                vm.leave()
                vm.viewModelScope.cancel()
            }
        } finally {
            if (::bridge.isInitialized) bridge.close()
            if (::app.isInitialized)
                instrumentation.targetContext.deleteSharedPreferences("zcode_load_test_${app.namespace}-vault")
        }
        if (::bridge.isInitialized) bridge.assertClean()
    }

    @Test fun turnBadgeSummaryPublishesFromAuthoritativeSnapshot() = runBlocking {
        freshViewModel()
        scriptRead(turn = obj("rowId" to 7L, "entityId" to "turn-7", "state" to "running",
            "activeMs" to 112000, "fileChanges" to obj("additions" to 318, "deletions" to 0, "files" to 2, "state" to "active")))
        selectReflection(session)
        instrumentation.runOnMainSync { vm.retryLoad() }
        assertTrue(await { it.turn != null && it.selected?.id == "s1" })
        val turn = vm.state.value.turn!!
        assertEquals(7L, turn.rowId); assertEquals("turn-7", turn.entityId)
        assertEquals("running", turn.state)
        val summary = turn.summary()!!
        assertEquals("更改 +318 −0 · 2 文件", turnDiffBadgeLabel(summary))
        assertTrue(summary.running); assertEquals(112000L, summary.activeMs)
        // A snapshot without a turn header clears the badge state rather than leaving it stale.
        scriptRead(turn = null)
        instrumentation.runOnMainSync { vm.retryLoad() }
        assertTrue(await { it.turn == null })
    }

    @Test fun diffDetailQueriesRowTargetWithSnapshotVersion() = runBlocking {
        freshViewModel()
        scriptRead(turn = obj("rowId" to 7L, "entityId" to "turn-7", "state" to "completedSuccess",
            "fileChanges" to obj("additions" to 12, "deletions" to 4, "files" to 1)))
        selectReflection(session)
        instrumentation.runOnMainSync { vm.retryLoad() }
        assertTrue(await { it.turn != null })
        val requests = CopyOnWriteArrayList<JSONObject>()
        bridge.scripted["session/diff"] = { req ->
            requests.add(req.optJSONObject("params") ?: obj())
            obj("files" to 1, "additions" to 12, "deletions" to 4, "state" to "active",
                "items" to JSONArray().put(obj("path" to "a.kt", "additions" to 12, "deletions" to 4,
                    "writeCount" to 2, "toolNames" to JSONArray().put("Edit"),
                    "patches" to JSONArray().put(obj("oldStart" to 1, "newStart" to 1,
                        "lines" to JSONArray().put("+ hi"))))))
        }
        instrumentation.runOnMainSync { vm.loadTurnDiff() }
        assertTrue(await { it.diffText != null })
        val params = requests.single()
        assertEquals(7L, params.optLong("rowId"))
        assertEquals("turn-7", params.string("entityId"))
        assertEquals(5L, params.optLong("baseRevision"))
        assertEquals("e1", params.string("baseLogEpoch"))
        assertEquals("s1", params.string("sessionId"))
        val text = vm.state.value.diffText!!
        assertTrue(text.contains("1 个文件")); assertTrue(text.contains("+12"))
        assertTrue(text.contains("a.kt")); assertTrue(text.contains("+ hi"))
        instrumentation.runOnMainSync { vm.clearTurnDiff() }
        assertTrue(await { it.diffText == null })
    }

    @Test fun queueRemoveCarriesSnapshotRevisionAndStaleNeverAutoRetries() = runBlocking {
        freshViewModel()
        val queue = JSONArray().put(obj("queueItemId" to "q-1", "text" to "排队内容", "sourceCommandId" to "cmd-a"))
        scriptRead(queue = queue, revision = 5)
        selectReflection(session)
        instrumentation.runOnMainSync { vm.retryLoad() }
        assertTrue(await { it.queue.isNotEmpty() })
        val wireCalls = ConcurrentLinkedQueue<JSONObject>()
        val replies = CopyOnWriteArrayList<JSONObject>()
        bridge.scripted["session/queue/remove"] = { req ->
            wireCalls.add(req.optJSONObject("params") ?: obj())
            replies.last()
        }
        // First attempt: the server reports the snapshot was superseded (stale).
        replies.add(obj("status" to "stale", "reasonCode" to "revisionMismatch"))
        instrumentation.runOnMainSync { vm.removeQueued(vm.state.value.queue.first()) }
        assertTrue(await { it.error != null && !it.busy && !it.commandInFlight })
        assertTrue(vm.state.value.error!!.contains("未接受"))
        assertEquals(1, wireCalls.size) // surfaced, never retried automatically
        val params = wireCalls.single()
        assertEquals("q-1", params.string("queueItemId"))
        assertEquals(5L, params.optLong("baseRevision"))
        assertTrue(params.string("commandId").length >= 16)
        // After the user refreshes, an explicit retry with the fresh revision succeeds.
        scriptRead(queue = JSONArray(), revision = 6)
        instrumentation.runOnMainSync { vm.retryLoad() }
        assertTrue(await { it.revision == 6L && it.queue.isEmpty() })
        replies.add(obj("status" to "accepted"))
        state.value = state.value.copy(error = null)
        instrumentation.runOnMainSync { vm.removeQueued(QueuedMessage("q-1", "cmd-a", "排队内容")) }
        assertTrue(await { it.error == null && it.notice.isNotBlank() })
        assertEquals(2, wireCalls.size)
        assertEquals(6L, wireCalls.toList()[1].optLong("baseRevision"))
    }

    @Test fun oldBridgeCapabilitiesKeepQueueOpsOffAndExplainThemselves() = runBlocking {
        freshViewModel()
        state.value = state.value.copy(queueOpsEnabled = false)
        scriptRead()
        selectReflection(session)
        instrumentation.runOnMainSync { vm.retryLoad() }
        assertTrue(await { it.selected?.id == "s1" && it.logEpoch.isNotBlank() })
        val queued = QueuedMessage("q-1", "cmd-a", "内容")
        instrumentation.runOnMainSync { vm.removeQueued(queued) }
        assertTrue(await { it.error?.contains("桥接器") == true })
        // The capability gate rejects before anything reaches the wire.
        assertTrue(bridge.clientWrites.isEmpty())
    }

    @Test fun badgeAndQueueActionsRenderOnDevice() {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                PocketHelmTheme {
                    ZcodeTimeline(ZcodeState(
                        items = listOf(TimelineItem("u", "userMessage", "任务内容")),
                        turn = ZTurn(7, "turn-7", "running", 112_000, 318, 0, 2),
                        queueOpsEnabled = true, connected = true,
                        queue = listOf(QueuedMessage("q1", "cmd-a", "第二条排队"))),
                        androidx.lifecycle.ViewModelProvider(activity)[ZcodeViewModel::class.java],
                        androidx.compose.ui.Modifier.fillMaxSize(), {}, {}) { _, _ -> }
                }
            } }
            fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
                val found = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    found.add(node)
                    for (i in 0 until node.childCount) walk(node.getChild(i))
                }
                walk(instrumentation.uiAutomation.rootInActiveWindow)
                return found
            }
            fun matching(text: String) = nodes().filter {
                it.isVisibleToUser && (it.text?.toString()?.contains(text) == true) }
            fun awaitUi(text: String) {
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline && matching(text).isEmpty()) Thread.sleep(100)
                assertTrue("Expected UI text missing: $text", matching(text).isNotEmpty())
            }
            awaitUi("更改 +318 −0 · 2 文件")
            awaitUi("1 分 52 秒")
            awaitUi("移出队列")
            awaitUi("立即发送")
            awaitUi("编辑")
        }
    }

    @Test fun listMetaUnreadFollowsSeenTimestampsFromAuthoritativeReads() = runBlocking {
        freshViewModel()
        ZcodeViewModel::class.java.getDeclaredField("supportsSessionIndex").let {
            it.isAccessible = true; it.set(vm, true) }
        var activityAt = 1000L
        bridge.scripted["sessions/status"] = {
            obj("workspaces" to JSONArray().put(obj("workspacePath" to "/tmp/zcode-p1", "workspaceIdentity" to "",
                "fresh" to true, "sessions" to JSONArray().put(obj("sessionId" to "s1", "phase" to "waiting",
                    "lastActivityAt" to activityAt, "lastAssistantPreview" to "最近的回复",
                    "pendingInteractionSummary" to obj("permissionCount" to 1, "userInputCount" to 2))))))
        }
        scriptRead()
        instrumentation.runOnMainSync { vm.enter() }
        assertTrue(await { it.listMeta["s1"] != null })
        val first = vm.state.value.listMeta.getValue("s1")
        assertEquals(3, first.pending); assertEquals("最近的回复", first.preview); assertFalse(first.unread)
        // Opening the session records a seen timestamp from its authoritative read; while
        // it stays open it is never unread by definition (the open session is being read).
        selectReflection(session)
        assertTrue(await { seenMap().containsKey("s1") })
        // Leave first: only a non-open session can show unread.
        instrumentation.runOnMainSync { vm.back() }
        assertTrue(await { it.selected == null })
        // New desktop-side activity after the seen stamp turns the dot on; with no session
        // open no further stamps race the assertion, so the state is stable.
        activityAt = seenMap().getValue("s1") + 1
        assertTrue(await { it.listMeta["s1"]?.unread == true })
        // Reopening stamps seen from a fresh authoritative read and the dot clears.
        selectReflection(session)
        assertTrue(await { it.listMeta["s1"]?.unread == false && seenMap().getValue("s1") >= activityAt })
    }
}
