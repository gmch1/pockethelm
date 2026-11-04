package com.gmch.codexremote.data

import android.content.Context
import com.gmch.codexremote.*
import com.gmch.codexremote.application.EngineSessionStore
import com.gmch.codexremote.engines.contract.*
import org.json.JSONObject
import java.security.MessageDigest

/** New engines use a versioned, encrypted, per-origin blob. Existing Codex/ZCode
 * UI receipts retain their legacy codecs and are not silently migrated or deleted. */
internal class VaultEngineSessionStore(context: Context) : EngineSessionStore {
    private val vault = Vault(context)
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun origin(o: EngineOrigin) = obj("environment" to o.environment, "engine" to o.engine, "instance" to o.instance)
    private fun tuple(vararg fields: String) = fields.joinToString("") { "${it.length}:$it" }
    private fun key(o: EngineOrigin) = "engine-session-v1:" + digest(tuple(o.environment, o.engine, o.instance))
    private fun refKey(r: EngineSessionRef) = digest(tuple(r.nativeId, r.workspacePath, r.workspaceIdentity))
    private fun ref(r: EngineSessionRef) = obj("id" to r.nativeId, "path" to r.workspacePath, "identity" to r.workspaceIdentity)
    private fun ref(o: EngineOrigin, j: JSONObject) = EngineSessionRef(o, j.getString("id"), j.string("path"), j.string("identity"))
    private fun load(o: EngineOrigin): JSONObject {
        val raw = vault.get(key(o)) ?: return obj("version" to 1, "origin" to origin(o), "drafts" to obj(), "pending" to obj())
        val value = JSONObject(raw)
        require(value.getInt("version") == 1 && value.getJSONObject("origin").let { it.getString("environment") == o.environment && it.getString("engine") == o.engine && it.getString("instance") == o.instance }) { "会话存储版本或来源不匹配" }
        return value
    }
    private fun save(o: EngineOrigin, value: JSONObject) {
        val raw = value.toString()
        check(raw.toByteArray().size <= 2 * 1024 * 1024) { "本地会话记录过大，请先核对待确认操作" }
        vault.put(key(o), raw)
    }
    override fun draft(session: EngineSessionRef): String = synchronized(lock) { load(session.origin).getJSONObject("drafts").string(refKey(session)) }
    override fun saveDraft(session: EngineSessionRef, text: String) = synchronized(lock) {
        val value = load(session.origin); val drafts = value.getJSONObject("drafts"); val id = refKey(session)
        if (text.isEmpty()) drafts.remove(id) else drafts.put(id, text)
        save(session.origin, value)
    }
    override fun pending(origin: EngineOrigin): List<PendingOperation> = synchronized(lock) {
        val values = load(origin).getJSONObject("pending")
        values.keys().asSequence().map { id ->
            val j = values.getJSONObject(id)
            PendingOperation(origin, id, PendingKind.valueOf(j.getString("kind")), j.optJSONObject("session")?.let { ref(origin, it) },
                j.string("workspace"), j.string("project"), j.string("text"))
        }.toList()
    }
    override fun savePending(operation: PendingOperation) = synchronized(lock) {
        require(operation.session == null || operation.session.origin == operation.origin)
        val value = load(operation.origin); val pending = value.getJSONObject("pending")
        check(pending.has(operation.id) || pending.length() < 128) { "待确认记录过多，请先核对" }
        pending.put(operation.id, obj("kind" to operation.kind.name, "session" to operation.session?.let(::ref),
            "workspace" to operation.workspacePath, "project" to operation.projectId, "text" to operation.text))
        save(operation.origin, value)
    }
    override fun clearPending(origin: EngineOrigin, commandId: String) = synchronized(lock) {
        val value = load(origin); value.getJSONObject("pending").remove(commandId); save(origin, value)
    }
    companion object { private val lock = Any() }
}
