package com.gmch.codexremote

import com.gmch.codexremote.engines.contract.*
import com.gmch.codexremote.engines.codex.CodexEngineClient
import com.gmch.codexremote.engines.zcode.ZcodeEngineClient
import com.gmch.codexremote.transport.RpcEndpoint
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineClientContractTest {
    private fun origin(engine: String) = EngineOrigin("environment", engine, "primary")
    private class Wire(val answer: suspend (String, JSONObject) -> JSONObject) : RpcEndpoint {
        data class Request(val method: String, val params: JSONObject, val timeout: Long)
        val requests = mutableListOf<Request>()
        val order = mutableListOf<String>()
        override suspend fun call(method: String, params: JSONObject, timeout: Long, onResult: (suspend (JSONObject) -> Unit)?): JSONObject {
            requests += Request(method, params, timeout)
            val response = answer(method, params)
            onResult?.invoke(response)
            order += "next-event"
            return response
        }
    }
    private fun codexWire() = Wire { method, _ -> when (method) {
        "thread/list" -> obj("data" to JSONArray().put(obj("id" to "s", "name" to "session", "cwd" to "/work", "updatedAt" to 1700000000L,
            "status" to obj("type" to "idle"))), "nextCursor" to "next")
        "fs/readDirectory" -> obj("entries" to JSONArray().put(obj("fileName" to "project", "isDirectory" to true)))
        "thread/turns/list" -> obj("data" to JSONArray().put(obj("id" to "turn", "status" to "completed")))
        "thread/items/list" -> obj("data" to JSONArray().put(obj("item" to obj("id" to "new", "type" to "agentMessage", "text" to "new")))
            .put(obj("item" to obj("id" to "old", "type" to "userMessage", "clientId" to "c", "content" to JSONArray().put(obj("type" to "text", "text" to "old"))))), "nextCursor" to "older")
        else -> error("Unexpected RPC $method")
    } }
    private fun zcodeWire(epoch: String = "e", revision: Long = 1) = Wire { method, _ -> when (method) {
        "projects" -> obj("projects" to JSONArray().put(obj("id" to "p", "name" to "project", "roots" to JSONArray().put("/work"))),
            "sessions" to JSONArray().put(obj("taskId" to "s", "title" to "session", "workspacePath" to "/work", "workspaceIdentity" to "w", "updatedAt" to 1700000000000L)))
        "session/read", "session/history" -> obj("items" to JSONArray().put(obj("id" to "old", "kind" to "userInput", "text" to "old", "commandId" to "c"))
            .put(obj("id" to "new", "kind" to "assistantText", "text" to "new")), "hasMore" to true,
            "firstRowId" to 20.0, "beforeRowId" to 10.0, "logEpoch" to epoch, "revision" to revision)
        else -> error("Unexpected RPC $method")
    } }
    private data class ThirdSession(override val ref: EngineSessionRef) : EngineSession {
        override val title = "session"; override val path = "/work"; override val updatedAtMillis = 1700000000000L
    }
    private data class ThirdProject(override val origin: EngineOrigin) : EngineProject {
        override val nativeId = "p"; override val title = "project"; override val roots = listOf("/work")
    }
    private class ThirdClient(override val origin: EngineOrigin) : EngineClient {
        override val capabilities = EngineReadCapabilities(SessionCatalogMode.Snapshot, false)
        override suspend fun sessions(query: SessionQuery): SessionPage<ThirdSession> {
            validate(query)
            return SessionPage(listOf(ThirdSession(EngineSessionRef(origin, "s"))), null, SessionCatalogMode.Snapshot)
        }
        override suspend fun projects() = ProjectCatalog<ThirdProject, ThirdSession>(listOf(ThirdProject(origin)))
        override suspend fun history(session: EngineSessionRef, cursor: HistoryCursor?, onPage: (suspend (HistoryPage) -> Unit)?): HistoryPage {
            validate(session, cursor)
            if (cursor != null) throw UnsupportedEngineQuery("No history paging")
            val page = HistoryPage(session, listOf(EngineTimelineItem("old", "userMessage", "old", clientId = "c"), EngineTimelineItem("new", "agentMessage", "new")), null)
            onPage?.invoke(page); return page
        }
    }
    @Test fun allThreeImplementTheSameReadContractWithoutNativeViewModels() = runBlocking {
        val clients: List<EngineClient> = listOf(CodexEngineClient(origin("codex"), codexWire(), "/work"),
            ZcodeEngineClient(origin("zcode"), zcodeWire()), ThirdClient(origin("third")))
        for (client in clients) {
            val catalog = client.projects()
            assertEquals(listOf("/work/project").takeIf { client.origin.engine == "codex" } ?: listOf("/work"), catalog.projects.single().roots)
            val sessions = catalog.sessions ?: client.sessions()
            val session = sessions.items.single()
            assertEquals(client.origin, session.ref.origin); assertEquals(1700000000000L, session.updatedAtMillis)
            val history = client.history(session.ref)
            assertEquals(listOf("old", "new"), history.items.map { it.id })
            assertEquals("c", history.items.first().clientId)
            assertEquals(session.ref, history.session)
        }
    }
    @Test fun codexPreservesNativePagingAndReceiveOrder() = runBlocking {
        val wire = codexWire(); val client = CodexEngineClient(origin("codex"), wire, "/work")
        val first = client.sessions(SessionQuery(limit = 30))
        client.sessions(SessionQuery(first.next, 30))
        assertEquals("next", wire.requests.last().params.getString("cursor"))
        assertEquals(listOf("cli", "vscode", "appServer", "exec", "unknown"), wire.requests.first().params.getJSONArray("sourceKinds").strings())
        wire.order.clear()
        val history = client.history(client.session("s"), onPage = { wire.order += "snapshot" })
        assertEquals(listOf("snapshot", "next-event"), wire.order)
        assertEquals("older", history.next?.token)
        val turn = client.latestTurn(client.session("s"), timeout = 10_000)
        assertEquals("turn", turn?.id); assertEquals(10_000L, wire.requests.last().timeout)
    }
    @Test fun foreignSessionAndCursorAreRejectedBeforeAnyRpc() = runBlocking {
        val wire = codexWire(); val client = CodexEngineClient(origin("codex"), wire, "/work")
        val ref = client.session("s")
        for (foreign in listOf(ref.copy(origin = ref.origin.copy(environment = "other")), ref.copy(origin = ref.origin.copy(engine = "third")), ref.copy(origin = ref.origin.copy(instance = "other")))) {
            assertTrue(runCatching { client.history(foreign) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { client.history(ref, HistoryCursor(ref.copy(nativeId = "different"), "token")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { client.sessions(SessionQuery(SessionCursor(origin("third"), "token"))) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(wire.requests.isEmpty())
    }
    @Test fun zcodeUsesOneSnapshotAndDoesNotPretendToSupportSessionPagination() = runBlocking {
        val wire = zcodeWire(); val client = ZcodeEngineClient(origin("zcode"), wire)
        val catalog = client.projects()
        assertNotNull(catalog.sessions); assertEquals(SessionCatalogMode.Snapshot, catalog.sessions!!.mode)
        assertEquals(1, wire.requests.size)
        assertTrue(runCatching { client.sessions(SessionQuery(limit = 30)) }.exceptionOrNull() is UnsupportedEngineQuery)
        assertEquals(1, wire.requests.size)
    }
    @Test fun zcodeHistoryBindsWorkspaceAndRevisionBeforeApplyingPage() = runBlocking {
        val wire = zcodeWire(); val client = ZcodeEngineClient(origin("zcode"), wire)
        val ref = client.session(ZSession("s", "session", "/work", "identity"))
        val cursor = client.cursor(ref, 20.0, "e", 1)
        val page = client.history(ref, cursor)
        assertEquals(20.0, wire.requests.single().params.getDouble("beforeRowId"), 0.0)
        assertEquals("identity", wire.requests.single().params.getString("workspaceIdentity"))
        assertEquals(10.0, client.before(page.next!!), 0.0)
        assertTrue(runCatching { client.history(ref.copy(workspaceIdentity = "other"), cursor) }.exceptionOrNull() is IllegalArgumentException)
        val stale = ZcodeEngineClient(origin("zcode"), zcodeWire(revision = 2))
        var applied = false
        assertTrue(runCatching { stale.history(ref, cursor) { applied = true } }.exceptionOrNull() is IllegalStateException)
        assertFalse(applied)
    }
    @Test fun errorsAndCancellationPropagateWithoutRetry() = runBlocking {
        val failure = RpcException(-32600, "native rejection")
        val bad = Wire { _, _ -> throw failure }
        val client = CodexEngineClient(origin("codex"), bad, "/work")
        assertSame(failure, runCatching { client.sessions() }.exceptionOrNull()); assertEquals(1, bad.requests.size)
        val entered = CompletableDeferred<Unit>()
        val paused = Wire { _, _ -> entered.complete(Unit); awaitCancellation() }
        val job = launch { CodexEngineClient(origin("codex"), paused, "/work").history(EngineSessionRef(origin("codex"), "s")) }
        entered.await(); job.cancelAndJoin(); assertTrue(job.isCancelled); assertEquals(1, paused.requests.size)
    }
    @Test fun filesystemRootAndHiddenDirectoriesAreHandledByCodexAdapter() = runBlocking {
        val wire = Wire { _, params ->
            assertEquals("/", params.getString("path"))
            obj("entries" to JSONArray().put(obj("fileName" to ".hidden", "isDirectory" to true))
                .put(obj("fileName" to "visible", "isDirectory" to true)).put(obj("fileName" to "file", "isDirectory" to false)))
        }
        assertEquals(listOf("/visible"), CodexEngineClient(origin("codex"), wire, "/").projects().projects.map { it.path })
    }
    @Test fun registryRejectsAClientClaimingAnotherEngine() {
        val foreign = ThirdClient(origin("another"))
        val host = object : EngineHost {
            override val descriptor = EngineDescriptor("third", "Third")
            override val connection = kotlinx.coroutines.flow.MutableStateFlow(EngineConnection(connected = true))
            override fun sessionClient(): EngineClient = foreign
            override fun enter() = Unit
            override fun leave() = Unit
            override fun keepConnection() = Unit
            override fun background() = Unit
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun prepareEnvironmentChange() = Unit
            override fun reloadEnvironment() = Unit
            override fun dismissError() = Unit
            override fun dismissChallenge() = Unit
        }
        assertThrows(IllegalStateException::class.java) { EngineRegistry(listOf(host)).sessionClient("third") }
    }

}
