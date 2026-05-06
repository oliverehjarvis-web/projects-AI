package com.oli.projectsai.data.search

import com.oli.projectsai.data.preferences.SearchSettings
import com.oli.projectsai.net.HttpClient
import com.oli.projectsai.net.HttpError
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls a user-hosted SearXNG instance over its JSON API. The base URL is stored locally (e.g.
 * a Tailscale address) so nothing machine-specific lives in the repo. Unlimited, no API key.
 */
@Singleton
class WebSearchClient @Inject constructor(
    private val searchSettings: SearchSettings,
    private val httpClient: HttpClient
) {
    data class Result(val title: String, val url: String, val snippet: String)

    class MissingEndpoint : Exception("SearXNG URL is not configured in Settings.")

    suspend fun search(query: String, count: Int = 5): List<Result> {
        val base = searchSettings.searxngUrl.first()
        if (base.isBlank()) throw MissingEndpoint()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val endpoint = "$base/search?q=$encoded&format=json&safesearch=1"
        val body = try {
            httpClient.get(
                url = endpoint,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 15_000,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Accept-Encoding" to "identity",
                    "User-Agent" to "ProjectsAI/1.0"
                )
            )
        } catch (e: HttpError.Status) {
            throw Exception("SearXNG request failed (HTTP ${e.code}): ${e.body.take(200)}")
        }
        return parseResults(body, count)
    }

    private fun parseResults(body: String, count: Int): List<Result> {
        val json = JSONObject(body)
        val arr = json.optJSONArray("results") ?: return emptyList()
        val results = mutableListOf<Result>()
        var i = 0
        while (i < arr.length() && results.size < count) {
            val item = arr.optJSONObject(i)
            i++
            if (item == null) continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            results.add(
                Result(
                    title = item.optString("title").ifBlank { url },
                    url = url,
                    snippet = item.optString("content").trim()
                )
            )
        }
        return results
    }

    companion object {
        /** Collapses a result list into a short block the model can read without wasting tokens. */
        fun formatForPrompt(query: String, results: List<Result>): String {
            if (results.isEmpty()) {
                return "Search for \"$query\" returned no results."
            }
            return buildString {
                appendLine("Search results for \"$query\":")
                results.forEachIndexed { idx, r ->
                    appendLine()
                    appendLine("[${idx + 1}] ${r.title}")
                    appendLine("    ${r.url}")
                    if (r.snippet.isNotBlank()) {
                        appendLine("    ${r.snippet}")
                    }
                }
            }.trim()
        }
    }
}
