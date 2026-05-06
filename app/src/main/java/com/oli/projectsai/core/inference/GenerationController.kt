package com.oli.projectsai.core.inference

import com.oli.projectsai.core.db.entity.Message
import com.oli.projectsai.core.db.entity.MessageRole
import com.oli.projectsai.core.preferences.SearchDepth
import com.oli.projectsai.core.preferences.SearchSettings
import com.oli.projectsai.core.repository.ChatRepository
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
    val titleHint: String,
)

/**
 * Coordinates a chat-level generation: pulls the message history, composes the system prompt,
 * dispatches to the right run-mode (single turn / auto-fetch / tool loop), persists the
 * assistant reply, and exposes a single [activeGeneration] StateFlow the UI subscribes to.
 *
 * The actual generation work lives in two extracted helpers:
 * - [MessageAssembler] resolves the wire-format ChatMessage list (DB + staged user turn).
 * - [AgentRunner] owns the search/fetch agent flows when web search is on.
 *
 * Only the simple non-search [runSingleTurn] case stays inline here because it depends on the
 * controller's own [scope] for the slow-response warning timer.
 */
@Singleton
class GenerationController @Inject constructor(
    private val chatRepository: ChatRepository,
    private val inferenceManager: InferenceManager,
    private val messageAssembler: MessageAssembler,
    private val agentRunner: AgentRunner,
    private val searchSettings: SearchSettings,
    @ApplicationScope private val scope: CoroutineScope,
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
            titleHint = params.chatTitleHint,
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
            val chatMessages = messageAssembler.assemble(
                chatId = chatId,
                currentUserContent = params.currentUserContent,
                currentAttachments = params.currentAttachments,
            )

            val depth = if (params.webSearchEnabled) searchSettings.searchDepth.first()
                        else SearchDepth.AUTO_FETCH
            val toolInstructions = when {
                !params.webSearchEnabled -> ""
                depth == SearchDepth.TOOL_LOOP -> TOOL_LOOP_INSTRUCTIONS
                else -> AUTO_FETCH_INSTRUCTIONS
            }
            val effectiveSystemPrompt = buildList {
                add(currentTemporalContext())
                if (params.systemPrompt.isNotBlank()) add(params.systemPrompt)
                if (toolInstructions.isNotBlank()) add(toolInstructions)
                if (params.forceShortAnswer) add(FORCE_ANSWER_INSTRUCTIONS)
            }.joinToString("\n\n")

            val genConfig = GenerationConfig(
                maxOutputTokens = params.maxOutputTokens,
                applyDefaultPreamble = params.applyDefaultPreamble,
                numCtx = params.numCtx,
            )
            cancelled = when (depth.takeIf { params.webSearchEnabled }) {
                SearchDepth.TOOL_LOOP -> agentRunner.runToolLoop(
                    chatMessages, effectiveSystemPrompt, fullResponse,
                    params.backendId, genConfig,
                    onStreaming = ::updateStreaming,
                    onSearchStatus = ::updateSearchStatus,
                )
                SearchDepth.AUTO_FETCH -> agentRunner.runAutoFetch(
                    chatMessages, effectiveSystemPrompt, fullResponse,
                    params.backendId, genConfig,
                    onStreaming = ::updateStreaming,
                    onSearchStatus = ::updateSearchStatus,
                )
                null -> runSingleTurn(
                    chatMessages, effectiveSystemPrompt, fullResponse,
                    params.backendId, genConfig,
                )
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
                },
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
                            tokenCount = inferenceManager.countTokens(responseText),
                        ),
                    )
                }
            }
            _activeGeneration.value = null
        }
    }

    /**
     * Plain non-tool generation. Inlined here (rather than living in [AgentRunner]) because the
     * slow-response warning uses [scope] to schedule a delayed status update — coupling we
     * don't want to push down to the runner.
     */
    private suspend fun runSingleTurn(
        chatMessages: List<ChatMessage>,
        systemPromptText: String,
        fullResponse: StringBuilder,
        backendId: String? = null,
        config: GenerationConfig = GenerationConfig(),
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
                backendId = backendId,
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
}
