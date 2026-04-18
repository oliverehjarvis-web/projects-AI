package com.oli.projectsai.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.attachments.AttachmentStore
import com.oli.projectsai.data.db.entity.Chat
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.data.preferences.GlobalContextStore
import com.oli.projectsai.data.repository.ChatRepository
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.data.search.WebSearchClient
import com.oli.projectsai.inference.AudioCapture
import com.oli.projectsai.inference.ChatMessage
import com.oli.projectsai.inference.GenerationConfig
import com.oli.projectsai.inference.InferenceError
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
import com.oli.projectsai.inference.SummarisationPrompts
import com.oli.projectsai.inference.TRANSCRIPTION_MAX_SECONDS
import com.oli.projectsai.ui.common.copyToClipboard
import com.oli.projectsai.ui.common.shareText
import com.oli.projectsai.ui.components.TokenBreakdown
import kotlinx.coroutines.delay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val projectRepository: ProjectRepository,
    private val inferenceManager: InferenceManager,
    private val globalContextStore: GlobalContextStore,
    private val attachmentStore: AttachmentStore,
    private val audioCapture: AudioCapture,
    private val webSearchClient: WebSearchClient,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val chatId: Long = savedStateHandle.get<Long>("chatId") ?: -1L
    private val projectId: Long = savedStateHandle.get<Long>("projectId") ?: -1L
    private val quickActionId: Long = savedStateHandle.get<Long>("quickActionId") ?: -1L

    private var activeChatId: Long = chatId

    private val _systemContext = MutableStateFlow("")
    val systemContext: StateFlow<String> = _systemContext.asStateFlow()
    private var systemPrompt: String
        get() = _systemContext.value
        set(value) { _systemContext.value = value }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _tokenBreakdown = MutableStateFlow(TokenBreakdown())
    val tokenBreakdown: StateFlow<TokenBreakdown> = _tokenBreakdown.asStateFlow()

    data class ChatError(val message: String, val retryable: Boolean)

    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = _error.asStateFlow()

    private var lastFailedUserContent: String? = null
    private var lastFailedAttachments: List<String> = emptyList()

    private val _pendingAttachments = MutableStateFlow<List<String>>(emptyList())
    val pendingAttachments: StateFlow<List<String>> = _pendingAttachments.asStateFlow()

    sealed class DictationState {
        data object Idle : DictationState()
        data class Recording(val elapsedMs: Long) : DictationState()
        data object Transcribing : DictationState()
        data class Error(val message: String) : DictationState()
    }

    private val _dictationState = MutableStateFlow<DictationState>(DictationState.Idle)
    val dictationState: StateFlow<DictationState> = _dictationState.asStateFlow()

    private val _transcribedText = MutableStateFlow<String?>(null)
    val transcribedText: StateFlow<String?> = _transcribedText.asStateFlow()

    private var dictationJob: Job? = null

    private val _chatTitle = MutableStateFlow("New Chat")
    val chatTitle: StateFlow<String> = _chatTitle.asStateFlow()

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    private val _searchStatus = MutableStateFlow<String?>(null)
    val searchStatus: StateFlow<String?> = _searchStatus.asStateFlow()

    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                inferenceManager.tokenizerVersion,
                inferenceManager.contextLimitFlow,
                globalContextStore.name,
                globalContextStore.rules
            ) { _, _, name, rules -> name to rules }.collect { (name, rules) ->
                if (contextProjectId != -1L) {
                    val p = projectRepository.getProject(contextProjectId)
                    if (p != null) {
                        systemPrompt = buildSystemPrompt(name, rules, p.manualContext, p.accumulatedMemory)
                        refreshContextTokenBreakdown(name, rules, p.manualContext, p.accumulatedMemory)
                    }
                }
                updateTokenBreakdown()
            }
        }
        viewModelScope.launch {
            if (chatId != -1L) {
                // Existing chat
                val chat = chatRepository.getChat(chatId)
                if (chat != null) {
                    activeChatId = chat.id
                    _chatTitle.value = chat.title
                    _webSearchEnabled.value = chat.webSearchEnabled
                    loadProjectContext(chat.projectId)
                    chatRepository.getMessagesFlow(chatId).collect { msgs ->
                        _messages.value = msgs
                        updateTokenBreakdown()
                    }
                }
            } else if (projectId != -1L) {
                // New chat
                val newChatId = chatRepository.createChat(
                    Chat(projectId = projectId, title = "New Chat")
                )
                activeChatId = newChatId
                loadProjectContext(projectId)

                // If quick action, pre-fill
                if (quickActionId != -1L) {
                    val action = projectRepository.getQuickActions(projectId).first()
                        .firstOrNull { it.id == quickActionId }
                    if (action != null) {
                        sendMessage(action.promptTemplate)
                    }
                }

                chatRepository.getMessagesFlow(newChatId).collect { msgs ->
                    _messages.value = msgs
                    updateTokenBreakdown()
                }
            }
        }
    }

    private var contextProjectId: Long = -1L

    private suspend fun loadProjectContext(pid: Long) {
        contextProjectId = pid
        val project = projectRepository.getProject(pid) ?: return
        val name = globalContextStore.name.first()
        val rules = globalContextStore.rules.first()
        systemPrompt = buildSystemPrompt(name, rules, project.manualContext, project.accumulatedMemory)
        refreshContextTokenBreakdown(name, rules, project.manualContext, project.accumulatedMemory)
    }

    private fun buildSystemPrompt(
        name: String,
        rules: String,
        manualContext: String,
        memory: String
    ): String = buildList {
        val globalBlock = buildGlobalBlock(name, rules)
        if (globalBlock.isNotBlank()) add(globalBlock)
        if (manualContext.isNotBlank()) add(manualContext)
        if (memory.isNotBlank()) add("## Accumulated Memory\n$memory")
    }.joinToString("\n\n")

    private suspend fun refreshContextTokenBreakdown(
        name: String,
        rules: String,
        manualContext: String,
        memory: String
    ) {
        val systemText = listOfNotNull(
            buildGlobalBlock(name, rules).ifBlank { null },
            manualContext.ifBlank { null }
        ).joinToString("\n\n")
        _tokenBreakdown.value = _tokenBreakdown.value.copy(
            systemPrompt = inferenceManager.countTokens(systemText),
            memory = inferenceManager.countTokens(memory),
            contextLimit = inferenceManager.contextLimitFlow.value
        )
    }

    private fun buildGlobalBlock(name: String, rules: String): String {
        val parts = mutableListOf<String>()
        if (name.isNotBlank()) parts.add("You are speaking with ${name.trim()}.")
        if (rules.isNotBlank()) {
            parts.add("Follow these rules in every response:\n${rules.trim()}")
        }
        return parts.joinToString("\n\n")
    }

    fun sendMessage(content: String) {
        val attachments = _pendingAttachments.value
        if (content.isBlank() && attachments.isEmpty()) return
        if (_isGenerating.value) return

        val trimmed = content.trim()
        generationJob = viewModelScope.launch {
            val persisted = try {
                chatRepository.addMessage(
                    Message(
                        chatId = activeChatId,
                        role = MessageRole.USER,
                        content = trimmed,
                        tokenCount = inferenceManager.countTokens(trimmed),
                        attachmentPaths = attachments
                    )
                )
                true
            } catch (t: Throwable) {
                _error.value = ChatError("Couldn't save your message: ${t.message}", retryable = false)
                false
            }
            if (!persisted) return@launch

            _pendingAttachments.value = emptyList()

            if (_messages.value.isEmpty()) {
                val titleSource = trimmed.ifBlank { "Image chat" }
                val title = titleSource.take(50).let { if (titleSource.length > 50) "$it..." else it }
                runCatching { chatRepository.updateChatTitle(activeChatId, title) }
                _chatTitle.value = title
            }

            generate(currentUserContent = trimmed, currentAttachments = attachments)
        }
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            try {
                val path = attachmentStore.importImage(uri)
                _pendingAttachments.value = _pendingAttachments.value + path
            } catch (t: Throwable) {
                _error.value = ChatError("Couldn't attach image: ${t.message}", retryable = false)
            }
        }
    }

    fun removePendingAttachment(path: String) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it == path }
    }

    fun retryLastPrompt() {
        val content = lastFailedUserContent ?: return
        if (_isGenerating.value) return
        _error.value = null
        val atts = lastFailedAttachments
        generationJob = viewModelScope.launch {
            generate(currentUserContent = content, currentAttachments = atts)
        }
    }

    fun cancelGeneration() {
        if (!_isGenerating.value) return
        generationJob?.cancel(CancellationException("User cancelled generation"))
    }

    fun toggleWebSearch() {
        val next = !_webSearchEnabled.value
        _webSearchEnabled.value = next
        viewModelScope.launch {
            runCatching { chatRepository.updateWebSearchEnabled(activeChatId, next) }
        }
    }

    private suspend fun generate(
        currentUserContent: String? = null,
        currentAttachments: List<String> = emptyList()
    ) {
        _isGenerating.value = true
        _streamingContent.value = ""
        _error.value = null

        val fullResponse = StringBuilder()
        var cancelled = false
        try {
            val dbMessages = _messages.value.mapIndexed { idx, msg ->
                val isLast = idx == _messages.value.lastIndex
                val bytes = if (isLast && msg.role == MessageRole.USER) {
                    msg.attachmentPaths.map { attachmentStore.readBytes(it) }
                } else emptyList()
                ChatMessage(
                    role = msg.role.toWireRole(),
                    content = msg.content,
                    imageBytes = bytes
                )
            }
            val chatMessages = if (currentUserContent != null &&
                dbMessages.lastOrNull()?.content != currentUserContent) {
                val bytes = currentAttachments.map { attachmentStore.readBytes(it) }
                dbMessages + ChatMessage(
                    role = "user",
                    content = currentUserContent,
                    imageBytes = bytes
                )
            } else {
                dbMessages
            }

            val searchEnabled = _webSearchEnabled.value
            val effectiveSystemPrompt = if (searchEnabled) {
                systemPrompt + "\n\n" + SEARCH_TOOL_INSTRUCTIONS
            } else systemPrompt

            val firstBuf = StringBuilder()
            try {
                inferenceManager.generate(
                    systemPrompt = effectiveSystemPrompt,
                    messages = chatMessages,
                    config = GenerationConfig()
                ).collect { token ->
                    firstBuf.append(token)
                    _streamingContent.value = if (searchEnabled) {
                        stripAfterSearchOpen(firstBuf.toString())
                    } else {
                        firstBuf.toString()
                    }
                }
            } catch (ce: CancellationException) {
                cancelled = true
            }

            val firstText = firstBuf.toString()
            val searchMatch = if (searchEnabled && !cancelled) {
                SEARCH_TAG_REGEX.find(firstText)
            } else null

            if (searchMatch != null) {
                val query = searchMatch.groupValues[1].trim()
                _searchStatus.value = "Searching: $query"
                val resultsText = try {
                    val results = webSearchClient.search(query)
                    WebSearchClient.formatForPrompt(query, results)
                } catch (t: Throwable) {
                    "Search failed: ${t.message ?: "unknown error"}"
                } finally {
                    _searchStatus.value = null
                }

                val continuationMessages = chatMessages + listOf(
                    ChatMessage(role = "model", content = "<search>$query</search>"),
                    ChatMessage(
                        role = "user",
                        content = "Search results:\n\n$resultsText\n\n" +
                            "Use these to answer my previous question. Do not call <search> again."
                    )
                )
                _streamingContent.value = ""
                try {
                    inferenceManager.generate(
                        systemPrompt = effectiveSystemPrompt,
                        messages = continuationMessages,
                        config = GenerationConfig()
                    ).collect { token ->
                        fullResponse.append(token)
                        _streamingContent.value = fullResponse.toString()
                    }
                } catch (ce: CancellationException) {
                    cancelled = true
                }
            } else {
                fullResponse.append(firstText)
            }
        } catch (ie: InferenceError) {
            lastFailedUserContent = currentUserContent
            lastFailedAttachments = currentAttachments
            _error.value = when (ie) {
                is InferenceError.ModelNotLoaded ->
                    ChatError("Load a model to start generating.", retryable = false)
                is InferenceError.Cancelled ->
                    ChatError("Generation cancelled.", retryable = true)
                is InferenceError.GenerationFailed,
                is InferenceError.TranscriptionFailed,
                is InferenceError.LoadFailed ->
                    ChatError(ie.message ?: "Generation failed.", retryable = true)
            }
        } catch (ce: CancellationException) {
            cancelled = true
            throw ce
        } catch (t: Throwable) {
            lastFailedUserContent = currentUserContent
            lastFailedAttachments = currentAttachments
            _error.value = ChatError(t.message ?: "Generation failed.", retryable = true)
        } finally {
            val responseText = fullResponse.toString()
            if (responseText.isNotBlank()) {
                lastFailedUserContent = null
                lastFailedAttachments = emptyList()
                // Persist even on cancel so the user's partial answer isn't lost.
                runCatching {
                    chatRepository.addMessage(
                        Message(
                            chatId = activeChatId,
                            role = MessageRole.ASSISTANT,
                            content = if (cancelled) "$responseText _(stopped)_" else responseText,
                            tokenCount = inferenceManager.countTokens(responseText)
                        )
                    )
                }
            }
            _isGenerating.value = false
            _streamingContent.value = ""
        }
    }

    fun startDictation() {
        if (dictationJob?.isActive == true) return
        if (!isModelLoaded) {
            _dictationState.value = DictationState.Error("Load a model first.")
            return
        }
        _transcribedText.value = null
        dictationJob = viewModelScope.launch {
            try {
                audioCapture.start()
            } catch (t: Throwable) {
                _dictationState.value = DictationState.Error(t.message ?: "Microphone unavailable")
                return@launch
            }
            val startedAt = System.currentTimeMillis()
            val maxMs = TRANSCRIPTION_MAX_SECONDS * 1000L
            _dictationState.value = DictationState.Recording(0L)
            try {
                while (audioCapture.isRecording) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    _dictationState.value = DictationState.Recording(elapsed)
                    if (elapsed >= maxMs) break
                    delay(200L)
                }
            } catch (ce: CancellationException) {
                audioCapture.cancel()
                _dictationState.value = DictationState.Idle
                throw ce
            }
            finishDictation()
        }
    }

    fun stopDictation() {
        val job = dictationJob ?: return
        if (!job.isActive) return
        viewModelScope.launch { finishDictation() }
    }

    fun cancelDictation() {
        audioCapture.cancel()
        dictationJob?.cancel()
        dictationJob = null
        _dictationState.value = DictationState.Idle
    }

    private suspend fun finishDictation() {
        if (_dictationState.value is DictationState.Transcribing) return
        _dictationState.value = DictationState.Transcribing
        val bytes = audioCapture.stop()
        if (bytes.isEmpty()) {
            _dictationState.value = DictationState.Idle
            return
        }
        try {
            val text = inferenceManager.transcribe(bytes).trim()
            _transcribedText.value = text
            _dictationState.value = DictationState.Idle
        } catch (t: Throwable) {
            _dictationState.value = DictationState.Error(t.message ?: "Transcription failed")
        } finally {
            dictationJob = null
        }
    }

    fun consumeTranscribedText(): String? {
        val text = _transcribedText.value
        _transcribedText.value = null
        return text
    }

    fun dismissDictationError() {
        if (_dictationState.value is DictationState.Error) {
            _dictationState.value = DictationState.Idle
        }
    }

    private suspend fun updateTokenBreakdown() {
        // Recompute conversation tokens live rather than summing stored snapshots — they can
        // drift after a model swap because each model has a different tokenizer.
        val joined = _messages.value.joinToString("\n") { it.content }
        val convTokens = inferenceManager.countTokens(joined)
        _tokenBreakdown.value = _tokenBreakdown.value.copy(
            conversation = convTokens,
            contextLimit = inferenceManager.contextLimitFlow.value
        )
    }

    fun copyToClipboard(text: String) = appContext.copyToClipboard(text)

    fun shareConversation() {
        val text = messages.value.joinToString("\n\n") { msg ->
            val role = if (msg.role == MessageRole.USER) "You" else "Assistant"
            "$role: ${msg.content}"
        }
        shareText(text)
    }

    fun shareText(text: String) = appContext.shareText(text)

    fun getConversationForMemory(): String {
        return _messages.value.joinToString("\n\n") { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
                MessageRole.SYSTEM -> "System"
            }
            "$role: ${msg.content}"
        }
    }

    val isModelLoaded: Boolean
        get() = inferenceManager.modelState.value is ModelState.Loaded

    /**
     * Runs the loaded model over [conversation] with an add-to-memory prompt and returns the
     * bullet-point summary. Throws [InferenceError] when the model isn't loaded or generation fails.
     */
    suspend fun autoSummariseForMemory(conversation: String): String {
        if (inferenceManager.modelState.value !is ModelState.Loaded) throw InferenceError.ModelNotLoaded
        val (system, user) = SummarisationPrompts.buildAddToMemoryPrompt(conversation)
        val out = StringBuilder()
        inferenceManager.generate(
            systemPrompt = system,
            messages = listOf(ChatMessage(role = "user", content = user)),
            config = GenerationConfig()
        ).collect { chunk -> out.append(chunk) }
        return out.toString().trim()
    }

    fun addToMemory(summary: String) {
        viewModelScope.launch {
            val pid = if (projectId != -1L) projectId else {
                chatRepository.getChat(activeChatId)?.projectId ?: return@launch
            }
            val project = projectRepository.getProject(pid) ?: return@launch
            val existing = project.accumulatedMemory
            val newMemory = if (existing.isBlank()) summary
            else "$existing\n\n---\n\n$summary"

            projectRepository.updateMemory(pid, newMemory)
        }
    }

    fun dismissError() { _error.value = null }
}

private fun MessageRole.toWireRole(): String = when (this) {
    MessageRole.USER -> "user"
    MessageRole.ASSISTANT -> "model"
    MessageRole.SYSTEM -> "system"
}

private val SEARCH_TAG_REGEX = Regex("<search>(.*?)</search>", RegexOption.DOT_MATCHES_ALL)

/**
 * Hides a partial <search> tag from the user while it streams in. Once we know the full query
 * we'll run the search and stream the real answer, so showing the tag itself only confuses.
 */
private fun stripAfterSearchOpen(text: String): String {
    val i = text.indexOf("<search>")
    return if (i >= 0) text.substring(0, i) else text
}

private val SEARCH_TOOL_INSTRUCTIONS = """
You have access to a web search tool. When the user's question needs current or specific
information you don't already know (news, dates, stats, recent events, specific facts),
respond with exactly:

<search>your concise search query</search>

and nothing else on that turn. You will then receive search results and should give
your final answer using them.

If you can answer from what you already know, answer directly — do not use <search> tags
in normal answers. Only use the tag when you would otherwise need to look something up.
""".trimIndent()

