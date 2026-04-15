package com.oli.projectsai.ui.memory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.ChatMessage
import com.oli.projectsai.inference.GenerationConfig
import com.oli.projectsai.inference.InferenceError
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
import com.oli.projectsai.inference.SummarisationPrompts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    private val projectId: Long = savedStateHandle.get<Long>("projectId") ?: -1L

    val project: StateFlow<Project?> = projectRepository.getProjectFlow(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _memoryText = MutableStateFlow("")
    val memoryText: StateFlow<String> = _memoryText.asStateFlow()

    private val _memoryTokenCount = MutableStateFlow(0)
    val memoryTokenCount: StateFlow<Int> = _memoryTokenCount.asStateFlow()

    private val _pinnedMemories = MutableStateFlow<List<String>>(emptyList())
    val pinnedMemories: StateFlow<List<String>> = _pinnedMemories.asStateFlow()

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()

    private val _compressError = MutableStateFlow<String?>(null)
    val compressError: StateFlow<String?> = _compressError.asStateFlow()

    val isModelLoaded: Boolean
        get() = inferenceManager.modelState.value is ModelState.Loaded

    init {
        viewModelScope.launch {
            project.filterNotNull().collect { p ->
                _memoryText.value = p.accumulatedMemory
                _pinnedMemories.value = p.pinnedMemories
            }
        }
        viewModelScope.launch {
            combine(
                _memoryText,
                inferenceManager.tokenizerVersion
            ) { text, _ -> text }.collect { text ->
                _memoryTokenCount.value = inferenceManager.countTokens(text)
            }
        }
    }

    fun startEditing() { _isEditing.value = true }

    fun updateMemoryText(text: String) {
        _memoryText.value = text
    }

    fun saveMemory() {
        viewModelScope.launch {
            projectRepository.updateMemory(projectId, _memoryText.value)
            _isEditing.value = false
        }
    }

    fun cancelEditing() {
        viewModelScope.launch {
            project.value?.let {
                _memoryText.value = it.accumulatedMemory
            }
            _isEditing.value = false
        }
    }

    fun pinLine(line: String) {
        val current = _pinnedMemories.value.toMutableList()
        if (line !in current) {
            current.add(line)
            _pinnedMemories.value = current
            viewModelScope.launch {
                projectRepository.updatePinnedMemories(projectId, current)
            }
        }
    }

    fun unpinLine(line: String) {
        val current = _pinnedMemories.value.toMutableList()
        current.remove(line)
        _pinnedMemories.value = current
        viewModelScope.launch {
            projectRepository.updatePinnedMemories(projectId, current)
        }
    }

    fun promoteToManualContext(text: String) {
        viewModelScope.launch {
            val project = projectRepository.getProject(projectId) ?: return@launch
            val newContext = if (project.manualContext.isBlank()) text
            else "${project.manualContext}\n\n$text"
            projectRepository.updateManualContext(projectId, newContext)
        }
    }

    fun compressMemory() {
        if (_isCompressing.value) return
        val existing = _memoryText.value
        if (existing.isBlank()) return
        val pinned = _pinnedMemories.value

        _isCompressing.value = true
        _compressError.value = null

        viewModelScope.launch {
            try {
                if (inferenceManager.modelState.value !is ModelState.Loaded) {
                    throw InferenceError.ModelNotLoaded
                }
                val (system, user) = SummarisationPrompts.buildCompressPrompt(existing, pinned)
                val out = StringBuilder()
                inferenceManager.generate(
                    systemPrompt = system,
                    messages = listOf(ChatMessage(role = "user", content = user)),
                    config = GenerationConfig()
                ).collect { chunk -> out.append(chunk) }

                val compressed = ensurePinnedPresent(out.toString().trim(), pinned)
                if (compressed.isBlank()) {
                    _compressError.value = "Model returned an empty response."
                } else {
                    projectRepository.updateMemory(projectId, compressed)
                }
            } catch (ie: InferenceError.ModelNotLoaded) {
                _compressError.value = "Load a model before compressing memory."
            } catch (t: Throwable) {
                _compressError.value = t.message ?: "Compression failed"
            } finally {
                _isCompressing.value = false
            }
        }
    }

    fun dismissCompressError() { _compressError.value = null }

    private fun ensurePinnedPresent(output: String, pinned: List<String>): String {
        if (pinned.isEmpty()) return output
        val missing = pinned.filter { line -> line.isNotBlank() && line !in output }
        if (missing.isEmpty()) return output
        val separator = if (output.endsWith("\n\n---\n\n")) "" else "\n\n---\n\n"
        return output + separator + missing.joinToString("\n")
    }
}
