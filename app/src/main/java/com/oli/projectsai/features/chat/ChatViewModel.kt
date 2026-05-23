package com.oli.projectsai.features.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.attachments.AttachmentStore
import com.oli.projectsai.core.db.entity.Chat
import com.oli.projectsai.core.db.entity.Message
import com.oli.projectsai.core.db.entity.MessageRole
import com.oli.projectsai.features.repo.github.RepoSelectionStore
import com.oli.projectsai.core.preferences.AssistantSettings
import com.oli.projectsai.core.preferences.GlobalContextStore
import com.oli.projectsai.core.preferences.SearchDepth
import com.oli.projectsai.core.preferences.SearchSettings
import com.oli.projectsai.core.repository.ChatRepository
import com.oli.projectsai.core.repository.ProjectRepository
import com.oli.projectsai.core.inference.ChatError
import com.oli.projectsai.core.inference.ChatMessage
import com.oli.projectsai.core.inference.ContextBudget
import com.oli.projectsai.core.inference.composeSystemPrompt
import com.oli.projectsai.core.inference.toolInstructionsFor
import com.oli.projectsai.core.inference.GenerationConfig
import com.oli.projectsai.core.inference.GenerationController
import com.oli.projectsai.core.inference.GenerationForegroundService
import com.oli.projectsai.core.inference.GenerationParams
import com.oli.projectsai.core.inference.InferenceError
import com.oli.projectsai.core.inference.InferenceManager
import com.oli.projectsai.core.inference.ModelState
import com.oli.projectsai.core.inference.SummarisationPrompts
import com.oli.projectsai.core.ui.common.copyToClipboard
import com.oli.projectsai.core.ui.common.shareText
import com.oli.projectsai.core.ui.components.TokenBreakdown
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val searchSettings: SearchSettings,
    private val assistantSettings: AssistantSettings,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /** Files staged from the GitHub repo browser, attached to the next outgoing turn. */
    val stagedRepoFiles: StateFlow<RepoSelectionStore.Selection?> = repoSelectionStore.staged

    fun clearStagedRepoFiles() = repoSelectionStore.clear()

    private val chatId: Long = savedStateHandle.get<Long>("chatId") ?: -1L
    private val projectId: Long = savedStateHandle.get<Long>("projectId") ?: -1L
    private val quickActionId: Long = savedStateHandle.get<Long>("quickActionId") ?: -1L
    /** Optional message id from a search-result deeplink; -1L means "no target". */
    private val initialTargetMessageId: Long =
        savedStateHandle.get<Long>("messageId") ?: -1L

    private val _targetMessageId = MutableStateFlow(initialTargetMessageId.takeIf { it > 0L })
    val targetMessageId: StateFlow<Long?> = _targetMessageId.asStateFlow()
    fun consumeTargetMessageId() { _targetMessageId.value = null }

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

    private val dictationManager = DictationManager(appContext)
    val dictationState: StateFlow<DictationState> = dictationManager.state
    val dictationRms: StateFlow<Float> = dictationManager.rms
    val transcribedText: StateFlow<String?> = dictationManager.transcribed

    private val _chatTitle = MutableStateFlow("New Chat")
    val chatTitle: StateFlow<String> = _chatTitle.asStateFlow()

    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()

    /**
     * One-shot snackbar message — currently only fires when the user enables web search on a
     * chat where no SearXNG endpoint is configured, so the toggle wouldn't actually do anything.
     */
    private val _toggleWarning = MutableStateFlow<String?>(null)
    val toggleWarning: StateFlow<String?> = _toggleWarning.asStateFlow()
    fun consumeToggleWarning() { _toggleWarning.value = null }

    /** UI states for the compaction prompt / progress. */
    enum class CompactState { Idle, Running, Done, Failed }
    private val _compactState = MutableStateFlow(CompactState.Idle)
    val compactState: StateFlow<CompactState> = _compactState.asStateFlow()

    /**
     * Per-session dismissal of the compaction banner. Auto-resets when usage drops back below
     * 0.70 — so if the user dismisses at 76 % and the chat balloons further it doesn't nag,
     * but a future chat that grows past the threshold again will re-prompt.
     */
    private val _compactPromptDismissed = MutableStateFlow(false)
    fun dismissCompactPrompt() { _compactPromptDismissed.value = true }

    /**
     * Surfaces the compaction banner only when there is something to compact AND we're not
     * mid-response. Conversation must have enough material to summarise (>= 4 turns) so a
     * project with a huge memory block but an empty chat doesn't trigger it.
     */
    val showCompactPrompt: StateFlow<Boolean> = combine(
        _tokenBreakdown,
        isGenerating,
        _compactState,
        _compactPromptDismissed,
        _messages,
    ) { bd, generating, state, dismissed, msgs ->
        bd.usagePercent > 0.75f &&
            !generating &&
            state == CompactState.Idle &&
            !dismissed &&
            msgs.count { it.role != MessageRole.SYSTEM } >= 4
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * UI default for whether finalised <think> blocks render expanded. Streaming blocks
     * always show their tail expanded regardless — see ThinkAwareMarkdown in ChatScreen.
     */
    val showReasoningByDefault: StateFlow<Boolean> = assistantSettings.showReasoningByDefault
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // These MUST be declared before `init`. The init block collects `repoSelectionStore.staged`,
    // a StateFlow that emits its current value synchronously on subscription; under
    // viewModelScope's Main.immediate dispatcher that runs `recomputeBreakdown()` inline during
    // construction. If these fields were declared after `init` they'd still hold JVM zero-values
    // at that point (notably `currentMemory == null` despite its non-null type), crashing the VM
    // the moment any chat screen opens.
    private var contextProjectId: Long = -1L
    @Volatile private var preferredBackendId: String? = null
    @Volatile private var contextMemoryTokenLimit: Int = Int.MAX_VALUE
    /** Project context length, forwarded to the remote backend as num_ctx. */
    @Volatile private var contextWindowTokens: Int = 16384

    // Cached project/global inputs to the system prompt, so [recomputeBreakdown] can size the
    // memory segment without re-reading the project on every message tick.
    @Volatile private var currentName: String = ""
    @Volatile private var currentRules: String = ""
    @Volatile private var currentMemory: String = ""

    init {
        // Re-arm the compact banner if usage drops back below 0.70 after the user has dismissed
        // it. Lets a user clear context (e.g. via Add to Memory + manual prune) without the
        // dismiss flag suppressing future warnings.
        viewModelScope.launch {
            _tokenBreakdown.collect { bd ->
                if (bd.usagePercent < 0.70f && _compactPromptDismissed.value) {
                    _compactPromptDismissed.value = false
                }
            }
        }
        viewModelScope.launch {
            combine(
                inferenceManager.tokenizerVersion,
                inferenceManager.contextLimitFlow,
                globalContextStore.name,
                globalContextStore.rules
            ) { _, _, name, rules -> name to rules }.collect { (name, rules) ->
                currentName = name
                currentRules = rules
                if (contextProjectId != -1L) {
                    val p = projectRepository.getProject(contextProjectId)
                    if (p != null) {
                        contextMemoryTokenLimit = p.memoryTokenLimit
                        currentMemory = p.accumulatedMemory
                        systemPrompt = PromptBuilder.buildSystemPrompt(
                            name, rules, p.manualContext, p.accumulatedMemory, contextMemoryTokenLimit,
                        )
                    }
                }
                recomputeBreakdown()
            }
        }
        // Staged GitHub repo files count toward the prompt that gets sent next turn, so keep the
        // bar honest as the user stages/clears them.
        viewModelScope.launch {
            repoSelectionStore.staged.collect { recomputeBreakdown() }
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

    /**
     * The full context window the project is configured for — the value sent to Ollama as
     * `num_ctx` (remote) and the value the local backend is loaded with (see
     * [reloadModelIfContextDiffers]). The loaded-model's `modelInfo.contextLength` can lag — e.g.
     * when the remote backend was loaded with the default 16384 but the project setting is 32768 —
     * so reading it instead would size the budget against the wrong limit. Falls back to the
     * model's value only when no project length has been resolved yet.
     */
    private fun contextWindow(): Int {
        if (contextWindowTokens > 0) return contextWindowTokens
        return inferenceManager.contextLimitFlow.value
    }

    private suspend fun loadProjectContext(pid: Long) {
        contextProjectId = pid
        val project = projectRepository.getProject(pid) ?: return
        applyProject(project)
        reloadModelIfContextDiffers(project.contextLength)

        // Live-watch the project so edits to contextLength / memoryTokenLimit / manual context /
        // accumulated memory propagate without needing to reopen the chat. Without this the
        // breakdown's contextLimit would be frozen at chat-open time.
        viewModelScope.launch {
            projectRepository.getProjectFlow(pid).collect { p ->
                if (p != null) applyProject(p)
            }
        }
    }

    private suspend fun applyProject(project: com.oli.projectsai.core.db.entity.Project) {
        preferredBackendId = if (project.preferredBackend == com.oli.projectsai.core.db.entity.PreferredBackend.REMOTE)
            "remote_http" else null
        contextMemoryTokenLimit = project.memoryTokenLimit
        contextWindowTokens = project.contextLength
        val name = globalContextStore.name.first()
        val rules = globalContextStore.rules.first()
        currentName = name
        currentRules = rules
        currentMemory = project.accumulatedMemory
        systemPrompt = PromptBuilder.buildSystemPrompt(
            name, rules, project.manualContext, project.accumulatedMemory, contextMemoryTokenLimit,
        )
        recomputeBreakdown()
    }

    /**
     * Reloads the *currently active* backend with the project's context length when it differs.
     * Only acts when the active backend matches the project's preferred backend — otherwise a
     * remote-preferred project with a stale local model loaded would force-reload local with the
     * wrong context length (and vice versa). The right backend gets loaded later via the
     * user's normal flow (Settings → connect, or the model picker).
     */
    private suspend fun reloadModelIfContextDiffers(projectContextLength: Int) {
        val loaded = inferenceManager.modelState.value as? ModelState.Loaded ?: return
        if (loaded.modelInfo.contextLength == projectContextLength) return
        val activeBackendId = inferenceManager.getActiveBackend()?.id ?: return
        val targetBackendId = preferredBackendId ?: "local_litertlm"
        if (activeBackendId != targetBackendId) return
        runCatching {
            inferenceManager.loadModel(
                loaded.modelInfo.copy(contextLength = projectContextLength),
                backendId = activeBackendId,
            )
        }
    }

    /**
     * Recomputes the token bar so it reflects *exactly* what the next generation will send:
     * the same effective system prompt ([composeSystemPrompt]) and the same sliding-window trim
     * ([ContextBudget.fit]) the [GenerationController] uses. Splitting the result into the bar's
     * system/memory/conversation segments is presentation only — the totals and the limit are the
     * real budgeted figures, so the bar and the model can no longer disagree.
     */
    private suspend fun recomputeBreakdown() {
        val window = contextWindow()
        val reserve = ContextBudget.outputReserveFor(window)
        val inputBudget = ContextBudget.inputBudgetFor(window, reserve)

        val memoryText = PromptBuilder.trimMemoryToLimit(currentMemory, contextMemoryTokenLimit)
        val memoryTokens = inferenceManager.countTokens(memoryText)

        // Build the effective system prompt the same way generation will: project base +
        // staged repo files + web-search tool instructions + temporal block.
        val repoBlock = PromptBuilder.buildRepoContextBlock(repoSelectionStore.staged.value)
        val base = listOf(systemPrompt, repoBlock).filter { it.isNotBlank() }.joinToString("\n\n")
        val depth = if (_webSearchEnabled.value) searchSettings.searchDepth.first() else SearchDepth.AUTO_FETCH
        val effectiveSystem = composeSystemPrompt(base, toolInstructionsFor(_webSearchEnabled.value, depth))
        val systemTokens = inferenceManager.countTokens(effectiveSystem)

        val counts = _messages.value.map {
            it.tokenCount.takeIf { c -> c > 0 } ?: ContextBudget.estimateTokens(it.content)
        }
        val fit = ContextBudget.fit(window, systemTokens, counts, reserve)

        _tokenBreakdown.value = TokenBreakdown(
            // Memory is part of the system prompt; show it as its own segment and attribute the
            // remainder (global profile + project context + temporal + tool/repo blocks) to system.
            systemPrompt = (systemTokens - memoryTokens).coerceAtLeast(0),
            memory = memoryTokens,
            conversation = fit.conversationTokens,
            contextLimit = inputBudget,
            droppedTurns = fit.droppedCount,
            reservedOutput = reserve,
        )
    }

    fun sendMessage(content: String) {
        val attachments = _pendingAttachments.value
        if (content.isBlank() && attachments.isEmpty()) return
        if (generationController.activeGeneration.value != null) return
        if (_compactState.value == CompactState.Running) return

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
            // Tool instructions enter/leave the system prompt with this toggle — keep the bar honest.
            recomputeBreakdown()
            if (next && searchSettings.searxngUrl.first().isBlank()) {
                _toggleWarning.value =
                    "Web search is on but no SearXNG instance is configured. Add one in Settings → Web search."
            }
        }
    }

    private fun startGeneration(
        currentUserContent: String?,
        currentAttachments: List<String>,
        titleHint: String,
    ) {
        // Pull the staged repo files (if any) and append them to the system prompt for this
        // turn. The block is then cleared so follow-up turns don't keep re-injecting them —
        // the user can re-stage from the browser whenever they need them again.
        val stagedSelection = repoSelectionStore.staged.value
        val repoBlock = PromptBuilder.buildRepoContextBlock(stagedSelection)
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
            // Ceiling only — GenerationController clamps this down to the exact tokens left in the
            // window after the (post-trim) prompt, so output can never push the request past num_ctx.
            maxOutputTokens = 16000,
            // Forwarded as Ollama's num_ctx for remote calls. Without it the 26B silently
            // ran at the 2048 default regardless of the project's contextLength setting.
            numCtx = contextWindowTokens,
        )
        val started = generationController.start(params)
        if (started) {
            val intent = Intent(appContext, GenerationForegroundService::class.java).apply {
                action = GenerationForegroundService.ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(appContext, intent) }
        }
    }

    fun startDictation() = dictationManager.start()
    /** Finishes the current utterance and waits for the final result. */
    fun stopDictation() = dictationManager.stop()
    fun cancelDictation() = dictationManager.cancel()
    fun consumeTranscribedText(): String? = dictationManager.consumeTranscribed()
    fun dismissDictationError() = dictationManager.dismissError()

    private suspend fun updateTokenBreakdown() = recomputeBreakdown()

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

    /**
     * Replaces the live conversation with a single SYSTEM "earlier conversation summary"
     * message so the model can keep chatting without the full transcript in context. Old
     * messages are soft-deleted (recoverable via the sync layer), not hard-removed. No-op if
     * a generation is in flight or there's nothing to compact.
     */
    fun compactConversation() {
        if (generationController.activeGeneration.value != null) return
        if (_compactState.value == CompactState.Running) return
        val msgs = _messages.value.filter { it.role != MessageRole.SYSTEM }
        if (msgs.size < 2) return
        if (inferenceManager.modelState.value !is ModelState.Loaded) {
            _compactState.value = CompactState.Failed
            return
        }
        _compactState.value = CompactState.Running
        viewModelScope.launch {
            val transcript = msgs.joinToString("\n\n") { msg ->
                val role = if (msg.role == MessageRole.USER) "User" else "Assistant"
                "$role: ${msg.content}"
            }
            val summary = runCatching {
                val (system, user) = SummarisationPrompts.buildConversationCompactionPrompt(transcript)
                val out = StringBuilder()
                inferenceManager.generate(
                    systemPrompt = system,
                    messages = listOf(ChatMessage(role = "user", content = user)),
                    config = GenerationConfig(),
                ).collect { chunk -> out.append(chunk) }
                out.toString().trim()
            }.getOrNull()

            if (summary.isNullOrBlank()) {
                _compactState.value = CompactState.Failed
                return@launch
            }

            runCatching {
                _messages.value.forEach { chatRepository.softDeleteMessage(it.id) }
                chatRepository.addMessage(
                    Message(
                        chatId = activeChatId,
                        role = MessageRole.SYSTEM,
                        content = "[Earlier conversation summary]\n\n$summary",
                        tokenCount = inferenceManager.countTokens(summary),
                    )
                )
            }.onFailure {
                _compactState.value = CompactState.Failed
                return@launch
            }
            _compactPromptDismissed.value = false
            _compactState.value = CompactState.Idle
        }
    }

    fun resetCompactState() { _compactState.value = CompactState.Idle }

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
        dictationManager.close()
        super.onCleared()
    }
}
