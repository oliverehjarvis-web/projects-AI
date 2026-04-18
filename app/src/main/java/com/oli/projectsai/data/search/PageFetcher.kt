package com.oli.projectsai.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a URL and extracts the main article text. Uses a coarse regex strip rather than a full
 * HTML parser — good enough for news/blog pages, cheap, and no extra dependency.
 */
@Singleton
class PageFetcher @Inject constructor() {

    suspend fun fetch(url: String, maxChars: Int = 2000): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile ProjectsAI")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return@withContext ""
            val contentType = conn.contentType.orEmpty()
            if (!contentType.contains("text/") && !contentType.contains("xhtml")) return@withContext ""
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            extractText(html).take(maxChars)
        } catch (t: Throwable) {
            ""
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(html: String): String {
        val dropped = html
            .replace(BLOCK_TAGS_REGEX, " ")
            .replace(COMMENT_REGEX, " ")
            .replace(TAG_REGEX, " ")
        return dropped
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private companion object {
        val BLOCK_TAGS_REGEX = Regex(
            "<(script|style|nav|header|footer|aside|form|noscript|svg)\\b[^>]*>.*?</\\1>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val COMMENT_REGEX = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val TAG_REGEX = Regex("<[^>]+>")
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
