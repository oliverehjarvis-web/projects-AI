package com.oli.projectsai.core.inference

import com.oli.projectsai.core.search.PageFetcher
import com.oli.projectsai.core.search.WebSearchClient
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search/fetch agent runs invoked by [GenerationController] when the project's
 * `web_search_enabled` flag is on. Lives outside the controller so the streaming-coordination
 * code stays clear of the multi-turn agent loop logic.
 *
 * Two strategies are supported, picked by the project's `searchDepth`:
 *
 * - [runAutoFetch] — first turn may emit `<search>query</search>`; if so, run the search,
 *   read the top two pages, then re-stream with the results stuffed into a follow-up user
 *   turn. One round, no recursion.
 * - [runToolLoop] — first turn may emit `<search>` or `<fetch>`; the result is fed back as a
 *   user turn and the model can keep going for [TOOL_LOOP_MAX_ROUNDS] rounds before being
 *   forced to answer.
 *
 * Both methods accept callbacks instead of mutating controller state directly:
 * - [onStreaming] mirrors partial responses to the UI
 * - [onSearchStatus] surfaces "Searching: …" / "Reading: …" hints during tool calls
 *
 * Returns true if the user cancelled mid-run.
 */
@Singleton
class AgentRunner @Inject constructor(
    private val inferenceManager: InferenceManager,
    private val webSearchClient: WebSearchClient,
    private val pageFetcher: PageFetcher,
) {
    suspend fun runAutoFetch(
        chatMessages: List<ChatMessage>,
        systemPromptText: String,
        fullResponse: StringBuilder,
        backendId: String?,
        config: GenerationConfig,
        onStreaming: (String) -> Unit,
        onSearchStatus: (String?) -> Unit,
    ): Boolean {
        val firstBuf = StringBuilder()
        var cancelled = try {
            inferenceManager.generate(
                systemPrompt = systemPromptText,
                messages = chatMessages,
                config = config,
                backendId = backendId,
            ).collect { token ->
                firstBuf.append(token)
                onStreaming(stripToolTags(firstBuf.toString()))
            }
            false
        } catch (ce: CancellationException) { true }

        val searchMatch = if (!cancelled) SEARCH_TAG_REGEX.find(firstBuf) else null
        if (searchMatch == null) {
            fullResponse.append(firstBuf)
            return cancelled
        }

        val query = searchMatch.groupValues[1].trim()
        onSearchStatus("Searching: $query")
        val results = try {
            webSearchClient.search(query, count = 5)
        } catch (t: Throwable) {
            onSearchStatus(null)
            val msg = "Search failed: ${t.message ?: "unknown error"}"
            fullResponse.append(msg)
            onStreaming(msg)
            return cancelled
        }

        val enriched = StringBuilder(WebSearchClient.formatForPrompt(query, results))
        results.take(2).forEachIndexed { idx, r ->
            onSearchStatus("Reading: ${r.title.take(40)}")
            val page = pageFetcher.fetch(r.url, maxChars = 2000)
            if (page.isNotBlank()) {
                enriched.append("\n\n--- Page [${idx + 1}] ${r.title} (${r.url}) ---\n")
                enriched.append(page)
            }
        }
        onSearchStatus(null)

        val continuation = chatMessages + listOf(
            ChatMessage(role = "assistant", content = "<search>$query</search>"),
            ChatMessage(
                role = "user",
                content = "$enriched\n\nUse these to answer my previous question. " +
                    "Do not call <search> again.",
            ),
        )
        onStreaming("")
        cancelled = try {
            inferenceManager.generate(
                systemPrompt = systemPromptText,
                messages = continuation,
                // Preamble already applied to the first turn; skip it on the follow-up.
                config = config.copy(applyDefaultPreamble = false),
                backendId = backendId,
            ).collect { token ->
                fullResponse.append(token)
                onStreaming(fullResponse.toString())
            }
            cancelled
        } catch (ce: CancellationException) { true }
        return cancelled
    }

    suspend fun runToolLoop(
        chatMessages: List<ChatMessage>,
        systemPromptText: String,
        fullResponse: StringBuilder,
        backendId: String?,
        config: GenerationConfig,
        onStreaming: (String) -> Unit,
        onSearchStatus: (String?) -> Unit,
    ): Boolean {
        var conversation = chatMessages
        repeat(TOOL_LOOP_MAX_ROUNDS) { round ->
            val buf = StringBuilder()
            // Only apply the preamble on the first round; subsequent rounds are continuations.
            val roundConfig = if (round == 0) config else config.copy(applyDefaultPreamble = false)
            val cancelled = try {
                inferenceManager.generate(
                    systemPrompt = systemPromptText,
                    messages = conversation,
                    config = roundConfig,
                    backendId = backendId,
                ).collect { token ->
                    buf.append(token)
                    onStreaming(stripToolTags(buf.toString()))
                }
                false
            } catch (ce: CancellationException) { true }

            if (cancelled) {
                fullResponse.append(stripToolTags(buf.toString()))
                return true
            }

            val text = buf.toString()
            val searchMatch = SEARCH_TAG_REGEX.find(text)
            val fetchMatch = FETCH_TAG_REGEX.find(text)
            val firstTool = listOfNotNull(searchMatch, fetchMatch).minByOrNull { it.range.first }

            if (firstTool == null) {
                fullResponse.append(text)
                return false
            }

            val isLastRound = round == TOOL_LOOP_MAX_ROUNDS - 1
            val toolResultText = when (firstTool) {
                searchMatch -> {
                    val query = firstTool.groupValues[1].trim()
                    onSearchStatus("Searching: $query")
                    try {
                        val results = webSearchClient.search(query)
                        WebSearchClient.formatForPrompt(query, results)
                    } catch (t: Throwable) {
                        "Search failed: ${t.message ?: "unknown error"}"
                    } finally {
                        onSearchStatus(null)
                    }
                }
                fetchMatch -> {
                    val url = firstTool.groupValues[1].trim()
                    onSearchStatus("Reading: ${url.take(50)}")
                    val page = pageFetcher.fetch(url, maxChars = 3000)
                    onSearchStatus(null)
                    if (page.isBlank()) "Fetch for $url returned no readable content."
                    else "Page content for $url:\n\n$page"
                }
                else -> ""
            }

            val closingHint = if (isLastRound)
                "\n\nYou've used the maximum number of tool calls. Answer now without any more tags."
            else ""

            conversation = conversation + listOf(
                ChatMessage(role = "assistant", content = firstTool.value),
                ChatMessage(role = "user", content = toolResultText + closingHint),
            )
            onStreaming("")
        }
        return false
    }
}
