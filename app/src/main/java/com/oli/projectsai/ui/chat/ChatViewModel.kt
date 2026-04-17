package com.oli.projectsai.ui.chat

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.db.entity.Chat
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.data.repository.ChatRepository
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.ChatMessage
import com.oli.projectsai.inference.GenerationConfig
import com.oli.projectsai.inference.InferenceError
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
import com.oli.projectsai.inference.SummarisationPrompts
import com.oli.projectsai.ui.common.copyToClipboard
import com.oli.projectsai.ui.common.shareText
import com.oli.projectsai.ui.components.TokenBreakdown
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

    private val _chatTitle = MutableStateFlow("New Chat")
    val chatTitle: StateFlow<String> = _chatTitle.asStateFlow()

    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                inferenceManager.tokenizerVersion,
                inferenceManager.contextLimitFlow
            ) { _, _ -> }.collect {
                if (contextProjectId != -1L) {
                    val p = projectRepository.getProject(contextProjectId)
                    if (p != null) refreshContextTokenBreakdown(p.manualContext, p.accumulatedMemory)
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
        val contextParts = buildList {
            if (project.manualContext.isNotBlank()) add(project.manualContext)
            if (project.accumulatedMemory.isNotBlank()) {
                add("## Accumulated Memory\n${project.accumulatedMemory}")
            }
        }
        systemPrompt = contextParts.joinToString("\n\n")
        refreshContextTokenBreakdown(project.manualContext, project.accumulatedMemory)
    }

    private suspend fun refreshContextTokenBreakdown(manualContext: String, memory: String) {
        _tokenBreakdown.value = _tokenBreakdown.value.copy(
            systemPrompt = inferenceManager.countTokens(manualContext),
            memory = inferenceManager.countTokens(memory),
            contextLimit = inferenceManager.contextLimitFlow.value
        )
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _isGenerating.value) return

        val trimmed = content.trim()
        generationJob = viewModelScope.launch {
            val persisted = try {
                chatRepository.addMessage(
                    Message(
                        chatId = activeChatId,
                        role = MessageRole.USER,
                        content = trimmed,
                        tokenCount = inferenceManager.countTokens(trimmed)
                    )
                )
                true
            } catch (t: Throwable) {
                _error.value = ChatError("Couldn't save your message: ${t.message}", retryable = false)
                false
            }
            if (!persisted) return@launch

            if (_messages.value.isEmpty()) {
                val title = trimmed.take(50).let { if (trimmed.length > 50) "$it..." else it }
                runCatching { chatRepository.updateChatTitle(activeChatId, title) }
                _chatTitle.value = title
            }

            generate(currentUserContent = trimmed)
        }
    }

    fun retryLastPrompt() {
        val content = lastFailedUserContent ?: return
        if (_isGenerating.value) return
        _error.value = null
        generationJob = viewModelScope.launch { generate(currentUserContent = content) }
    }

    fun cancelGeneration() {
        if (!_isGenerating.value) return
        generationJob?.cancel(CancellationException("User cancelled generation"))
    }

    private suspend fun generate(currentUserContent: String? = null) {
        _isGenerating.value = true
        _streamingContent.value = ""
        _error.value = null

        val fullResponse = StringBuilder()
        var cancelled = false
        try {
            val dbMessages = _messages.value.map { msg ->
                ChatMessage(role = msg.role.toWireRole(), content = msg.content)
            }
            val chatMessages = if (currentUserContent != null &&
                dbMessages.lastOrNull()?.content != currentUserContent) {
                dbMessages + ChatMessage(role = "user", content = currentUserContent)
            } else {
                dbMessages
            }

            val responseFlow = inferenceManager.generate(
                systemPrompt = systemPrompt,
                messages = chatMessages,
                config = GenerationConfig()
            )

            try {
                responseFlow.collect { token ->
                    fullResponse.append(token)
                    _streamingContent.value = fullResponse.toString()
                }
            } catch (ce: CancellationException) {
                cancelled = true
            }
        } catch (ie: InferenceError) {
            lastFailedUserContent = currentUserContent
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
            _error.value = ChatError(t.message ?: "Generation failed.", retryable = true)
        } finally {
            val responseText = fullResponse.toString()
            if (responseText.isNotBlank()) {
                lastFailedUserContent = null
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
