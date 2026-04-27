package com.oli.projectsai.ui.project

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.privacy.PrivacySession
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.ChatMessage
import com.oli.projectsai.inference.GenerationConfig
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
import com.oli.projectsai.inference.SummarisationPrompts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val inferenceManager: InferenceManager,
    privacySession: PrivacySession
) : ViewModel() {

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

    val contextLengthOptions: List<Int> = listOf(4096, 8192, 16384, 24576, 32768)

    private var existingProject: Project? = null

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
        _memoryTokenLimit.value = value.coerceIn(1000, 32000)
    }

    fun updateContextLength(value: Int) {
        if (value in contextLengthOptions) _contextLength.value = value
    }

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
