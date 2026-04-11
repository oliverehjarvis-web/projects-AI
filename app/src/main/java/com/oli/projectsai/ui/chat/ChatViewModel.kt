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
    private var systemPrompt = ""

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _tokenBreakdown = MutableStateFlow(TokenBreakdown())
    val tokenBreakdown: StateFlow<TokenBreakdown> = _tokenBreakdown.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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

        viewModelScope.launch {
            // Save user message
            val userMsg = Message(
                chatId = activeChatId,
                role = MessageRole.USER,
                content = content.trim(),
                tokenCount = inferenceManager.countTokens(content)
            )
            chatRepository.addMessage(userMsg)

            // Auto-title from first message
            if (_messages.value.isEmpty()) {
                val title = content.take(50).let { if (content.length > 50) "$it..." else it }
                chatRepository.updateChatTitle(activeChatId, title)
                _chatTitle.value = title
            }

            // Generate response
            generate()
        }
    }

    private suspend fun generate() {
        _isGenerating.value = true
        _streamingContent.value = ""
        _error.value = null

        try {
            val chatMessages = _messages.value.map { msg ->
                ChatMessage(
                    role = when (msg.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "model"
                        MessageRole.SYSTEM -> "system"
                    },
                    content = msg.content
                )
            }

            val responseFlow = inferenceManager.generate(
                systemPrompt = systemPrompt,
                messages = chatMessages,
                config = GenerationConfig()
            )

            val fullResponse = StringBuilder()
            responseFlow.collect { token ->
                fullResponse.append(token)
                _streamingContent.value = fullResponse.toString()
            }

            // Save assistant message
            val responseText = fullResponse.toString()
            if (responseText.isNotBlank()) {
                chatRepository.addMessage(
                    Message(
                        chatId = activeChatId,
                        role = MessageRole.ASSISTANT,
                        content = responseText,
                        tokenCount = inferenceManager.countTokens(responseText)
                    )
                )
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "Generation failed"
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
