package com.gmch.codexremote

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gmch.codexremote.app.*
import com.gmch.codexremote.app.EngineApp as RegisteredEngineApp
import com.gmch.codexremote.engines.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID

/** This provider only implements the shared contracts. No Codex/ZCode state or RPC. */
@RunWith(AndroidJUnit4::class)
class EngineClientDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private data class Row(override val ref: EngineSessionRef) : EngineSession {
        override val title = "第三引擎会话"
        override val path = "/test"
        override val updatedAtMillis = 1L
    }
    private class Client : EngineClient {
        override val origin = EngineOrigin("fake-environment", "third", "primary")
        override val capabilities = EngineReadCapabilities(SessionCatalogMode.Snapshot, false)
        var historyReads = 0
        var sends = 0
        var stops = 0
        var created = false
        override val operations = setOf(EngineOperation.Create, EngineOperation.Send, EngineOperation.Interrupt)
        override suspend fun create(request: EngineCreateRequest, beforeDispatch: suspend () -> Unit): EngineCreatedSession {
            beforeDispatch(); created = true
            return object : EngineCreatedSession { override val session = Row(EngineSessionRef(origin, "new")) }
        }
        override suspend fun send(message: EngineMessage, beforeDispatch: suspend () -> Unit): EngineReceipt {
            beforeDispatch(); sends++
            return EngineReceipt(message.commandId, ReceiptDelivery.Accepted, message.session)
        }
        override suspend fun interrupt(session: EngineSessionRef, runId: String, commandId: String) { stops++ }
        override suspend fun inspect(session: EngineSessionRef) = EngineSessionSnapshot(session, "第三引擎会话", history(session), true, "run", true)
        override suspend fun sessions(query: SessionQuery): SessionPage<Row> {
            validate(query)
            if (query.cursor != null || query.limit != null) throw UnsupportedEngineQuery("No list pagination")
            return SessionPage(listOf(Row(EngineSessionRef(origin, "s"))), null, SessionCatalogMode.Snapshot)
        }
        override suspend fun projects() = ProjectCatalog<EngineProject, EngineSession>(listOf(object : EngineProject {
            override val origin = this@Client.origin
            override val nativeId = "p"
            override val title = "测试项目"
            override val roots = listOf("/test")
        }))
        override suspend fun history(session: EngineSessionRef, cursor: HistoryCursor?, onPage: (suspend (HistoryPage) -> Unit)?): HistoryPage {
            validate(session, cursor)
            if (cursor != null) throw UnsupportedEngineQuery("No history pagination")
            historyReads++
            val result = HistoryPage(session, listOf(EngineTimelineItem("m", "agentMessage", "通过统一会话接口读取")), null)
            onPage?.invoke(result); return result
        }
    }
    private class Host(var client: Client) : EngineHost {
        override val descriptor = EngineDescriptor("third", "Third")
        override val connection = MutableStateFlow(EngineConnection(connected = true))
        override fun sessionClient() = client.takeIf { connection.value.connected }
        override fun enter() = Unit
        override fun leave() = Unit
        override fun keepConnection() = Unit
        override fun background() = Unit
        override fun connect() { connection.value = EngineConnection(connected = true) }
        override fun disconnect() { connection.value = EngineConnection() }
        override fun prepareEnvironmentChange() = disconnect()
        override fun reloadEnvironment() = Unit
        override fun dismissError() = Unit
        override fun dismissChallenge() = Unit
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) { if (n == null) return; result += n; for (i in 0 until n.childCount) walk(n.getChild(i)) }
        walk(inst.uiAutomation.rootInActiveWindow)
        return result.filter { it.isVisibleToUser }
    }
    private fun awaitUi(test: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!test() && System.nanoTime() < end) Thread.sleep(30)
        assertTrue(test())
    }
    @Test fun registeredThirdEngineCanListAndReadThroughTheSharedClient(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var app: AppViewModel
        inst.runOnMainSync { app = AppViewModel(fixture) }
        val client = Client(); val host = Host(client)
        lateinit var registry: EngineUiRegistry
        registry = EngineUiRegistry(listOf(EngineUiEntry(host) {
            var sessions by remember { mutableStateOf<List<EngineSession>>(emptyList()) }
            var history by remember { mutableStateOf<HistoryPage?>(null) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { sessions = registry.runtime.sessionClient("third")!!.sessions().items }
            Column {
                sessions.forEach { row -> TextButton(onClick = {
                    scope.launch { history = registry.runtime.sessionClient("third")!!.history(row.ref) }
                }) { Text(row.title) } }
                history?.items?.forEach { Text(it.text) }
            }
        }))
        try {
            inst.runOnMainSync { app.bind(registry.runtime, EnvironmentVerifier { _, _ -> error("not used") }); app.start() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { PocketHelmTheme { RegisteredEngineApp(app, registry) } } }
                awaitUi { nodes().any { it.text?.toString() == "第三引擎会话" } }
                var node = nodes().first { it.text?.toString() == "第三引擎会话" }
                while (!node.isClickable && node.parent != null) node = node.parent
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitUi { nodes().any { it.text?.toString() == "通过统一会话接口读取" } }
                assertEquals(1, client.historyReads)
                inst.runOnMainSync { host.disconnect() }
                assertNull(registry.runtime.sessionClient("third"))
            }
        } finally {
            inst.runOnMainSync { app.stop(); app.viewModelScope.cancel() }
            inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
        }
    }
    private fun click(text: String) {
        awaitUi { nodes().any { it.text?.toString() == text } }
        var node = nodes().first { it.text?.toString() == text }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun genericUiCreatesSendsAndStopsThirdEngineWithoutNativeScreen(): Unit = runBlocking {
        val fixture = TestPrefsApp(UUID.randomUUID().toString()).also { it.attach(inst.targetContext) }
        lateinit var app: AppViewModel
        lateinit var controller: com.gmch.codexremote.application.EngineSessionController
        val client = Client(); val host = Host(client)
        inst.runOnMainSync {
            app = AppViewModel(fixture)
            controller = com.gmch.codexremote.application.EngineSessionController(app.viewModelScope,
                com.gmch.codexremote.data.VaultEngineSessionStore(fixture))
        }
        val registry = EngineUiRegistry(listOf(genericEngineEntry(host, controller)))
        try {
            inst.runOnMainSync { app.bind(registry.runtime, EnvironmentVerifier { _, _ -> error("not used") }); app.start() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { PocketHelmTheme { RegisteredEngineApp(app, registry) } } }
                click("新建会话"); click("测试项目")
                awaitUi { nodes().any { it.isEditable } }
                // Simulate a fast reconnect whose transient disconnected state was conflated.
                val replacement = Client()
                inst.runOnMainSync { host.client = replacement; host.connection.value = host.connection.value.copy(connectionRevision = 1) }
                awaitUi { replacement.historyReads > 0 && controller.state.value.choicesLoaded }
                val input = nodes().first { it.isEditable }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "本地假引擎测试")
                }))
                click("发送")
                awaitUi { replacement.sends == 1 && !controller.state.value.working }
                click("停止执行")
                awaitUi { replacement.stops == 1 }
                assertEquals(0, client.sends)
                assertTrue(client.created)
                assertTrue(controller.state.value.pending.isEmpty())
                assertEquals("", controller.state.value.draft)
            }
        } finally {
            inst.runOnMainSync { app.stop(); app.viewModelScope.cancel() }
            inst.targetContext.deleteSharedPreferences("zcode_load_test_${fixture.namespace}-vault")
        }
    }

}
