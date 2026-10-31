package com.gmch.codexremote

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class TimelineSyncTest {
    @Test fun historyAndDeltasAreAppliedInWireOrderWithoutLossOrDuplication() = runBlocking {
        val cases = listOf(
            Triple(TimelineItem("reply", "agentMessage", "hello world"), "world", "!"),
            // Repeated text cannot be aligned reliably using string overlap heuristics.
            Triple(TimelineItem("reply", "agentMessage", "haha"), "ha", "ha"),
            Triple(TimelineItem("reply", "commandExecution", "$ echo hello\nhello world"), "world", "\n"),
            Triple(TimelineItem("reply", "plan", "first, second"), "second", ", third")
        )
        for ((snapshot, before, after) in cases) {
            val timeline = linkedMapOf<String, TimelineItem>()
            val baseline = timeline.toMap()
            val input = PipedInputStream()
            val server = PipedOutputStream(input)
            val output = ByteArrayOutputStream()
            val snapshotApplied = CompletableDeferred<Unit>()
            val releaseSnapshot = CompletableDeferred<Unit>()
            val finalDeltaApplied = CompletableDeferred<Unit>()
            val client = RpcClient(input, output, { event ->
                val delta = event.getJSONObject("params").getString("delta")
                val old = timeline[snapshot.id] ?: snapshot.copy(text = "")
                timeline[snapshot.id] = old.copy(text = old.text + delta)
                if (event.getJSONObject("params").optBoolean("last")) finalDeltaApplied.complete(Unit)
            }, {})
            try {
                val request = async {
                    client.call("thread/items/list", onResult = {
                        applyHistorySnapshot(timeline, listOf(snapshot), baseline)
                        snapshotApplied.complete(Unit)
                        releaseSnapshot.await() // Simulate delayed Main dispatcher processing.
                    })
                }
                withTimeout(3000) { while (output.size() == 0) delay(1) }
                fun delta(text: String, last: Boolean = false) = obj("method" to "delta", "params" to obj("delta" to text, "last" to last))
                val messages = listOf(delta(before), obj("id" to "1", "result" to obj()), delta(after, last = true))
                server.write(messages.joinToString("\n", postfix = "\n").toByteArray()); server.flush()
                withTimeout(3000) { snapshotApplied.await() }
                assertEquals(snapshot.text, timeline.getValue(snapshot.id).text)
                assertFalse(finalDeltaApplied.isCompleted)
                releaseSnapshot.complete(Unit)
                withTimeout(3000) { request.await(); finalDeltaApplied.await() }
                assertEquals(snapshot.text + after, timeline.getValue(snapshot.id).text)
            } finally { client.close(); server.close(); input.close() }
        }
    }

    @Test fun refreshReplacesCoveredLiveItemsButKeepsUnlistedNewItems() {
        val old = TimelineItem("reply", "agentMessage", "hello")
        val baseline = linkedMapOf(old.id to old, "old-page" to TimelineItem("old-page", "userMessage", "older"))
        val newItem = TimelineItem("new", "agentMessage", "next reply")
        val timeline = LinkedHashMap(baseline).apply {
            put(old.id, old.copy(text = "hello world"))
            put(newItem.id, newItem)
        }
        val full = old.copy(text = "hello world!", status = "completed")
        applyHistorySnapshot(timeline, listOf(full), baseline)
        assertEquals(listOf(full, newItem), timeline.values.toList())
    }

    @Test fun rejectedHistoryRequestDoesNotApplyASnapshot() = runBlocking {
        val input = PipedInputStream(); val server = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        var applied = false
        val client = RpcClient(input, output, {}, {})
        try {
            val request = async { runCatching { client.call("thread/items/list", onResult = { applied = true }) } }
            withTimeout(3000) { while (output.size() == 0) delay(1) }
            server.write((obj("id" to "1", "error" to obj("code" to -1, "message" to "rejected")).toString() + "\n").toByteArray())
            server.flush()
            assertTrue(withTimeout(3000) { request.await() }.exceptionOrNull() is RpcException)
            assertFalse(applied)
        } finally { client.close(); server.close(); input.close() }
    }

    @Test fun disconnectDuringSnapshotApplicationFailsTheWaitingRequest() = runBlocking {
        val input = PipedInputStream(); val server = PipedOutputStream(input)
        val output = ByteArrayOutputStream()
        val applying = CompletableDeferred<Unit>()
        val client = RpcClient(input, output, {}, {})
        try {
            val request = async {
                runCatching { client.call("thread/items/list", onResult = { applying.complete(Unit); awaitCancellation() }) }
            }
            withTimeout(3000) { while (output.size() == 0) delay(1) }
            server.write((obj("id" to "1", "result" to obj()).toString() + "\n").toByteArray()); server.flush()
            withTimeout(3000) { applying.await() }
            client.close()
            assertTrue(withTimeout(3000) { request.await() }.isFailure)
        } finally { client.close(); server.close(); input.close() }
    }
}
