package com.oli.projectsai.ui.project

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.appscript.AppScriptClient
import com.oli.projectsai.data.appscript.AppScriptSecretStore
import com.oli.projectsai.data.appscript.GoogleOAuthManager
import com.oli.projectsai.data.appscript.ResolvedAppScriptTool
import com.oli.projectsai.data.db.dao.AppScriptToolDao
import com.oli.projectsai.data.db.entity.AppScriptAuthMode
import com.oli.projectsai.data.db.entity.AppScriptTool
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.preferences.RemoteSettings
import com.oli.projectsai.data.privacy.PrivacySession
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.ChatMessage
import com.oli.projectsai.inference.ContextSizing
import com.oli.projectsai.inference.GenerationConfig
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelInfo
import com.oli.projectsai.inference.ModelPrecision
import com.oli.projectsai.inference.ModelState
import com.oli.projectsai.inference.SummarisationPrompts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ProjectEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val inferenceManager: InferenceManager,
    private val contextSizing: ContextSizing,
    private val appScriptToolDao: AppScriptToolDao,
    private val appScriptSecretStore: AppScriptSecretStore,
    private val appScriptClient: AppScriptClient,
    private val oauthManager: GoogleOAuthManager,
    remoteSettings: RemoteSettings,
    privacySession: PrivacySession
) : ViewModel() {

    /**
     * True when both server URL and API token are set, so flipping the per-project remote-backend
     * switch is meaningful. The screen warns when this is false to avoid the silent fall-back to
     * local that used to confuse the user.
     */
    val isRemoteConfigured: StateFlow<Boolean> = combine(
        remoteSettings.serverUrl, remoteSettings.apiToken
    ) { url, token -> url.isNotBlank() && token.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val canTogglePrivate: StateFlow<Boolean> = privacySession.isUnlocked

    private val projectId: Long = savedStateHandle.get<Long>("projectId") ?: -1L

    private val _isNew = MutableStateFlow(projectId == -1L)
    val isNew: StateFlow<Boolean> = _isNew.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    private val _manualContext = MutableStateFlow("")
    val manualContext: StateFlow<String> = _manualContext.asStateFlow()

    private val _memoryTokenLimit = MutableStateFlow(4000)
    val memoryTokenLimit: StateFlow<Int> = _memoryTokenLimit.asStateFlow()

    private val _contextLength = MutableStateFlow(16384)
    val contextLength: StateFlow<Int> = _contextLength.asStateFlow()

    private val _isSecret = MutableStateFlow(false)
    val isSecret: StateFlow<Boolean> = _isSecret.asStateFlow()

    private val _useRemoteBackend = MutableStateFlow(false)
    val useRemoteBackend: StateFlow<Boolean> = _useRemoteBackend.asStateFlow()

    private val _contextTokenCount = MutableStateFlow(0)
    val contextTokenCount: StateFlow<Int> = _contextTokenCount.asStateFlow()

    private val _isRefining = MutableStateFlow(false)
    val isRefining: StateFlow<Boolean> = _isRefining.asStateFlow()

    private val _refineError = MutableStateFlow<String?>(null)
    val refineError: StateFlow<String?> = _refineError.asStateFlow()

    val contextLengthOptions: List<Int> = ContextSizing.PICKER_STEPS

    private val _autoContextHint = MutableStateFlow<String?>(null)
    /** Human-readable rationale shown next to the picker after Auto runs. */
    val autoContextHint: StateFlow<String?> = _autoContextHint.asStateFlow()

    private val _isComputingAutoContext = MutableStateFlow(false)
    val isComputingAutoContext: StateFlow<Boolean> = _isComputingAutoContext.asStateFlow()

    private var existingProject: Project? = null

    // ── Apps Script tools (per-project data sources) ─────────────────────────────

    val tools: StateFlow<List<AppScriptTool>> =
        if (projectId != -1L) appScriptToolDao.getByProject(projectId)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        else flowOf(emptyList<AppScriptTool>())
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _editingTool = MutableStateFlow<AppScriptTool?>(null)
    val editingTool: StateFlow<AppScriptTool?> = _editingTool.asStateFlow()

    private val _editorOpen = MutableStateFlow(false)
    val editorOpen: StateFlow<Boolean> = _editorOpen.asStateFlow()

    val googleSignedIn: StateFlow<Boolean> = oauthManager.signedIn
    val googleAccountEmail: StateFlow<String?> = oauthManager.accountEmail
    val googleWebClientConfigured: Boolean get() = oauthManager.webClientIdConfigured

    private val _oauthConsentIntent = MutableStateFlow<PendingIntent?>(null)
    val oauthConsentIntent: StateFlow<PendingIntent?> = _oauthConsentIntent.asStateFlow()

    private val _testCallResult = MutableStateFlow<String?>(null)
    val testCallResult: StateFlow<String?> = _testCallResult.asStateFlow()

    private val _toolError = MutableStateFlow<String?>(null)
    val toolError: StateFlow<String?> = _toolError.asStateFlow()

    fun openAddTool() {
        _editingTool.value = AppScriptTool(
            projectId = projectId,
            name = "",
            description = "",
            authMode = AppScriptAuthMode.SHARED_SECRET
        )
        _editorOpen.value = true
    }

    fun openEditTool(tool: AppScriptTool) {
        _editingTool.value = tool
        _editorOpen.value = true
    }

    fun closeEditor() {
        _editorOpen.value = false
        _editingTool.value = null
    }

    /**
     * Persist the in-flight tool. [secretInput] is non-null only when the user is changing
     * the secret (blank means clear it). Untouched secrets are left as-is on the existing row.
     */
    fun saveEditingTool(
        name: String,
        description: String,
        argSchemaHint: String,
        authMode: AppScriptAuthMode,
        webAppUrl: String,
        scriptId: String,
        functionName: String,
        enabled: Boolean,
        secretInput: String?
    ) {
        val draft = _editingTool.value ?: return
        if (name.isBlank()) {
            _toolError.value = "Tool name is required."
            return
        }
        if (projectId == -1L) {
            _toolError.value = "Save the project first before adding data sources."
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (draft.id == 0L) {
                val toInsert = draft.copy(
                    name = name.trim(),
                    description = description.trim(),
                    argSchemaHint = argSchemaHint.trim(),
                    authMode = authMode,
                    webAppUrl = webAppUrl.trim(),
                    scriptId = scriptId.trim(),
                    functionName = functionName.trim(),
                    enabled = enabled,
                    updatedAt = now,
                    createdAt = now
                )
                val newId = appScriptToolDao.insert(toInsert)
                if (secretInput != null && secretInput.isNotBlank()) {
                    val ref = appScriptSecretStore.putSecret(newId, secretInput)
                    appScriptToolDao.update(toInsert.copy(id = newId, secretRef = ref, updatedAt = System.currentTimeMillis()))
                }
            } else {
                val newSecretRef = when {
                    secretInput == null -> draft.secretRef               // unchanged
                    secretInput.isBlank() -> {                             // cleared
                        appScriptSecretStore.deleteSecret(draft.secretRef)
                        null
                    }
                    else -> appScriptSecretStore.putSecret(draft.id, secretInput)
                }
                appScriptToolDao.update(
                    draft.copy(
                        name = name.trim(),
                        description = description.trim(),
                        argSchemaHint = argSchemaHint.trim(),
                        authMode = authMode,
                        webAppUrl = webAppUrl.trim(),
                        scriptId = scriptId.trim(),
                        functionName = functionName.trim(),
                        enabled = enabled,
                        secretRef = newSecretRef,
                        updatedAt = now
                    )
                )
            }
            closeEditor()
        }
    }

    fun deleteTool(tool: AppScriptTool) {
        viewModelScope.launch {
            appScriptSecretStore.deleteSecret(tool.secretRef)
            appScriptToolDao.softDelete(tool.id)
        }
    }

    /** Calls the script with empty args and surfaces the raw response for debugging. */
    fun testCall(tool: AppScriptTool, secretInputOverride: String? = null) {
        viewModelScope.launch {
            _testCallResult.value = null
            _toolError.value = null
            val resolved = when (tool.authMode) {
                AppScriptAuthMode.SHARED_SECRET -> {
                    val secret = secretInputOverride
                        ?: appScriptSecretStore.getSecret(tool.secretRef)
                    ResolvedAppScriptTool(
                        name = tool.name.ifBlank { "tool" },
                        description = tool.description,
                        argSchemaHint = tool.argSchemaHint,
                        authMode = tool.authMode,
                        webAppUrl = tool.webAppUrl,
                        scriptId = "",
                        functionName = "",
                        secret = secret
                    )
                }
                AppScriptAuthMode.OAUTH -> ResolvedAppScriptTool(
                    name = tool.name.ifBlank { "tool" },
                    description = tool.description,
                    argSchemaHint = tool.argSchemaHint,
                    authMode = tool.authMode,
                    webAppUrl = "",
                    scriptId = tool.scriptId,
                    functionName = tool.functionName,
                    secret = null
                )
            }
            try {
                val raw = appScriptClient.dispatch(resolved, JSONObject())
                _testCallResult.value = raw
            } catch (t: Throwable) {
                _testCallResult.value = "Error: ${t.message ?: "unknown failure"}"
            }
        }
    }

    fun clearTestCallResult() { _testCallResult.value = null }
    fun clearToolError() { _toolError.value = null }

    fun connectGoogle() {
        viewModelScope.launch {
            when (val r = oauthManager.beginSignIn()) {
                GoogleOAuthManager.BeginResult.Success -> Unit
                is GoogleOAuthManager.BeginResult.NeedsConsent -> _oauthConsentIntent.value = r.pendingIntent
                is GoogleOAuthManager.BeginResult.Error -> _toolError.value = r.message
            }
        }
    }

    fun completeGoogleSignIn(data: Intent?) {
        _oauthConsentIntent.value = null
        oauthManager.completeSignIn(data).onFailure { _toolError.value = it.message }
    }

    fun clearOAuthConsentIntent() { _oauthConsentIntent.value = null }

    fun disconnectGoogle() = oauthManager.signOut()

    init {
        if (projectId != -1L) {
            viewModelScope.launch {
                projectRepository.getProject(projectId)?.let { p ->
                    existingProject = p
                    _name.value = p.name
                    _description.value = p.description
                    _manualContext.value = p.manualContext
                    _memoryTokenLimit.value = p.memoryTokenLimit
                    _contextLength.value = p.contextLength
                    _isSecret.value = p.isSecret
                    _useRemoteBackend.value = p.preferredBackend == com.oli.projectsai.data.db.entity.PreferredBackend.REMOTE
                }
            }
        }
        viewModelScope.launch {
            combine(_manualContext, inferenceManager.tokenizerVersion) { text, _ -> text }
                .collect { text ->
                    _contextTokenCount.value = inferenceManager.countTokens(text)
                }
        }
    }

    fun updateName(value: String) { _name.value = value }
    fun updateDescription(value: String) { _description.value = value }

    fun updateManualContext(value: String) {
        _manualContext.value = value
    }

    fun updateMemoryTokenLimit(value: Int) {
        // Cap memory to context window minus a 2k floor for the actual conversation, so the
        // user can't lock themselves out of replies by giving the entire window to memory.
        val ceiling = (_contextLength.value - 2000).coerceAtLeast(1000)
        _memoryTokenLimit.value = value.coerceIn(1000, ceiling)
    }

    fun updateContextLength(value: Int) {
        if (value in contextLengthOptions) {
            _contextLength.value = value
            // Re-clamp memory if it now exceeds the new context (leaves a 2k floor for replies).
            val ceiling = (value - 2000).coerceAtLeast(1000)
            if (_memoryTokenLimit.value > ceiling) _memoryTokenLimit.value = ceiling
        }
        _autoContextHint.value = null
    }

    /**
     * Picks a context length that fits in available RAM, on-device or on the NAS, and
     * sets it on the form. The user can still tweak afterwards.
     */
    fun autoFillContextLength() {
        viewModelScope.launch {
            _isComputingAutoContext.value = true
            try {
                val info = inferenceManager.modelState.value.let {
                    when (it) {
                        is ModelState.Loaded -> it.modelInfo
                        is ModelState.Loading -> it.modelInfo
                        else -> null
                    }
                } ?: stubModelInfoForAuto()
                val rec = if (_useRemoteBackend.value)
                    contextSizing.forRemote(info)
                else
                    contextSizing.forLocal(info)
                _contextLength.value = rec.tokens
                _autoContextHint.value = "Auto-picked ${rec.tokens / 1024}k. ${rec.rationale}"
            } finally {
                _isComputingAutoContext.value = false
            }
        }
    }

    private fun stubModelInfoForAuto(): ModelInfo = ModelInfo(
        // Best guess for a typical local model when nothing's loaded — sizing will fall
        // back to a 4B-ish footprint, which is the common case on the phone.
        name = "gemma4-e4b",
        precision = ModelPrecision.Q4,
        filePath = ""
    )

    fun refineContext() {
        val raw = _manualContext.value
        if (raw.isBlank()) return
        if (inferenceManager.modelState.value !is ModelState.Loaded) {
            _refineError.value = "Load a model first to use context refinement."
            return
        }
        viewModelScope.launch {
            _isRefining.value = true
            try {
                val (system, user) = SummarisationPrompts.buildProjectContextRefinePrompt(raw)
                val out = StringBuilder()
                inferenceManager.generate(
                    systemPrompt = system,
                    messages = listOf(ChatMessage(role = "user", content = user)),
                    config = GenerationConfig()
                ).collect { chunk -> out.append(chunk) }
                val refined = out.toString().trim()
                if (refined.isNotBlank()) updateManualContext(refined)
            } catch (t: Throwable) {
                _refineError.value = "Refinement failed: ${t.message ?: "unknown error"}"
            } finally {
                _isRefining.value = false
            }
        }
    }

    fun clearRefineError() {
        _refineError.value = null
    }

    fun updateIsSecret(value: Boolean) {
        _isSecret.value = value
    }

    fun updateUseRemoteBackend(value: Boolean) {
        _useRemoteBackend.value = value
        // Auto recommendation is backend-specific (RAM is different on phone vs NAS), so the
        // hint goes stale the moment the user flips this. Drop it; they can re-tap Auto.
        _autoContextHint.value = null
    }

    fun save() {
        viewModelScope.launch {
            val existing = existingProject
            val backend = if (_useRemoteBackend.value)
                com.oli.projectsai.data.db.entity.PreferredBackend.REMOTE
            else
                com.oli.projectsai.data.db.entity.PreferredBackend.LOCAL
            if (existing != null) {
                projectRepository.updateProject(
                    existing.copy(
                        name = _name.value.trim(),
                        description = _description.value.trim(),
                        manualContext = _manualContext.value,
                        memoryTokenLimit = _memoryTokenLimit.value,
                        contextLength = _contextLength.value,
                        isSecret = _isSecret.value,
                        preferredBackend = backend
                    )
                )
            } else {
                projectRepository.createProject(
                    Project(
                        name = _name.value.trim(),
                        description = _description.value.trim(),
                        manualContext = _manualContext.value,
                        memoryTokenLimit = _memoryTokenLimit.value,
                        contextLength = _contextLength.value,
                        isSecret = _isSecret.value,
                        preferredBackend = backend
                    )
                )
            }
        }
    }
}
