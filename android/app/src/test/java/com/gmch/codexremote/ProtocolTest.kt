package com.gmch.codexremote

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*

class ProtocolTest {
    @Test fun unknownRuntimeIsNotMisrepresentedAsCompletion() {
        val s = SessionInfo.parse(obj("id" to "a", "cwd" to "/projects/demo", "status" to obj("type" to "notLoaded")))
        assertEquals("未加载", s.status)
        assertEquals("失败", s.copy(lastTurnStatus = "failed").status)
        assertEquals("已中断", s.copy(lastTurnStatus = "interrupted").status)
        assertEquals("执行中", s.copy(runtime = "active", lastTurnStatus = "completed").status)
    }
    @Test fun historyPreservesMessageIdentityAndCommandOutput() {
        val user = TimelineItem.parse(obj("id" to "item", "clientId" to "sent-uuid", "type" to "userMessage", "content" to textInput("继续")))
        assertEquals("sent-uuid", user.clientId); assertEquals("继续", user.text)
        val tool = TimelineItem.parse(obj("id" to "cmd", "type" to "commandExecution", "command" to "go test", "aggregatedOutput" to "ok\n", "status" to "completed"))
        assertTrue(tool.text.contains("$ go test\nok"))
    }
    @Test fun shellQuotingCannotEvaluateUserText() {
        val text = "a'b\n\$(touch /tmp/not-created)"
        val quoted = shellQuote(text)
        val process = ProcessBuilder("bash", "-c", "printf %s $quoted").start()
        assertEquals(text, process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
    }
    @Test fun rpcCorrelatesOutOfOrderResponsesAndEvents() = runBlocking {
        val source = PipedInputStream(); val server = PipedOutputStream(source)
        val output = ByteArrayOutputStream(); val events = mutableListOf<String>()
        val client = RpcClient(source, output, { synchronized(events) { events.add(it.string("method")) } }, {})
        try {
            val first = async { client.call("first") }; val second = async { client.call("second") }
            withTimeout(3000) { while (output.toString().lines().count { it.isNotBlank() } < 2) delay(10) }
            server.write(("{\"id\":\"2\",\"result\":{\"n\":2}}\n" +
                "{\"method\":\"event\",\"params\":{}}\n{\"id\":\"1\",\"result\":{\"n\":1}}\n").toByteArray()); server.flush()
            assertEquals(1, first.await().getInt("n")); assertEquals(2, second.await().getInt("n"))
            assertEquals(listOf("event"), events)
        } finally { client.close(); server.close(); source.close() }
    }
    @Test fun disconnectFailsOutstandingRequests() = runBlocking {
        val source = PipedInputStream(); val server = PipedOutputStream(source)
        val client = RpcClient(source, ByteArrayOutputStream(), {}, {})
        val request = async { runCatching { client.call("pending") } }
        delay(30); client.close()
        assertTrue(withTimeout(1000) { request.await() }.isFailure)
        server.close(); source.close()
    }

    /** Server that answers the first request late; later requests get an immediate reply. */
    @Test fun singleRequestTimeoutIsATransportFailureNotCancellation() = runBlocking {
        val source = PipedInputStream(); val server = PipedOutputStream(source)
        val output = ByteArrayOutputStream()
        val client = RpcClient(source, output, {}, {})
        try {
            // First request: server stays silent past the deadline.
            val timeout = async { runCatching { client.call("slow", timeout = 120) } }
            withTimeout(3000) { while (output.toString().lines().none { it.contains("slow") }) delay(5) }
            val failure = timeout.await().exceptionOrNull()
            assertTrue("超时应为普通失败：$failure", failure is IOException)
            assertFalse("不得伪装成明确拒绝", failure is RpcException)
            assertFalse("不得伪装成协程取消", failure is CancellationException)
            // The late response for the abandoned request must be ignored; the next call works.
            val lines = output.toString().lines().filter { it.isNotBlank() }
            val firstId = JSONObject(lines.first()).string("id")
            server.write(("{\"id\":\"$firstId\",\"result\":{\"late\":true}}\n" +
                "{\"id\":\"999\",\"result\":{\"wrong\":true}}\n").toByteArray()); server.flush()
            val reply = async { runCatching { client.call("fast", timeout = 5000) } }
            withTimeout(3000) { while (output.toString().lines().count { it.isNotBlank() } < 2) delay(5) }
            val secondId = JSONObject(output.toString().lines().filter { it.isNotBlank() }[1]).string("id")
            server.write(("{\"id\":\"$secondId\",\"result\":{\"n\":7}}\n").toByteArray()); server.flush()
            assertEquals(7, reply.await().getOrThrow().getInt("n"))
        } finally { client.close(); server.close(); source.close() }
    }

    @Test fun callerCancellationStillPropagatesAsCancellation() = runBlocking {
        val source = PipedInputStream(); val server = PipedOutputStream(source)
        val client = RpcClient(source, ByteArrayOutputStream(), {}, {})
        try {
            // No runCatching: a swallowed CancellationException would complete normally and
            // hide the very regression this test guards against.
            val job = async { client.call("pending", timeout = 60_000) }
            delay(50)
            job.cancel(); job.join()
            assertTrue("调用方取消必须仍是取消：${job.getCompletionExceptionOrNull()}", job.isCancelled)
        } finally { client.close(); server.close(); source.close() }
    }

    @Test fun respondEchoesRequestIdWithTypePreserved() = runBlocking {
        val source = PipedInputStream(); val server = PipedOutputStream(source)
        val output = ByteArrayOutputStream()
        val client = RpcClient(source, output, {}, {})
        try {
            client.respond(7, obj("decision" to "accept"))           // numeric id stays numeric
            client.respond("7", obj("decision" to "decline"))        // string id stays string
            val lines = output.toString().lines().filter { it.isNotBlank() }
            assertEquals(2, lines.size)
            assertEquals(7, JSONObject(lines[0]).getInt("id"))
            assertEquals("accept", JSONObject(lines[0]).getJSONObject("result").string("decision"))
            assertEquals("7", JSONObject(lines[1]).getString("id"))
            assertEquals("decline", JSONObject(lines[1]).getJSONObject("result").string("decision"))
        } finally { client.close(); server.close(); source.close() }
    }

    @Test fun approvalKeysKeepNumberAndStringIdsDistinct() {
        assertEquals("n:7", PendingApproval.keyOf(7))
        assertEquals("s:7", PendingApproval.keyOf("7"))
        assertNotEquals(PendingApproval.keyOf(7), PendingApproval.keyOf("7"))
        // Same numeric value in Int or Long form is the same id; only the string form differs.
        assertEquals(PendingApproval.keyOf(7L), PendingApproval.keyOf(7))
    }

    @Test fun approvalParsingCoversKindsDecisionsAndDefaults() {
        val decisions = org.json.JSONArray()
            .put("accept").put("decline").put(obj("acceptWithExecpolicyAmendment" to obj()))
        val command = obj("id" to 11, "method" to "item/commandExecution/requestApproval",
            "params" to obj("threadId" to "t1", "turnId" to "u1", "itemId" to "c1",
                "kind" to "command", "command" to "rm -rf build", "cwd" to "/w",
                "availableDecisions" to decisions))
        val parsed = PendingApproval.parse(command, 3)!!
        assertEquals("n:11", parsed.key); assertEquals(11, parsed.rawId)
        assertEquals(3, parsed.generation); assertEquals("command", parsed.kind)
        assertEquals(listOf("accept", "decline"), parsed.decisions)
        assertTrue(parsed.hasObjectDecisions)

        val writeStdin = obj("id" to "s-1", "method" to "item/commandExecution/requestApproval",
            "params" to obj("threadId" to "t1", "turnId" to "u1", "itemId" to "c2",
                "kind" to "writeStdin", "command" to "y"))
        assertEquals("writeStdin", PendingApproval.parse(writeStdin, 3)!!.kind)

        val fileChange = obj("id" to 12, "method" to "item/fileChange/requestApproval",
            "params" to obj("threadId" to "t1", "turnId" to "u1", "itemId" to "f1",
                "reason" to "修改配置", "grantRoot" to "/w/config"))
        val file = PendingApproval.parse(fileChange, 3)!!
        assertEquals("fileChange", file.kind); assertEquals("/w/config", file.grantRoot)
        // Absent availableDecisions: the full supported set renders.
        assertEquals(PendingApproval.DECISIONS, file.decisions)

        val other = obj("id" to 13, "method" to "item/permissions/requestApproval", "params" to obj())
        assertNull("未知交互类型不消费", PendingApproval.parse(other, 3))

        // An advertised list with only object-form decisions must yield NO renderable
        // buttons — presence of the field is not the same as absence.
        val objectOnly = obj("id" to 14, "method" to "item/commandExecution/requestApproval",
            "params" to obj("threadId" to "t1", "turnId" to "u1", "itemId" to "c3", "command" to "x",
                "availableDecisions" to org.json.JSONArray().put(obj("acceptWithExecpolicyAmendment" to obj()))))
        val unsupported = PendingApproval.parse(objectOnly, 3)!!
        assertTrue(unsupported.advertised)
        assertTrue(unsupported.decisions.isEmpty())
    }
}
