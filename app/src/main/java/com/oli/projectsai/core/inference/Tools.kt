package com.oli.projectsai.core.inference

import com.oli.projectsai.core.db.entity.MessageRole
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure helpers used by the generation pipeline:
 *
 * - Regex constants and tag stripping for the search/fetch agent tools
 * - The AUTO_FETCH and TOOL_LOOP instruction blocks that get folded into the system prompt
 *   when web search is enabled
 * - The temporal-context block that's prepended to every system prompt
 * - The DB → wire format role mapping
 *
 * Lifted out of GenerationController so the orchestration code stays focused on coordinating
 * the inference backend, error mapping, and persistence side effects.
 */

internal val SEARCH_TAG_REGEX = Regex("<search>(.*?)</search>", RegexOption.DOT_MATCHES_ALL)
internal val FETCH_TAG_REGEX = Regex("<fetch>(.*?)</fetch>", RegexOption.DOT_MATCHES_ALL)
internal const val TOOL_LOOP_MAX_ROUNDS = 4
internal const val SLOW_RESPONSE_THRESHOLD_MS = 30_000L

internal fun MessageRole.toWireRole(): String = when (this) {
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
    MessageRole.SYSTEM -> "system"
}

internal fun currentTemporalContext(): String {
    val now = ZonedDateTime.now()
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
    val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    return "<date_time>\n- Date: $date\n- Time: $time\n- Timezone: ${now.zone.id}\n</date_time>"
}

/** Cuts the response at the first tool tag so partial streams don't render half-typed XML. */
internal fun stripToolTags(text: String): String {
    val sIdx = text.indexOf("<search>")
    val fIdx = text.indexOf("<fetch>")
    val cut = listOf(sIdx, fIdx).filter { it >= 0 }.minOrNull() ?: return text
    return text.substring(0, cut)
}

internal val AUTO_FETCH_INSTRUCTIONS = """
You have access to a web search tool. When the user's question needs current or specific
information you don't already know (news, dates, stats, recent events, specific facts),
respond with exactly:

<search>your concise search query</search>

and nothing else on that turn. You will receive search results AND the full text of the
top pages, then give your final answer using them.

If you can answer from what you already know, answer directly — do not use <search> tags
in normal answers. Only use the tag when you would otherwise need to look something up.
""".trimIndent()

internal val TOOL_LOOP_INSTRUCTIONS = """
You have two tools:

1. <search>your query</search> — runs a web search and returns snippet-style results.
2. <fetch>https://example.com/page</fetch> — downloads a specific URL and returns its
   main text (use this on a URL from a previous search result to read it in full).

When you use a tool, emit exactly the tag and nothing else on that turn; you will then
receive the tool output and can either call another tool or give your final answer. A
typical pattern: search first, pick the best URL from the results, fetch it for detail,
then answer.

If you can answer from what you already know, answer directly — do not emit tool tags in
normal answers. You have a limited number of tool calls per question, so use them only
when you genuinely need fresh information.
""".trimIndent()
