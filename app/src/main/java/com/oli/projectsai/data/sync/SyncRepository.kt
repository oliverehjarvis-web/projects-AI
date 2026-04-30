package com.oli.projectsai.data.sync

import com.oli.projectsai.data.db.dao.ChatDao
import com.oli.projectsai.data.db.dao.MessageDao
import com.oli.projectsai.data.db.dao.ProjectDao
import com.oli.projectsai.data.db.dao.QuickActionDao
import com.oli.projectsai.data.db.entity.Chat
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.data.db.entity.PreferredBackend
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.db.entity.QuickAction
import com.oli.projectsai.data.preferences.RemoteSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncResult {
    data object Success : SyncResult()
    data class Error(val message: String) : SyncResult()
    data object Skipped : SyncResult()
}

@Singleton
class SyncRepository @Inject constructor(
    private val remoteSettings: RemoteSettings,
    private val projectDao: ProjectDao,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val quickActionDao: QuickActionDao
) {
    suspend fun syncNow(): SyncResult = withContext(Dispatchers.IO) {
        val serverUrl = remoteSettings.serverUrl.first().trimEnd('/')
        val apiToken = remoteSettings.apiToken.first()
        if (serverUrl.isBlank() || apiToken.isBlank()) return@withContext SyncResult.Skipped

        val lastSyncAt = remoteSettings.lastSyncAt.first()
        val now = System.currentTimeMillis()

        return@withContext try {
            // 1. Pull everything changed since last sync
            val fullJson = get("$serverUrl/v1/sync/full?since=$lastSyncAt", apiToken)
            applyPull(fullJson)

            // 2. Push local changes
            pushProjects(serverUrl, apiToken, lastSyncAt)
            pushChats(serverUrl, apiToken, lastSyncAt)
            pushMessages(serverUrl, apiToken, lastSyncAt)
            pushQuickActions(serverUrl, apiToken, lastSyncAt)

            remoteSettings.setLastSyncAt(now)
            SyncResult.Success
        } catch (t: Throwable) {
            SyncResult.Error(t.message ?: "Sync failed")
        }
    }

    // ── Pull ─────────────────────────────────────────────────────────────────

    private suspend fun applyPull(json: JSONObject) {
        applyProjects(json.optJSONArray("projects") ?: JSONArray())
        applyChats(json.optJSONArray("chats") ?: JSONArray())
        applyMessages(json.optJSONArray("messages") ?: JSONArray())
        applyQuickActions(json.optJSONArray("quick_actions") ?: JSONArray())
    }

    private suspend fun applyProjects(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val remoteId = item.getString("remote_id")
            val updatedAt = item.getLong("updated_at")
            val deletedAt = if (item.isNull("deleted_at")) null else item.getLong("deleted_at")
            val existing = projectDao.getByRemoteId(remoteId)
            if (existing == null) {
                projectDao.insert(
                    Project(
                        remoteId = remoteId,
                        name = item.getString("name"),
                        description = item.optString("description"),
                        manualContext = item.optString("manual_context"),
                        accumulatedMemory = item.optString("accumulated_memory"),
                        isSecret = item.optBoolean("is_secret"),
                        preferredBackend = if (item.optString("preferred_backend") == "REMOTE")
                            PreferredBackend.REMOTE else PreferredBackend.LOCAL,
                        createdAt = item.getLong("created_at"),
                        updatedAt = updatedAt,
                        deletedAt = deletedAt
                    )
                )
            } else if (updatedAt > existing.updatedAt) {
                projectDao.update(
                    existing.copy(
                        name = item.getString("name"),
                        description = item.optString("description"),
                        manualContext = item.optString("manual_context"),
                        accumulatedMemory = item.optString("accumulated_memory"),
                        isSecret = item.optBoolean("is_secret"),
                        preferredBackend = if (item.optString("preferred_backend") == "REMOTE")
                            PreferredBackend.REMOTE else PreferredBackend.LOCAL,
                        updatedAt = updatedAt,
                        deletedAt = deletedAt
                    )
                )
            }
        }
    }

    private suspend fun applyChats(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val remoteId = item.getString("remote_id")
            val projectRemoteId = item.getString("project_remote_id")
            val project = projectDao.getByRemoteId(projectRemoteId) ?: continue
            val updatedAt = item.getLong("updated_at")
            val deletedAt = if (item.isNull("deleted_at")) null else item.getLong("deleted_at")
            val existing = chatDao.getByRemoteId(remoteId)
            if (existing == null) {
                chatDao.insert(
                    Chat(
                        remoteId = remoteId,
                        projectId = project.id,
                        title = item.optString("title", "New Chat"),
                        webSearchEnabled = item.optBoolean("web_search_enabled"),
                        createdAt = item.getLong("created_at"),
                        updatedAt = updatedAt,
                        deletedAt = deletedAt
                    )
                )
            } else if (updatedAt > existing.updatedAt) {
                chatDao.update(
                    existing.copy(
                        title = item.optString("title", "New Chat"),
                        webSearchEnabled = item.optBoolean("web_search_enabled"),
                        updatedAt = updatedAt,
                        deletedAt = deletedAt
                    )
                )
            }
        }
    }

    private suspend fun applyMessages(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val remoteId = item.getString("remote_id")
            if (messageDao.getByRemoteId(remoteId) != null) continue
            val chatRemoteId = item.getString("chat_remote_id")
            val chat = chatDao.getByRemoteId(chatRemoteId) ?: continue
            val deletedAt = if (item.isNull("deleted_at")) null else item.getLong("deleted_at")
            val role = when (item.optString("role")) {
                "assistant", "model" -> MessageRole.ASSISTANT
                "system" -> MessageRole.SYSTEM
                else -> MessageRole.USER
            }
            messageDao.insert(
                Message(
                    remoteId = remoteId,
                    chatId = chat.id,
                    role = role,
                    content = item.getString("content"),
                    tokenCount = item.optInt("token_count"),
                    createdAt = item.getLong("created_at"),
                    updatedAt = item.optLong("updated_at", item.getLong("created_at")),
                    deletedAt = deletedAt
                )
            )
        }
    }

    private suspend fun applyQuickActions(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val remoteId = item.getString("remote_id")
            if (quickActionDao.getByRemoteId(remoteId) != null) continue
            val projectRemoteId = item.getString("project_remote_id")
            val project = projectDao.getByRemoteId(projectRemoteId) ?: continue
            val deletedAt = if (item.isNull("deleted_at")) null else item.getLong("deleted_at")
            quickActionDao.insert(
                QuickAction(
                    remoteId = remoteId,
                    projectId = project.id,
                    name = item.getString("name"),
                    promptTemplate = item.getString("prompt_template"),
                    sortOrder = item.optInt("sort_order"),
                    createdAt = item.getLong("created_at"),
                    updatedAt = item.optLong("updated_at", item.getLong("created_at")),
                    deletedAt = deletedAt
                )
            )
        }
    }

    // ── Push ──────────────────────────────────────────────────────────────────

    private suspend fun pushProjects(serverUrl: String, apiToken: String, since: Long) {
        val items = projectDao.getAllForSync()
            .filter { it.remoteId == null || it.updatedAt > since }
        if (items.isEmpty()) return
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject().apply {
                put("remote_id", p.remoteId)
                put("name", p.name)
                put("description", p.description)
                put("manual_context", p.manualContext)
                put("accumulated_memory", p.accumulatedMemory)
                put("pinned_memories", p.pinnedMemories.joinToString("\u001F"))
                put("preferred_backend", p.preferredBackend.name)
                put("memory_token_limit", p.memoryTokenLimit)
                put("context_length", p.contextLength)
                put("is_secret", p.isSecret)
                put("created_at", p.createdAt)
                put("updated_at", p.updatedAt)
                if (p.deletedAt != null) put("deleted_at", p.deletedAt) else put("deleted_at", JSONObject.NULL)
            })
        }
        val response = put("$serverUrl/v1/sync/projects", apiToken, JSONObject().put("items", arr))
        val remoteIds = response.optJSONArray("remote_ids") ?: return
        items.forEachIndexed { idx, p ->
            val rid = remoteIds.optString(idx)
            if (rid.isNotBlank() && p.remoteId != rid) projectDao.updateRemoteId(p.id, rid)
        }
    }

    private suspend fun pushChats(serverUrl: String, apiToken: String, since: Long) {
        val items = chatDao.getAllForSync()
            .filter { it.remoteId == null || it.updatedAt > since }
        if (items.isEmpty()) return
        val projectRemoteIdMap = projectDao.getAllForSync().associate { it.id to it.remoteId }
        val arr = JSONArray()
        val eligible = items.filter { projectRemoteIdMap[it.projectId] != null }
        eligible.forEach { c ->
            arr.put(JSONObject().apply {
                put("remote_id", c.remoteId)
                put("project_remote_id", projectRemoteIdMap[c.projectId])
                put("title", c.title)
                put("web_search_enabled", c.webSearchEnabled)
                put("created_at", c.createdAt)
                put("updated_at", c.updatedAt)
                if (c.deletedAt != null) put("deleted_at", c.deletedAt) else put("deleted_at", JSONObject.NULL)
            })
        }
        if (arr.length() == 0) return
        val response = put("$serverUrl/v1/sync/chats", apiToken, JSONObject().put("items", arr))
        val remoteIds = response.optJSONArray("remote_ids") ?: return
        eligible.forEachIndexed { idx, c ->
            val rid = remoteIds.optString(idx)
            if (rid.isNotBlank() && c.remoteId != rid) chatDao.updateRemoteId(c.id, rid)
        }
    }

    private suspend fun pushMessages(serverUrl: String, apiToken: String, since: Long) {
        val items = messageDao.getAllForSync()
            .filter { it.remoteId == null || it.updatedAt > since }
        if (items.isEmpty()) return
        val chatRemoteIdMap = chatDao.getAllForSync().associate { it.id to it.remoteId }
        val arr = JSONArray()
        val eligible = items.filter { chatRemoteIdMap[it.chatId] != null }
        eligible.forEach { m ->
            arr.put(JSONObject().apply {
                put("remote_id", m.remoteId)
                put("chat_remote_id", chatRemoteIdMap[m.chatId])
                put("role", m.role.name.lowercase())
                put("content", m.content)
                put("token_count", m.tokenCount)
                put("created_at", m.createdAt)
                put("updated_at", m.updatedAt)
                if (m.deletedAt != null) put("deleted_at", m.deletedAt) else put("deleted_at", JSONObject.NULL)
            })
        }
        if (arr.length() == 0) return
        val response = put("$serverUrl/v1/sync/messages", apiToken, JSONObject().put("items", arr))
        val remoteIds = response.optJSONArray("remote_ids") ?: return
        eligible.forEachIndexed { idx, m ->
            val rid = remoteIds.optString(idx)
            if (rid.isNotBlank() && m.remoteId != rid) messageDao.updateRemoteId(m.id, rid)
        }
    }

    private suspend fun pushQuickActions(serverUrl: String, apiToken: String, since: Long) {
        val items = quickActionDao.getAllForSync()
            .filter { it.remoteId == null || it.updatedAt > since }
        if (items.isEmpty()) return
        val projectRemoteIdMap = projectDao.getAllForSync().associate { it.id to it.remoteId }
        val arr = JSONArray()
        val eligible = items.filter { projectRemoteIdMap[it.projectId] != null }
        eligible.forEach { qa ->
            arr.put(JSONObject().apply {
                put("remote_id", qa.remoteId)
                put("project_remote_id", projectRemoteIdMap[qa.projectId])
                put("name", qa.name)
                put("prompt_template", qa.promptTemplate)
                put("sort_order", qa.sortOrder)
                put("created_at", qa.createdAt)
                put("updated_at", qa.updatedAt)
                if (qa.deletedAt != null) put("deleted_at", qa.deletedAt) else put("deleted_at", JSONObject.NULL)
            })
        }
        if (arr.length() == 0) return
        val response = put("$serverUrl/v1/sync/quick_actions", apiToken, JSONObject().put("items", arr))
        val remoteIds = response.optJSONArray("remote_ids") ?: return
        eligible.forEachIndexed { idx, qa ->
            val rid = remoteIds.optString(idx)
            if (rid.isNotBlank() && qa.remoteId != rid) quickActionDao.updateRemoteId(qa.id, rid)
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun get(url: String, token: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            if (conn.responseCode != 200) throw serverError("GET", url, conn)
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun put(url: String, token: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode != 200) throw serverError("PUT", url, conn)
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun serverError(method: String, url: String, conn: HttpURLConnection): IllegalStateException {
        val code = conn.responseCode
        val detail = runCatching {
            val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (raw.isBlank()) null
            else runCatching { JSONObject(raw).optString("error").ifBlank { raw } }.getOrDefault(raw)
        }.getOrNull()
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " — ${it.take(200)}" }.orEmpty()
        return IllegalStateException("$method $url → HTTP $code$suffix")
    }
}
