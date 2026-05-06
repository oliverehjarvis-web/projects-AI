package com.oli.projectsai.inference

import com.oli.projectsai.data.attachments.AttachmentStore
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.data.preferences.SearchDepth
import com.oli.projectsai.data.preferences.SearchSettings
import com.oli.projectsai.data.repository.ChatRepository
import com.oli.projectsai.data.search.PageFetcher
import com.oli.projectsai.data.search.WebSearchClient
import com.oli.projectsai.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class ChatError(val message: String, val retryable: Boolean)

data class ActiveGeneration(
    val chatId: Long,
    val streamingContent: String,
    val isGenerating: Boolean,
    val searchStatus: String?,
    val titleHint: String
)

@Singleton
class GenerationController @Inject constructor(
    private val chatRepository: ChatRepository,
    private val inferenceManager: InferenceManager,
    private val attachmentStore: AttachmentStore,
    private val webSearchClient: WebSearchClient,
    private val pageFetcher: PageFetcher,
    private val searchSettings: SearchSettings,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val _activeGeneration = MutableStateFlow<ActiveGeneration?>(null)
    val activeGeneration: StateFlow<ActiveGeneration?> = _activeGeneration.asStateFlow()

    private val _errors = MutableStateFlow<Map<Long, ChatError>>(emptyMap())
    val errors: StateFlow<Map<Long, ChatError>> = _errors.asStateFlow()

    private data class LastFailed(val content: String?, val attachments: List<String>)
    private val lastFailed = mutableMapOf<Long, LastFailed>()

    private var job: Job? = null

    fun start(params: GenerationParams): Boolean {
        if (_activeGeneration.value != null) return false
        _activeGeneration.value = ActiveGeneration(
            chatId = params.chatId,
            streamingContent = "",
            isGenerating = true,
            searchStatus = null,
            titleHint = params.chatTitleHint
        )
        clearError(params.chatId)
        job = scope.launch { runGeneration(params) }
        return true
    }

    fun cancel() {
        job?.cancel(CancellationException("User cancelled generation"))
    }

    fun clearError(chatId: Long) {
        _errors.value = _errors.value - chatId
    }

    fun lastFailedFor(chatId: Long): Pair<String?, List<String>>? =
        lastFailed[chatId]?.let { it.content to it.attachments }

    private fun setError(chatId: Long, error: ChatError) {
        _errors.value = _errors.value + (chatId to error)
    }

    private fun updateStreaming(content: String) {
        _activeGeneration.value = _activeGeneration.value?.copy(streamingContent = content)
    }

    private fun updateSearchStatus(status: String?) {
        _activeGeneration.value = _activeGeneration.value?.copy(searchStatus = status)
    }

    private suspend fun runGeneration(params: GenerationParams) {
        val chatId = params.chatId
        val fullResponse = StringBuilder()
        var cancelled = false
        try {
            val msgs: List<Message> = chatRepository.getMessagesFlow(chatId).first()
            val dbMessages = msgs.mapIndexed { idx, msg ->
                val isLast = idx == msgs.lastIndex
                val bytes = if (isLast && msg.role == MessageRole.USER) {
                    msg.attachmentPaths.map { attachmentStore.readBytes(it) }
                } else emptyList()
                ChatMessage(
                    role = msg.role.toWireRole(),
                    content = msg.content,
                    imageBytes = bytes
                )
            }
            val chatMessages = if (params.currentUserContent != null &&
                dbMessages.lastOrNull()?.content != params.currentUserContent
            ) {
                val bytes = params.currentAttachments.map { attachmentStore.readBytes(it) }
                dbMessages + ChatMessage(
                    role = "user",
                    content = params.currentUserContent,
                    imageBytes = bytes
                )
            } else {
                dbMessages
            }

            val depth = if (params.webSearchEnabled) searchSettings.searchDepth.first() else SearchDepth.AUTO_FETCH
            val toolInstructions = when {
                !params.webSearchEnabled -> ""
                depth == SearchDepth.TOOL_LOOP -> TOOL_LOOP_INSTRUCTIONS
                else -> AUTO_FETCH_INSTRUCTIONS
            }
            val effectiveSystemPrompt = buildList {
                add(currentTemporalContext())
                if (params.systemPrompt.isNotBlank()) add(params.systemPrompt)
                if (toolInstructions.isNotBlank()) add(toolInstructions)
            }.joinToString("\n\n")

            val genConfig = GenerationConfig(
                maxOutputTokens = params.maxOutputTokens,
                applyDefaultPreamble = params.applyDefaultPreamble,
                numCtx = params.numCtx
            )
            cancelled = when (depth.takeIf { params.webSearchEnabled }) {
                SearchDepth.TOOL_LOOP -> runToolLoop(chatMessages, effectiveSystemPrompt, fullResponse, params.backendId, genConfig)
                SearchDepth.AUTO_FETCH -> runAutoFetch(chatMessages, effectiveSystemPrompt, fullResponse, params.backendId, genConfig)
                null -> runSingleTurn(chatMessages, effectiveSystemPrompt, fullResponse, params.backendId, genConfig)
            }
        } catch (ie: InferenceError) {
            lastFailed[chatId] = LastFailed(params.currentUserContent, params.currentAttachments)
            setError(
                chatId,
                when (ie) {
                    is InferenceError.ModelNotLoaded ->
                        ChatError("Load a model to start generating.", retryable = false)
                    is InferenceError.Cancelled ->
                        ChatError("Generation cancelled.", retryable = true)
                    is InferenceError.GenerationFailed,
                    is InferenceError.TranscriptionFailed,
                    is InferenceError.LoadFailed ->
                        ChatError(ie.message ?: "Generation failed.", retryable = true)
                }
            )
        } catch (ce: CancellationException) {
            cancelled = true
            throw ce
        } catch (t: Throwable) {
            lastFailed[chatId] = LastFailed(params.currentUserContent, params.currentAttachments)
            setError(chatId, ChatError(t.message ?: "Generation failed.", retryable = true))
        } finally {
            val responseText = fullResponse.toString()
            if (responseText.isNotBlank()) {
                lastFailed.remove(chatId)
                runCatching {
                    chatRepository.addMessage(
                        Message(
                            chatId = chatId,
                            role = MessageRole.ASSISTANT,
                            content = if (cancelled) "$responseText _(stopped)_" else responseText,
                            tokenCount = inferenceManager.countTokens(responseText)
                        )
                    )
                }
            }
            _activeGeneration.value = null
        }
    }

    private suspend fun runSingleTurn(
        chatMessages: List<ChatMessage>,
        systemPromptText: String,
        fullResponse: StringBuilder,
        backendId: String? = null,
        config: GenerationConfig = GenerationConfig()
    ): Boolean {
        var firstTokenReceived = false
        var showingSlowWarning = false
        val slowJob = scope.launch {
            delay(SLOW_RESPONSE_THRESHOLD_MS)
            showingSlowWarning = true
            updateSearchStatus("Still generating — the model is taking a while to respond…")
        }
        return try {
            inferenceManager.generate(
                systemPrompt = systemPromptText,
                messages = chatMessages,
                config = config,
                backendId = backendId
            ).collect { token ->
                if (!firstTokenReceived) {
                    firstTokenReceived = true
                    slowJob.cancel()
                }
                if (showingSlowWarning) {
                    showingSlowWarning = false
                    updateSearchStatus(null)
                }
                fullResponse.append(token)
                updateStreaming(fullResponse.toString())
            }
            false
        } catch (ce: CancellationException) { true }
        finally {
            slowJob.cancel()
            if (showingSlowWarning) updateSearchStatus(null)
        }
    }

    private suspend fun runAutoFetch(
        chatMessages: List<ChatMessage>,
        systemPromptText: String,
        fullResponse: StringBuilder,
        backendId: String? = null,
        config: GenerationConfig = GenerationConfig()
    ): Boolean {
        val firstBuf = StringBuilder()
        var cancelled = try {
            inferenceManager.generate(
                systemPrompt = systemPromptText,
                messages = chatMessages,
                config = config,
                backendId = backendId
            ).collect { token ->
                firstBuf.append(token)
                updateStreaming(stripToolTags(firstBuf.toString()))
            }
            false
        } catch (ce: CancellationException) { true }

        val searchMatch = if (!cancelled) SEARCH_TAG_REGEX.find(firstBuf) else null
        if (searchMatch == null) {
            fullResponse.append(firstBuf)
            return cancelled
        }

        val query = searchMatch.groupValues[1].trim()
        updateSearchStatus("Searching: $query")
        val results = try {
            webSearchClient.search(query, count = 5)
        } catch (t: Throwable) {
            updateSearchStatus(null)
            val msg = "Search failed: ${t.message ?: "unknown error"}"
            fullResponse.append(msg)
            updateStreaming(msg)
            return cancelled
        }

        val enriched = StringBuilder(WebSearchClient.formatForPrompt(query, results))
        results.take(2).forEachIndexed { idx, r ->
            updateSearchStatus("Reading: ${r.title.take(40)}")
            val page = pageFetcher.fetch(r.url, maxChars = 2000)
            if (page.isNotBlank()) {
                enriched.append("\n\n--- Page [${idx + 1}] ${r.title} (${r.url}) ---\n")
                enriched.append(page)
            }
        }
        updateSearchStatus(null)

        val continuation = chatMessages + listOf(
            ChatMessage(role = "assistant", content = "<search>$query</search>"),
            ChatMessage(
                role = "user",
                content = "$enriched\n\nUse these to answer my previous question. " +
                    "Do not call <search> again."
            )
        )
        updateStreaming("")
        cancelled = try {
            inferenceManager.generate(
                systemPrompt = systemPromptText,
                messages = continuation,
                // Preamble already applied to the first turn; skip it on the follow-up.
                config = config.copy(applyDefaultPreamble = false),
                backendId = backendId
            ).collect { token ->
                fullResponse.append(token)
                updateStreaming(fullResponse.toString())
            }
            cancelled
        } catch (ce: CancellationException) { true }
        return cancelled
    }

    private suspend fun runToolLoop(
        chatMessages: List<ChatMessage>,
        systemPromptText: String,
        fullResponse: StringBuilder,
        backendId: String? = null,
        config: GenerationConfig = GenerationConfig()
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
                    backendId = backendId
                ).collect { token ->
                    buf.append(token)
                    updateStreaming(stripToolTags(buf.toString()))
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
                    updateSearchStatus("Searching: $query")
                    try {
                        val results = webSearchClient.search(query)
                        WebSearchClient.formatForPrompt(query, results)
                    } catch (t: Throwable) {
                        "Search failed: ${t.message ?: "unknown error"}"
                    } finally {
                        updateSearchStatus(null)
                    }
                }
                fetchMatch -> {
                    val url = firstTool.groupValues[1].trim()
                    updateSearchStatus("Reading: ${url.take(50)}")
                    val page = pageFetcher.fetch(url, maxChars = 3000)
                    updateSearchStatus(null)
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
                ChatMessage(role = "user", content = toolResultText + closingHint)
            )
            updateStreaming("")
        }
        return false
    }
}

