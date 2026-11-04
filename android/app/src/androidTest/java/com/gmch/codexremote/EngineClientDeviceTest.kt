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
        override suspend fun sessions(query: SessionQuery): SessionPage<Row> {
            validate(query)
            if (query.cursor != null || query.limit != null) throw UnsupportedEngineQuery("No list pagination")
            return SessionPage(listOf(Row(EngineSessionRef(origin, "s"))), null, SessionCatalogMode.Snapshot)
        }
        override suspend fun projects() = ProjectCatalog<EngineProject, EngineSession>(emptyList())
        override suspend fun history(session: EngineSessionRef, cursor: HistoryCursor?, onPage: (suspend (HistoryPage) -> Unit)?): HistoryPage {
            validate(session, cursor)
            if (cursor != null) throw UnsupportedEngineQuery("No history pagination")
            historyReads++
            val result = HistoryPage(session, listOf(EngineTimelineItem("m", "agentMessage", "通过统一会话接口读取")), null)
            onPage?.invoke(result); return result
        }
    }
    private class Host(val client: Client) : EngineHost {
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
}
