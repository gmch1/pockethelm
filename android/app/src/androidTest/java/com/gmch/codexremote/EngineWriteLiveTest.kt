package com.gmch.codexremote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gmch.codexremote.engines.zcode.ZcodeEngineClient
import com.gmch.codexremote.engines.contract.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.util.UUID

/** Opt-in restricted local relay; it owns naming, stopping and native deletion in finally.
 * No normal session/profile is modified and no paid turn runs without the private fixture. */
@RunWith(AndroidJUnit4::class)
class EngineWriteLiveTest {
    @Test fun startPlanFlashCreateSendAndReadUsingAndroidAdapter(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "engine-write-live.json")
        assumeTrue("Dedicated disposable live relay is required", file.exists())
        val config = JSONObject(file.readText()); file.delete()
        val socket = Socket("127.0.0.1", config.getInt("port"))
        val rpc = RpcClient(socket.getInputStream(), socket.getOutputStream(), {}, {})
        try {
            rpc.call("initialize", obj("clientId" to "pockethelm-engine-${UUID.randomUUID()}"))
            val client = ZcodeEngineClient(EngineOrigin("live-test", "zcode", "isolated"), rpc)
            val choice = client.choices().single { it.kind == "start-plan" && it.model == "GLM-5.3-Flash" && it.level == "low" }
            val project = client.projects().projects.single()
            val created = client.create(EngineCreateRequest(UUID.randomUUID().toString(), project.roots.single(), projectId = project.nativeId, choice = choice))
            val id = UUID.randomUUID().toString()
            val receipt = client.send(EngineMessage(created.session.ref, id, "[测试] Reply exactly ENGINE_OK. Do not use tools.", choice = choice))
            assertEquals(id, receipt.commandId)
            withTimeout(120_000) {
                while (true) {
                    val snapshot = client.inspect(created.session.ref)
                    if (snapshot.history.items.any { it.kind == "agentMessage" && it.text.contains("ENGINE_OK") }) break
                    delay(500)
                }
            }
        } finally { rpc.close(); socket.close() }
    }
}
