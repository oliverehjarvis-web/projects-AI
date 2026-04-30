package com.oli.projectsai.data.linkedin

import com.oli.projectsai.data.preferences.LinkedInSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw post returned by `/feed/fetch`. Pre-ranking — `LinkedInRepository` is what scores it
 * and turns it into a [com.oli.projectsai.data.db.entity.LinkedInSuggestion].
 */
data class RawFeedPost(
    val urn: String,
    val authorName: String,
    val authorHeadline: String,
    val text: String,
    val postUrl: String
)

class LinkedInNotConfigured : IllegalStateException("LinkedIn agent URL or token not set")

class LinkedInSessionExpired(message: String) : IOException(message)

class LinkedInHttpError(val code: Int, message: String) : IOException(message)

/**
 * Thin HTTP wrapper around the standalone linkedin-agent service. Mirrors the helper style in
 * SyncRepository (HttpURLConnection + bearer token) so the codebase stays consistent.
 */
@Singleton
class LinkedInClient @Inject constructor(
    private val settings: LinkedInSettings
) {

    suspend fun sessionHealth(): Boolean = withContext(Dispatchers.IO) {
        val (url, token) = require()
        val res = get("$url/session-health", token)
        res.optBoolean("logged_in", false)
    }

    suspend fun fetchFeed(maxPosts: Int): List<RawFeedPost> = withContext(Dispatchers.IO) {
        val (url, token) = require()
        val body = JSONObject().put("max_posts", maxPosts)
        val res = post("$url/feed/fetch", token, body)
        val posts = res.optJSONArray("posts") ?: JSONArray()
        List(posts.length()) { i ->
            val o = posts.getJSONObject(i)
            RawFeedPost(
                urn = o.getString("urn"),
                authorName = o.optString("author_name"),
                authorHeadline = o.optString("author_headline"),
                text = o.optString("text"),
                postUrl = o.optString("post_url")
            )
        }
    }

    suspend fun like(urn: String) {
        postAction(urn, "like", text = null)
    }

    suspend fun comment(urn: String, text: String) {
        postAction(urn, "comment", text = text)
    }

    private suspend fun postAction(urn: String, action: String, text: String?) =
        withContext(Dispatchers.IO) {
            val (url, token) = require()
            val body = JSONObject().put("urn", urn).put("action", action)
            if (text != null) body.put("text", text)
            post("$url/feed/post", token, body)
            Unit
        }

    private suspend fun require(): Pair<String, String> {
        val url = settings.agentUrl.first().trimEnd('/')
        val token = settings.agentToken.first()
        if (url.isBlank() || token.isBlank()) throw LinkedInNotConfigured()
        return url to token
    }

    private fun get(url: String, token: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            readJson(conn, "GET", url)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, token: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            // /feed/fetch and /feed/post drive a real headless browser on the other end —
            // give them headroom rather than failing at 30s.
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            readJson(conn, "POST", url)
        } finally {
            conn.disconnect()
        }
    }

    private fun readJson(conn: HttpURLConnection, method: String, url: String): JSONObject {
        val code = conn.responseCode
        if (code in 200..299) {
            return JSONObject(conn.inputStream.bufferedReader().readText())
        }
        val errBody = conn.errorStream?.bufferedReader()?.readText().orEmpty()
        if (code == 409) throw LinkedInSessionExpired(errBody.ifBlank { "Session expired" })
        throw LinkedInHttpError(code, "$method $url -> $code: ${errBody.take(300)}")
    }
}
