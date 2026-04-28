package com.oli.projectsai.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.attachments.AttachmentStore
import com.oli.projectsai.data.db.entity.Chat
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.data.github.RepoSelectionStore
import com.oli.projectsai.data.preferences.GlobalContextStore
import com.oli.projectsai.data.repository.ChatRepository
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.ChatError
import com.oli.projectsai.inference.ChatMessage
import com.oli.projectsai.inference.GenerationConfig
import com.oli.projectsai.inference.GenerationController
import com.oli.projectsai.inference.GenerationForegroundService
import com.oli.projectsai.inference.GenerationParams
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
    private val generationController: GenerationController,
    private val repoSelectionStore: RepoSelectionStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** Files staged from the GitHub repo browser, attached to the next outgoing turn. */
    val stagedRepoFiles: StateFlow<RepoSelectionStore.Selection?> = repoSelectionStore.staged

    fun clearStagedRepoFiles() = repoSelectionStore.clear()

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
        /** Microphone open, audio streaming. [partialText] updates in real time as words are recognised. */
        data class Recording(val partialText: String = "") : DictationState()
        data object Transcribing : DictationState()
        data class Error(val message: String) : DictationState()
    }

    private val _dictationState = MutableStateFlow<DictationState>(DictationState.Idle)
    val dictationState: StateFlow<DictationState> = _dictationState.asStateFlow()

    private val _transcribedText = MutableStateFlow<String?>(null)
    val transcribedText: StateFlow<String?> = _transcribedText.asStateFlow()

    /** Normalised mic level 0..1, updated ~10 Hz while recording. Drives the waveform bars. */
    private val _dictationRms = MutableStateFlow(0f)
    val dictationRms: StateFlow<Float> = _dictationRms.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

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
                        contextMemoryTokenLimit = p.memoryTokenLimit
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
    @Volatile private var contextMemoryTokenLimit: Int = Int.MAX_VALUE
    /** Project context length, forwarded to the remote backend as num_ctx. */
    @Volatile private var contextWindowTokens: Int = 16384

    private suspend fun loadProjectContext(pid: Long) {
        contextProjectId = pid
        val project = projectRepository.getProject(pid) ?: return
        preferredBackendId = if (project.preferredBackend == com.oli.projectsai.data.db.entity.PreferredBackend.REMOTE)
            "remote_http" else null
        contextMemoryTokenLimit = project.memoryTokenLimit
        contextWindowTokens = project.contextLength
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

    private fun trimMemoryToLimit(memory: String): String {
        if (contextMemoryTokenLimit >= Int.MAX_VALUE || contextMemoryTokenLimit <= 0) return memory
        val charBudget = contextMemoryTokenLimit * 4  // ~4 chars/token (Gemma SentencePiece calibrated)
        return if (memory.length > charBudget) {
            memory.take(charBudget) + "\n[memory truncated — compress in Memory settings]"
        } else memory
    }

    private fun buildSystemPrompt(
        name: String,
        rules: String,
        manualContext: String,
        memory: String
    ): String = buildList {
        val globalBlock = buildGlobalBlock(name, rules)
        if (globalBlock.isNotBlank()) add(globalBlock)
        if (manualContext.isNotBlank()) add("<project_context>\n$manualContext\n</project_context>")
        if (memory.isNotBlank()) add("<memory>\n${trimMemoryToLimit(memory)}\n</memory>")
    }.joinToString("\n\n")

    /** Wraps the staged repo files in an XML block the model can refer back to. */
    private fun buildRepoContextBlock(selection: RepoSelectionStore.Selection?): String {
        if (selection == null || selection.files.isEmpty()) return ""
        val header = "<repo_context owner=\"${selection.owner}\" repo=\"${selection.repo}\" ref=\"${selection.ref}\">"
        val body = selection.files.joinToString("\n") { f ->
            "<file path=\"${f.path}\">\n${f.text}\n</file>"
        }
        return "$header\n$body\n</repo_context>"
    }

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
            memory = inferenceManager.countTokens(trimMemoryToLimit(memory)),
            contextLimit = inferenceManager.contextLimitFlow.value
        )
    }

    private fun buildGlobalBlock(name: String, rules: String): String {
        // Framed as soft preferences, not hard rules, and wrapped in <user_profile> so the model
        // clearly distinguishes this section from project facts and memory. Phrasing matches the
        // server-side preamble so thinking-capable models don't fall into a rule-checking loop
        // where they re-evaluate each constraint against each draft reply — a 15-minute-thinking
        // failure mode we hit before.
        val parts = mutableListOf<String>()
        if (name.isNotBlank()) parts.add("You are speaking with ${name.trim()}.")
        if (rules.isNotBlank()) {
            parts.add(
                "The user has these standing guidelines (soft preferences — follow by default, " +
                    "deviate with a brief note when a specific request needs it):\n${rules.trim()}"
            )
        }
        if (parts.isEmpty()) return ""
        return "<user_profile>\n${parts.joinToString("\n\n")}\n</user_profile>"
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

    fun regenerateLastResponse() {
        if (generationController.activeGeneration.value != null) return
        val msgs = _messages.value
        val lastAssistant = msgs.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
        // Only regenerate if the assistant's reply is the most recent message — otherwise
        // the user has typed something in between and a regenerate is ambiguous.
        if (msgs.last().id != lastAssistant.id) return
        viewModelScope.launch {
            runCatching { chatRepository.softDeleteMessage(lastAssistant.id) }
            // Pass null so generation reuses the (now last) user message from the DB
            // verbatim instead of appending it again.
            startGeneration(null, emptyList(), _chatTitle.value)
        }
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
        // Cap output to the remaining context budget. Subtract a small buffer for the current
        // user message which may not yet be counted in the breakdown (flow hasn't fired yet).
        // Falls back to 16000 when context size is unknown (no model loaded).
        val adaptiveMaxTokens = run {
            val bd = _tokenBreakdown.value
            if (bd.contextLimit > 0 && bd.remaining > 0) (bd.remaining - 200).coerceIn(256, 16000)
            else 16000
        }
        // Pull the staged repo files (if any) and append them to the system prompt for this
        // turn. The block is then cleared so follow-up turns don't keep re-injecting them —
        // the user can re-stage from the browser whenever they need them again.
        val stagedSelection = repoSelectionStore.staged.value
        val repoBlock = buildRepoContextBlock(stagedSelection)
        val effectiveSystemPrompt = if (repoBlock.isBlank()) systemPrompt
        else if (systemPrompt.isBlank()) repoBlock
        else "$systemPrompt\n\n$repoBlock"
        if (stagedSelection != null) repoSelectionStore.clear()

        val params = GenerationParams(
            chatId = activeChatId,
            currentUserContent = currentUserContent,
            currentAttachments = currentAttachments,
            systemPrompt = effectiveSystemPrompt,
            webSearchEnabled = _webSearchEnabled.value,
            chatTitleHint = titleHint,
            backendId = preferredBackendId,
            // Quick Actions are short, direct operations that don't benefit from the server's
            // reasoning preamble — suppress it so responses stay concise.
            applyDefaultPreamble = quickActionId == -1L,
            maxOutputTokens = adaptiveMaxTokens,
            // Forwarded as Ollama's num_ctx for remote calls. Without it the 26B silently
            // ran at the 2048 default regardless of the project's contextLength setting.
            numCtx = contextWindowTokens
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
        if (_dictationState.value != DictationState.Idle) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _dictationState.value = DictationState.Error("Speech recognition is not available on this device.")
            return
        }
        _transcribedText.value = null

        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _dictationState.value = DictationState.Recording()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // onRmsChanged range is roughly -2..10; normalise to 0..1
                _dictationRms.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _dictationState.value = DictationState.Transcribing
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                _dictationState.value = DictationState.Recording(partialText = partial)
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                _dictationRms.value = 0f
                if (text.isNotBlank()) {
                    _transcribedText.value = text
                    _dictationState.value = DictationState.Idle
                } else {
                    _dictationState.value = DictationState.Error("Nothing was heard — try again.")
                }
                destroySpeechRecognizer()
            }
            override fun onError(error: Int) {
                _dictationRms.value = 0f
                _dictationState.value = DictationState.Error(
                    when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                        SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand — try speaking more clearly"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy — try again"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — try again"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER ->
                            "Network error — enable offline speech in system Settings → General management → Language"
                        else -> "Recognition failed"
                    }
                )
                destroySpeechRecognizer()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    /** Finishes the current utterance and waits for the final result. */
    fun stopDictation() {
        speechRecognizer?.stopListening()
    }

    fun cancelDictation() {
        _dictationRms.value = 0f
        destroySpeechRecognizer()
        _dictationState.value = DictationState.Idle
    }

    private fun destroySpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
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

    override fun onCleared() {
        destroySpeechRecognizer()
        super.onCleared()
    }
}
