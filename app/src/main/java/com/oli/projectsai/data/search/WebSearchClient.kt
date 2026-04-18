package com.oli.projectsai.data.search

import com.oli.projectsai.data.preferences.SearchSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the Brave Search API with the user's key (stored locally). Returns snippet-sized results
 * that the model can consume without blowing the context budget.
 */
@Singleton
class WebSearchClient @Inject constructor(
    private val searchSettings: SearchSettings
) {
    data class Result(val title: String, val url: String, val snippet: String)

    class MissingApiKey : Exception("Brave API key is not configured in Settings.")

    suspend fun search(query: String, count: Int = 5): List<Result> = withContext(Dispatchers.IO) {
        val key = searchSettings.braveApiKey.first()
        if (key.isBlank()) throw MissingApiKey()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val endpoint = "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$count"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("X-Subscription-Token", key)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw Exception("Brave search failed (HTTP $code): ${body.take(200)}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseResults(body, count)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResults(body: String, count: Int): List<Result> {
        val json = JSONObject(body)
        val web = json.optJSONObject("web") ?: return emptyList()
        val arr = web.optJSONArray("results") ?: return emptyList()
        val results = mutableListOf<Result>()
        for (i in 0 until minOf(arr.length(), count)) {
            val item = arr.optJSONObject(i) ?: continue
            results.add(
                Result(
                    title = item.optString("title").ifBlank { item.optString("url") },
                    url = item.optString("url"),
                    snippet = item.optString("description").trim()
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
