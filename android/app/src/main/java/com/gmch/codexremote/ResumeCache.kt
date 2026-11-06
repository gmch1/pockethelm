package com.gmch.codexremote

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Include the provisioned environment and root, not just an SSH endpoint reused by another host.
internal val ConnectionProfile.resumeIdentity: String get() = "$identity|$environmentId|$root"
internal const val BACKGROUND_GRACE_MS = 30_000L

/** Read-only presentation data. No connection, execution ID, quota, queue or confirmed settings. */
internal data class ResumeSnapshot(
    val environment: String, val tab: Int = 0,
    val sessions: List<SessionInfo> = emptyList(), val projects: List<String> = emptyList(),
    val selected: SessionInfo? = null, val items: List<TimelineItem> = emptyList(),
    val zSessions: List<ZSession> = emptyList(), val zProjects: List<ZProject> = emptyList(),
    val zSelected: ZSession? = null, val choice: ZSelection? = null, val guard: String = ""
) {
    fun json(): JSONObject = obj("version" to 1, "environment" to environment, "tab" to tab,
        "sessions" to JSONArray(sessions.take(60).map(::sessionJson)), "projects" to JSONArray(projects.take(100)),
        "selected" to selected?.let(::sessionJson),
        "items" to JSONArray(boundedItems(items).map { obj("id" to it.id, "kind" to it.kind, "text" to it.text,
            "status" to it.status, "clientId" to it.clientId, "entityId" to it.entityId, "timestampMs" to it.timestampMs) }),
        "zSessions" to JSONArray(zSessions.take(100).map(::zSessionJson)),
        "zProjects" to JSONArray(zProjects.take(100).map { obj("id" to it.id, "name" to it.name, "roots" to JSONArray(it.roots)) }),
        "zSelected" to zSelected?.let(::zSessionJson), "choice" to choice?.json(), "guard" to guard)

    companion object {
        fun codex(s: UiState) = ResumeSnapshot(s.profile.resumeIdentity, s.tab, s.sessions, s.projects, s.selected, s.items)
        fun zcode(s: ZcodeState, profile: ConnectionProfile) = ResumeSnapshot(profile.resumeIdentity,
            items = s.items, zSessions = s.sessions, zProjects = s.projects, zSelected = s.selected,
            choice = s.selection, guard = s.selectionGuard)
        private fun sessionJson(s: SessionInfo) = obj("id" to s.id, "name" to s.title, "cwd" to s.cwd,
            "preview" to s.preview.take(500), "status" to obj("type" to "notLoaded"), "updatedAt" to s.updatedAt, "model" to s.model)
        private fun zSessionJson(s: ZSession) = obj("taskId" to s.id, "title" to s.title, "workspacePath" to s.path,
            "workspaceIdentity" to s.identity, "projectId" to s.project, "updatedAt" to s.updatedAt)
        internal fun boundedItems(items: List<TimelineItem>): List<TimelineItem> {
            var remaining = 240_000
            return items.takeLast(80).asReversed().mapNotNull { item ->
                if (remaining <= 0) null else {
                    val limit = minOf(12_000, remaining)
                    val text = if (item.text.length > limit) item.text.take(limit) + "\n[本地预览已截断，联网后更新]" else item.text
                    remaining -= text.length
                    item.copy(text = text)
                }
            }.asReversed()
        }
        fun parse(j: JSONObject): ResumeSnapshot {
            require(j.optInt("version") == 1)
            return ResumeSnapshot(j.getString("environment"), j.optInt("tab").coerceIn(0, 2),
                j.optJSONArray("sessions").objects().take(60).map(SessionInfo::parse), j.optJSONArray("projects").strings().take(100),
                j.optJSONObject("selected")?.let(SessionInfo::parse),
                j.optJSONArray("items").objects().takeLast(80).map { TimelineItem(it.getString("id"), it.getString("kind"), it.getString("text"), it.string("status"), it.string("clientId"), it.string("entityId"), messageTimestamp(it.opt("timestampMs"))) },
                j.optJSONArray("zSessions").objects().take(100).map(ZSession::parse),
                j.optJSONArray("zProjects").objects().take(100).map { ZProject(it.getString("id"), it.getString("name"), it.optJSONArray("roots").strings()) },
                j.optJSONObject("zSelected")?.let(ZSession::parse), ZSelection.parse(j.optJSONObject("choice")), j.string("guard"))
        }
    }
}

/** One bounded encrypted snapshot per engine; no Android backup, plaintext temp file or transcript archive. */
internal class ResumeCache(context: Context, engine: String) {
    private val vault = Vault(context)
    private val name = "resume-v1-$engine"
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.bin"))
    suspend fun read(environment: String): ResumeSnapshot? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                if (!file.baseFile.exists() || file.baseFile.length() > MAX_BYTES ||
                    System.currentTimeMillis() - file.baseFile.lastModified() > 7 * 86_400_000L) return@synchronized null
                val snapshot = ResumeSnapshot.parse(JSONObject(vault.decrypt(name, file.readFully()).toString(Charsets.UTF_8)))
                snapshot.takeIf { it.environment == environment }
            }.getOrNull() // Corrupt/expired caches must never block authentication or reconnect.
        }
    }
    suspend fun write(snapshot: ResumeSnapshot, observedAt: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            // A finishing write from a destroyed Activity must not replace a newer instance's snapshot.
            if (file.baseFile.lastModified() > observedAt) return@synchronized
            val bytes = vault.encrypt(name, snapshot.json().toString().toByteArray(Charsets.UTF_8))
            require(bytes.size <= MAX_BYTES)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (t: Throwable) { file.failWrite(output); throw t }
        }
    }
    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 2 * 1024 * 1024L
    }
}
