package com.gmch.codexremote

import com.gmch.codexremote.application.*
import com.gmch.codexremote.engines.contract.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EngineSessionControllerTest {
    private class Client : EngineClient {
        override val origin = EngineOrigin("test", "third", "one")
        override val capabilities = EngineReadCapabilities(SessionCatalogMode.Snapshot, false)
        override val operations = setOf(EngineOperation.Create, EngineOperation.Send, EngineOperation.Interrupt)
        val ref = EngineSessionRef(origin, "session", "/work")
        val row = object : EngineSession {
            override val ref = this@Client.ref
            override val title = "[测试] third"
            override val path = "/work"
            override val updatedAtMillis = 0L
        }
        val project = object : EngineProject {
            override val origin = this@Client.origin
            override val nativeId = "project"
            override val title = "project"
            override val roots = listOf("/work")
        }
        var pageHandler: (suspend (HistoryCursor?) -> HistoryPage)? = null
        var sends = 0
        var stops = 0
        var failure: Exception? = null
        var last: EngineMessage? = null
        var items = emptyList<EngineTimelineItem>()
        var beforeSend: () -> Unit = {}
        override suspend fun sessions(query: SessionQuery) = SessionPage(listOf(row), null, SessionCatalogMode.Snapshot)
        override suspend fun projects() = ProjectCatalog(listOf(project), sessions())
        override suspend fun history(session: EngineSessionRef, cursor: HistoryCursor?, onPage: (suspend (HistoryPage) -> Unit)?): HistoryPage =
            (pageHandler?.invoke(cursor) ?: HistoryPage(session, items, null)).also { onPage?.invoke(it) }
        override suspend fun inspect(session: EngineSessionRef) = EngineSessionSnapshot(session, row.title, history(session), true, "run", true)
        override suspend fun create(request: EngineCreateRequest, beforeDispatch: suspend () -> Unit): EngineCreatedSession {
            beforeDispatch()
            return object : EngineCreatedSession { override val session = row }
        }
        override suspend fun send(message: EngineMessage, beforeDispatch: suspend () -> Unit): EngineReceipt {
            beforeDispatch(); beforeSend(); sends++; last = message
            failure?.let { throw it }
            return EngineReceipt(message.commandId, ReceiptDelivery.Accepted, message.session, "run")
        }
        override suspend fun interrupt(session: EngineSessionRef, runId: String, commandId: String) { stops++ }
    }
    private suspend fun ready(controller: EngineSessionController, client: Client) {
        controller.bind(client); controller.open(client.ref); controller.refresh()?.join()
    }
    @Test fun thirdEngineCreateSendReadStopUsesSharedWorkflow() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = MemoryEngineSessionStore(); val client = Client(); val controller = EngineSessionController(scope, store)
            controller.bind(client); controller.refresh()?.join()
            controller.create(client.project).join(); controller.refresh()?.join()
            assertEquals(client.ref, controller.state.value.selected)
            client.beforeSend = { assertEquals(1, store.pending(client.origin).size) }
            controller.draft("one"); controller.send().join()
            assertEquals(1, client.sends); assertEquals("", store.draft(client.ref))
            assertTrue(store.pending(client.origin).isEmpty())
            controller.interrupt().join(); assertEquals(1, client.stops)
        } finally { scope.cancel() }
    }
    @Test fun lostAcknowledgementDoesNotReplayAndOnlyUserEvidenceSettlesIt() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = MemoryEngineSessionStore(); val client = Client(); val controller = EngineSessionController(scope, store)
            ready(controller, client); client.failure = IOException("lost")
            controller.draft("one"); controller.send().join()
            val id = client.last!!.commandId
            assertEquals(OutgoingState.UNCERTAIN, controller.state.value.outgoing.single().state)
            client.items = listOf(EngineTimelineItem("assistant", "agentMessage", "one", clientId = id))
            controller.refresh()?.join(); controller.send().join()
            assertEquals(1, client.sends); assertEquals(1, store.pending(client.origin).size)
            client.items = listOf(EngineTimelineItem("user", "userMessage", "one", clientId = id))
            controller.refresh()?.join()
            assertTrue(store.pending(client.origin).isEmpty()); assertEquals("", controller.state.value.draft)
            assertTrue(controller.state.value.outgoing.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun explicitAcknowledgementRemovesBubbleAndPreservesNewDraft() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = MemoryEngineSessionStore(); val client = Client(); val controller = EngineSessionController(scope, store)
            ready(controller, client); client.failure = IOException("lost")
            controller.draft("old"); controller.send().join(); controller.draft("new")
            controller.acknowledgeUnknown(client.last!!.commandId)
            assertTrue(controller.state.value.outgoing.isEmpty()); assertEquals("new", controller.state.value.draft)
        } finally { scope.cancel() }
    }
    @Test fun receiptPersistenceFailurePreventsDispatch() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val memory = MemoryEngineSessionStore()
            val store = object : EngineSessionStore by memory { override fun savePending(operation: PendingOperation) { throw IOException("full") } }
            val client = Client(); val controller = EngineSessionController(scope, store)
            ready(controller, client); controller.draft("one"); controller.send().join()
            assertEquals(0, client.sends); assertEquals("one", controller.state.value.draft)
            assertEquals(OutgoingState.FAILED, controller.state.value.outgoing.single().state)
        } finally { scope.cancel() }
    }
    @Test fun definitiveRejectionClearsProtectionButKeepsDraft() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = MemoryEngineSessionStore(); val client = Client(); val controller = EngineSessionController(scope, store)
            ready(controller, client); client.failure = EngineRejectedException("rejected", "rejected")
            controller.draft("one"); controller.send().join()
            assertTrue(store.pending(client.origin).isEmpty()); assertEquals("one", controller.state.value.draft)
            assertEquals(OutgoingState.FAILED, controller.state.value.outgoing.single().state)
        } finally { scope.cancel() }
    }
    @Test fun expandedHistorySurvivesAutomaticRefreshUntilExplicitRefresh() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val client = Client(); val controller = EngineSessionController(scope, MemoryEngineSessionStore())
            client.pageHandler = { cursor -> HistoryPage(client.ref,
                listOf(EngineTimelineItem(if (cursor == null) "new" else "old", "agentMessage", "text")),
                if (cursor == null) HistoryCursor(client.ref, "older") else null) }
            ready(controller, client)
            controller.moreHistory()?.join()
            assertEquals(listOf("old", "new"), controller.state.value.snapshot!!.history.items.map { it.id })
            assertNull(controller.refresh(refreshChoices = false))
            assertTrue(controller.state.value.readingOlder)
            controller.refresh()?.join()
            assertEquals(listOf("new"), controller.state.value.snapshot!!.history.items.map { it.id })
            assertFalse(controller.state.value.readingOlder)
        } finally { scope.cancel() }
    }
    @Test fun lateHistoryPageCannotReplaceCursorAfterRefresh() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val client = Client(); val controller = EngineSessionController(scope, MemoryEngineSessionStore())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            client.pageHandler = { cursor ->
                if (cursor != null) { entered.complete(Unit); release.await() }
                HistoryPage(client.ref, listOf(EngineTimelineItem(if (cursor == null) "new" else "old", "agentMessage", "text")),
                    if (cursor == null) HistoryCursor(client.ref, "older") else null)
            }
            ready(controller, client)
            val job = controller.moreHistory()!!; entered.await()
            controller.refresh()?.join(); release.complete(Unit); job.join()
            assertEquals(listOf("new"), controller.state.value.snapshot!!.history.items.map { it.id })
            assertEquals("older", controller.state.value.snapshot!!.history.next!!.token)
        } finally { scope.cancel() }
    }

    @Test fun expandingHistoryCancelsInFlightAutomaticRefresh() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val client = Client(); val controller = EngineSessionController(scope, MemoryEngineSessionStore())
            val entered = CompletableDeferred<Unit>(); var stall = false
            client.pageHandler = { cursor ->
                if (cursor == null && stall) { entered.complete(Unit); awaitCancellation() }
                HistoryPage(client.ref, listOf(EngineTimelineItem(if (cursor == null) "new" else "old", "agentMessage", "text")),
                    if (cursor == null) HistoryCursor(client.ref, "older") else null)
            }
            ready(controller, client); stall = true
            val refreshing = controller.refresh(refreshChoices = false)!!; entered.await()
            controller.moreHistory()?.join(); refreshing.join()
            assertTrue(refreshing.isCancelled)
            assertEquals(listOf("old", "new"), controller.state.value.snapshot!!.history.items.map { it.id })
            assertTrue(controller.state.value.readingOlder)
        } finally { scope.cancel() }
    }

}
