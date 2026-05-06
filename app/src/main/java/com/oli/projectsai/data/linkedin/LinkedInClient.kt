package com.oli.projectsai.data.linkedin

import com.oli.projectsai.data.preferences.LinkedInSettings
import com.oli.projectsai.net.HttpClient
import com.oli.projectsai.net.HttpError
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
 * Thin HTTP wrapper around the standalone linkedin-agent service.
 */
@Singleton
class LinkedInClient @Inject constructor(
    private val settings: LinkedInSettings,
    private val httpClient: HttpClient
) {

    suspend fun sessionHealth(): Boolean {
        val (url, token) = require()
        val res = getJson("$url/session-health", token)
        return res.optBoolean("logged_in", false)
    }

    suspend fun fetchFeed(maxPosts: Int): List<RawFeedPost> {
        val (url, token) = require()
        val body = JSONObject().put("max_posts", maxPosts)
        val res = postJson("$url/feed/fetch", token, body)
        val posts = res.optJSONArray("posts") ?: JSONArray()
        return List(posts.length()) { i ->
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

    private suspend fun postAction(urn: String, action: String, text: String?) {
        val (url, token) = require()
        val body = JSONObject().put("urn", urn).put("action", action)
        if (text != null) body.put("text", text)
        postJson("$url/feed/post", token, body)
    }

    private suspend fun require(): Pair<String, String> {
        val url = settings.agentUrl.first().trimEnd('/')
        val token = settings.agentToken.first()
        if (url.isBlank() || token.isBlank()) throw LinkedInNotConfigured()
        return url to token
    }

    private suspend fun getJson(url: String, token: String): JSONObject = try {
        JSONObject(
            httpClient.get(
                url = url,
                bearer = token,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 60_000
            )
        )
    } catch (e: HttpError.Status) {
        throw mapError("GET", url, e)
    }

    private suspend fun postJson(url: String, token: String, body: JSONObject): JSONObject = try {
        JSONObject(
            httpClient.postJson(
                url = url,
                body = body.toString(),
                bearer = token,
                connectTimeoutMs = 15_000,
                // /feed/fetch and /feed/post drive a real headless browser on the other end —
                // give them headroom rather than failing at 30s.
                readTimeoutMs = 120_000
            )
        )
    } catch (e: HttpError.Status) {
        throw mapError("POST", url, e)
    }

    private fun mapError(method: String, url: String, e: HttpError.Status): IOException {
        if (e.code == 409) return LinkedInSessionExpired(e.body.ifBlank { "Session expired" })
        return LinkedInHttpError(e.code, "$method $url -> ${e.code}: ${e.body.take(300)}")
    }
}
