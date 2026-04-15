package com.oli.projectsai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.oli.projectsai.ui.components.TokenBreakdown
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private suspend fun loadProjectContext(pid: Long) {
        val project = projectRepository.getProject(pid) ?: return
        val contextParts = buildList {
            if (project.manualContext.isNotBlank()) add(project.manualContext)
            if (project.accumulatedMemory.isNotBlank()) {
                add("## Accumulated Memory\n${project.accumulatedMemory}")
            }
        }
        systemPrompt = contextParts.joinToString("\n\n")

        val contextLimit = inferenceManager.getActiveBackend()?.loadedModel?.contextLength ?: 8192
        _tokenBreakdown.value = TokenBreakdown(
            systemPrompt = inferenceManager.countTokens(project.manualContext),
            memory = inferenceManager.countTokens(project.accumulatedMemory),
            contextLimit = contextLimit
        )
    }

    fun sendMessage(content: String) {
        if (content.isBlank() || _isGenerating.value) return

        val trimmed = content.trim()
        viewModelScope.launch {
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

    private suspend fun generate(currentUserContent: String? = null) {
        _isGenerating.value = true
        _streamingContent.value = ""
        _error.value = null

        val fullResponse = StringBuilder()
        try {
            val dbMessages = _messages.value.map { msg ->
                ChatMessage(
                    role = when (msg.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "model"
                        MessageRole.SYSTEM -> "system"
                    },
                    content = msg.content
                )
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

            responseFlow.collect { token ->
                fullResponse.append(token)
                _streamingContent.value = fullResponse.toString()
            }

            val responseText = fullResponse.toString()
            if (responseText.isNotBlank()) {
                lastFailedUserContent = null
                try {
                    chatRepository.addMessage(
                        Message(
                            chatId = activeChatId,
                            role = MessageRole.ASSISTANT,
                            content = responseText,
                            tokenCount = inferenceManager.countTokens(responseText)
                        )
                    )
                } catch (t: Throwable) {
                    _error.value = ChatError(
                        "Response generated but failed to save: ${t.message}",
                        retryable = false
                    )
                }
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
        } catch (t: Throwable) {
            lastFailedUserContent = currentUserContent
            _error.value = ChatError(t.message ?: "Generation failed.", retryable = true)
        } finally {
            _isGenerating.value = false
            _streamingContent.value = ""
        }
    }

    private suspend fun updateTokenBreakdown() {
        val convTokens = _messages.value.sumOf { it.tokenCount }
        _tokenBreakdown.value = _tokenBreakdown.value.copy(conversation = convTokens)
    }

    fun copyToClipboard(text: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Projects AI", text))
    }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(Intent.createChooser(intent, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

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
