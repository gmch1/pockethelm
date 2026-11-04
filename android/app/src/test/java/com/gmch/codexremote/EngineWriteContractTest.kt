package com.gmch.codexremote

import com.gmch.codexremote.engines.codex.CodexEngineClient
import com.gmch.codexremote.engines.zcode.ZcodeEngineClient
import com.gmch.codexremote.engines.contract.*
import com.gmch.codexremote.transport.RpcEndpoint
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EngineWriteContractTest {
    private class Wire(val answer: (String, JSONObject) -> JSONObject) : RpcEndpoint {
        val methods = mutableListOf<String>()
        val params = mutableListOf<JSONObject>()
        override suspend fun call(method: String, params: JSONObject, timeout: Long, onResult: (suspend (JSONObject) -> Unit)?): JSONObject {
            methods += method; this.params += params
            return answer(method, params).also { onResult?.invoke(it) }
        }
    }
    private fun origin(id: String) = EngineOrigin("env", id, "one")
    private fun attached(runtime: String) = obj("approvalPolicy" to "never", "sandbox" to obj("type" to "dangerFullAccess"),
        "thread" to obj("id" to "s", "status" to obj("type" to runtime)))
    @Test fun codexRoutesByNativeStateAndPersistsBeforeWrite() = runBlocking {
        for ((runtime, delivery, method) in listOf(Triple("idle", MessageDelivery.Auto, "turn/start"),
            Triple("active", MessageDelivery.Queue, "thread/queue/add"), Triple("active", MessageDelivery.Steer, "turn/steer"))) {
            var persisted = false
            val wire = Wire { name, _ -> if (name == "thread/resume") attached(runtime) else {
                assertTrue(persisted); obj()
            } }
            val adapter = CodexEngineClient(origin("codex"), wire, "/work")
            val result = adapter.send(EngineMessage(adapter.session("s"), "command", "one", delivery, "run")) { persisted = true }
            assertEquals(listOf("thread/resume", method), wire.methods)
            assertEquals("command", result.commandId)
            assertEquals("command", wire.params.last().string("clientUserMessageId"))
        }
    }
    @Test fun codexCreatePersistsNameThenAppliesEffortUsingNativeSchema() = runBlocking {
        val wire = Wire { method, _ -> if (method == "thread/start") attached("idle") else obj() }
        val adapter = CodexEngineClient(origin("codex"), wire, "/work")
        adapter.create(EngineCreateRequest("c", "/work", "[测试] contract", choice = EngineChoice(adapter.origin, model = "cheap", level = "low")))
        assertEquals(listOf("thread/start", "thread/name/set", "thread/settings/update"), wire.methods)
        assertFalse(wire.params.first().has("reasoningEffort"))
        assertEquals("paginated", wire.params.first().string("historyMode"))
        assertEquals("low", wire.params.last().string("effort"))
    }
    @Test fun partialCreateReturnsKnownSessionForManualRecovery() = runBlocking {
        val wire = Wire { method, _ -> if (method == "thread/start") attached("idle") else throw IOException("lost") }
        val adapter = CodexEngineClient(origin("codex"), wire, "/work")
        val error = runCatching { adapter.create(EngineCreateRequest("c", "/work")) }.exceptionOrNull()
        assertTrue(error is EngineCreationUnconfirmed)
        assertEquals(adapter.session("s"), (error as EngineCreationUnconfirmed).session)
        assertEquals(2, wire.methods.size)
    }
    @Test fun zcodeUsesServerDeliveryAndRejectsForeignAcknowledgementWithoutRetry() = runBlocking {
        var foreign = false
        val wire = Wire { _, _ -> obj("commandId" to if (foreign) "other" else "c", "status" to "accepted", "result" to obj("delivery" to "queue")) }
        val adapter = ZcodeEngineClient(origin("zcode"), wire)
        val ref = EngineSessionRef(adapter.origin, "s", "/work")
        val choice = EngineChoice(adapter.origin, "plan", "flash", guard = "guard")
        val message = EngineMessage(ref, "c", "one", MessageDelivery.Steer, choice = choice)
        assertEquals(ReceiptDelivery.Queued, adapter.send(message).delivery)
        foreign = true
        assertNotNull(runCatching { adapter.send(message) }.exceptionOrNull())
        assertEquals(2, wire.methods.size)
        assertEquals("guide", wire.params.first().string("delivery"))
    }
    @Test fun codexInspectAttachesBeforeReadingUnloadedHistory() = runBlocking {
        val wire = Wire { method, _ -> if (method == "thread/resume") attached("idle") else obj("data" to org.json.JSONArray()) }
        val adapter = CodexEngineClient(origin("codex"), wire, "/work")
        val snapshot = adapter.inspect(adapter.session("s"))
        assertEquals("thread/resume", wire.methods.first())
        assertEquals(false, snapshot.running)
        assertTrue(snapshot.history.items.isEmpty())
    }
    @Test fun invalidScopeAndAttachmentCannotDispatch() = runBlocking {
        val wire = Wire { _, _ -> error("No RPC expected") }
        val adapter = ZcodeEngineClient(origin("zcode"), wire)
        val ref = EngineSessionRef(adapter.origin, "s", "/work")
        val choice = EngineChoice(adapter.origin, "plan", "flash", guard = "guard")
        val message = EngineMessage(ref, "c", "one", choice = choice,
            attachments = listOf(EngineAttachment(ref.copy(nativeId = "foreign"), "ref", "a.jpg", "image/jpeg", 1)))
        assertNotNull(runCatching { adapter.send(message) }.exceptionOrNull())
        assertTrue(wire.methods.isEmpty())
    }
}
