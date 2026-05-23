package com.oli.projectsai.core.inference

import com.oli.projectsai.core.db.entity.MessageRole
import com.oli.projectsai.core.preferences.SearchDepth
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

/**
 * Hard ceiling on characters streamed inside a `<think>...</think>` block before we abort.
 * ~6 kB ≈ 1500 SentencePiece tokens, enough for legitimate multi-step deliberation but well
 * short of the loops we see thinking-capable models fall into when they keep re-checking the
 * standing instructions. Aborting beats letting the user wait minutes for nothing.
 */
internal const val THINK_BUDGET_CHARS = 6000

/**
 * Streaming detector that watches tokens emitted by the model, tracks cumulative characters
 * that fall inside `<think>...</think>` blocks, and signals when the per-turn budget has been
 * exceeded. Robust to tags that split across token boundaries — the trailing buffer keeps a
 * few characters around so a `<thi` + `nk>` split is still recognised.
 *
 * Stateful, single-use (one instance per generation). Not thread-safe.
 */
internal class ThinkBudgetTracker(private val maxChars: Int = THINK_BUDGET_CHARS) {
    private var inThink = false
    private var thinkChars = 0
    private val tail = StringBuilder()

    /** Returns true once cumulative thinking content has exceeded the budget. */
    fun observe(token: String): Boolean {
        for (ch in token) {
            tail.append(ch)
            if (!inThink) {
                if (tail.endsWith("<think>")) {
                    inThink = true
                    thinkChars = 0
                    tail.clear()
                } else if (tail.length > 7) {
                    tail.delete(0, tail.length - 7)
                }
            } else {
                thinkChars++
                if (tail.endsWith("</think>")) {
                    inThink = false
                    tail.clear()
                } else if (thinkChars > maxChars) {
                    return true
                } else if (tail.length > 8) {
                    tail.delete(0, tail.length - 8)
                }
            }
        }
        return false
    }
}

internal fun MessageRole.toWireRole(): String = when (this) {
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "assistant"
    MessageRole.SYSTEM -> "system"
}

/**
 * Selects the web-search tool instructions to fold into the system prompt, or "" when search
 * is off. Shared so the chat UI's token bar can account for the exact same block that the
 * generation path will send.
 */
internal fun toolInstructionsFor(webSearchEnabled: Boolean, depth: SearchDepth): String = when {
    !webSearchEnabled -> ""
    depth == SearchDepth.TOOL_LOOP -> TOOL_LOOP_INSTRUCTIONS
    else -> AUTO_FETCH_INSTRUCTIONS
}

/**
 * Composes the final system prompt sent to the model: the temporal block, the caller's base
 * prompt (global profile + project context + memory + any staged repo files), and the web-search
 * tool instructions when enabled.
 *
 * This is the single source of truth for system-prompt assembly. Both [GenerationController]
 * (to send) and the chat ViewModel (to size the token bar against what's actually sent) call it,
 * so the bar can never again disagree with reality.
 */
internal fun composeSystemPrompt(base: String, toolInstructions: String): String = buildList {
    add(currentTemporalContext())
    if (base.isNotBlank()) add(base)
    if (toolInstructions.isNotBlank()) add(toolInstructions)
}.joinToString("\n\n")

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
