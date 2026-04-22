package com.oli.projectsai.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.attachments.AttachmentStore
import com.oli.projectsai.data.db.entity.Chat
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.data.preferences.GlobalContextStore
import com.oli.projectsai.data.preferences.VoiceSettings
import com.oli.projectsai.data.repository.ChatRepository
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.AudioCapture
import com.oli.projectsai.inference.ChatError
import com.oli.projectsai.inference.ChatMessage
import com.oli.projectsai.inference.GenerationConfig
import com.oli.projectsai.inference.GenerationController
import com.oli.projectsai.inference.GenerationForegroundService
import com.oli.projectsai.inference.GenerationParams
import com.oli.projectsai.inference.InferenceError
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelInfo
import com.oli.projectsai.inference.ModelPrecision
import com.oli.projectsai.inference.ModelState
import com.oli.projectsai.inference.SummarisationPrompts
import com.oli.projectsai.inference.TRANSCRIPTION_MAX_SECONDS
import java.io.File
import com.oli.projectsai.ui.common.copyToClipboard
import com.oli.projectsai.ui.common.shareText
import com.oli.projectsai.ui.components.TokenBreakdown
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    private val generationController: GenerationController,
    private val voiceSettings: VoiceSettings,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val chatId: Long = savedStateHandle.get<Long>("chatId") ?: -1L
    private val projectId: Long = savedStateHandle.get<Long>("projectId") ?: -1L
    private val quickActionId: Long = savedStateHandle.get<Long>("quickActionId") ?: -1L

    private val _activeChatId = MutableStateFlow(chatId)
    private val activeChatId: Long get() = _activeChatId.value

    private val _systemContext = MutableStateFlow("")
    val systemContext: StateFlow<String> = _systemContext.asStateFlow()
    private var systemPrompt: String
        get() = _systemContext.value
        set(value) { _systemContext.value = value }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    val isGenerating: StateFlow<Boolean> = combine(
        _activeChatId,
        generationController.activeGeneration
    ) { cid, active -> active?.chatId == cid && active.isGenerating }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val streamingContent: StateFlow<String> = combine(
        _activeChatId,
        generationController.activeGeneration
    ) { cid, active -> if (active?.chatId == cid) active.streamingContent else "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val searchStatus: StateFlow<String?> = combine(
        _activeChatId,
        generationController.activeGeneration
    ) { cid, active -> if (active?.chatId == cid) active.searchStatus else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val error: StateFlow<ChatError?> = combine(
        _activeChatId,
        generationController.errors
    ) { cid, errs -> errs[cid] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _tokenBreakdown = MutableStateFlow(TokenBreakdown())
    val tokenBreakdown: StateFlow<TokenBreakdown> = _tokenBreakdown.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<String>>(emptyList())
    val pendingAttachments: StateFlow<List<String>> = _pendingAttachments.asStateFlow()

    sealed class DictationState {
        data object Idle : DictationState()
        data object PreparingModel : DictationState()
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
                val chat = chatRepository.getChat(chatId)
                if (chat != null) {
                    _activeChatId.value = chat.id
                    _chatTitle.value = chat.title
                    _webSearchEnabled.value = chat.webSearchEnabled
                    loadProjectContext(chat.projectId)
                    chatRepository.getMessagesFlow(chatId).collect { msgs ->
                        _messages.value = msgs
                        updateTokenBreakdown()
                    }
                }
            } else if (projectId != -1L) {
                val newChatId = chatRepository.createChat(
                    Chat(projectId = projectId, title = "New Chat")
                )
                _activeChatId.value = newChatId
                loadProjectContext(projectId)

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
    @Volatile private var preferredBackendId: String? = null

    private suspend fun loadProjectContext(pid: Long) {
        contextProjectId = pid
        val project = projectRepository.getProject(pid) ?: return
        preferredBackendId = if (project.preferredBackend == com.oli.projectsai.data.db.entity.PreferredBackend.REMOTE)
            "remote_http" else null
        val name = globalContextStore.name.first()
        val rules = globalContextStore.rules.first()
        systemPrompt = buildSystemPrompt(name, rules, project.manualContext, project.accumulatedMemory)
        refreshContextTokenBreakdown(name, rules, project.manualContext, project.accumulatedMemory)
        reloadModelIfContextDiffers(project.contextLength)
    }

    private suspend fun reloadModelIfContextDiffers(projectContextLength: Int) {
        val loaded = inferenceManager.modelState.value as? ModelState.Loaded ?: return
        if (loaded.modelInfo.contextLength == projectContextLength) return
        runCatching {
            inferenceManager.loadModel(loaded.modelInfo.copy(contextLength = projectContextLength))
        }
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
        // Framed as soft preferences, not hard rules. Phrasing matches the
        // server-side preamble so thinking-capable models don't fall into a
        // rule-checking loop where they re-evaluate each constraint against
        // each draft reply — a 15-minute-thinking failure mode we hit before.
        val parts = mutableListOf<String>()
        if (name.isNotBlank()) parts.add("You are speaking with ${name.trim()}.")
        if (rules.isNotBlank()) {
            parts.add(
                "The user has these standing guidelines (soft preferences — follow by default, " +
                    "deviate with a brief note when a specific request needs it):\n${rules.trim()}"
            )
        }
        return parts.joinToString("\n\n")
    }

    fun sendMessage(content: String) {
        val attachments = _pendingAttachments.value
        if (content.isBlank() && attachments.isEmpty()) return
        if (generationController.activeGeneration.value != null) return

        val trimmed = content.trim()
        viewModelScope.launch {
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
                generationController.clearError(activeChatId)
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

            startGeneration(trimmed, attachments, _chatTitle.value.ifBlank { trimmed.take(40) })
        }
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            try {
                val path = attachmentStore.importImage(uri)
                _pendingAttachments.value = _pendingAttachments.value + path
            } catch (t: Throwable) {
                // Attachment-import failures surface via the banner-free path; log silently.
            }
        }
    }

    fun removePendingAttachment(path: String) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it == path }
    }

    fun retryLastPrompt() {
        if (generationController.activeGeneration.value != null) return
        val last = generationController.lastFailedFor(activeChatId) ?: return
        val (content, attachments) = last
        generationController.clearError(activeChatId)
        startGeneration(content, attachments, _chatTitle.value)
    }

    fun cancelGeneration() {
        if (!isGenerating.value) return
        generationController.cancel()
    }

    fun toggleWebSearch() {
        val next = !_webSearchEnabled.value
        _webSearchEnabled.value = next
        viewModelScope.launch {
            runCatching { chatRepository.updateWebSearchEnabled(activeChatId, next) }
        }
    }

    private fun startGeneration(
        currentUserContent: String?,
        currentAttachments: List<String>,
        titleHint: String
    ) {
        val params = GenerationParams(
            chatId = activeChatId,
            currentUserContent = currentUserContent,
            currentAttachments = currentAttachments,
            systemPrompt = systemPrompt,
            webSearchEnabled = _webSearchEnabled.value,
            chatTitleHint = titleHint,
            backendId = preferredBackendId,
            // Quick Actions are short, direct operations that don't benefit from the server's
            // reasoning preamble — suppress it so responses stay concise.
            applyDefaultPreamble = quickActionId == -1L
        )
        val started = generationController.start(params)
        if (started) {
            val intent = Intent(appContext, GenerationForegroundService::class.java).apply {
                action = GenerationForegroundService.ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(appContext, intent) }
        }
    }

    fun startDictation() {
        if (dictationJob?.isActive == true) return
        _transcribedText.value = null
        dictationJob = viewModelScope.launch {
            // Transcription always uses the local backend regardless of which backend is
            // active for chat. Load the voice model on demand if it isn't resident yet.
            if (!inferenceManager.localBackendReady) {
                val path = voiceSettings.voiceModelPath.first()
                if (path.isBlank() || !File(path).exists()) {
                    _dictationState.value = DictationState.Error(
                        "No voice model set. Pick one in Settings → Voice transcription."
                    )
                    return@launch
                }
                _dictationState.value = DictationState.PreparingModel
                try {
                    inferenceManager.prepareLocalForTranscription(
                        ModelInfo(
                            name = File(path).nameWithoutExtension,
                            precision = ModelPrecision.Q4,
                            filePath = path
                        )
                    )
                } catch (t: Throwable) {
                    _dictationState.value = DictationState.Error(
                        t.message ?: "Failed to load voice model"
                    )
                    return@launch
                }
            }
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
            val text = inferenceManager.transcribeViaLocal(bytes).trim()
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

    fun dismissError() { generationController.clearError(activeChatId) }
}
