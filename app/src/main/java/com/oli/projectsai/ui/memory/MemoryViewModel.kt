package com.oli.projectsai.ui.memory

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.InferenceManager
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
        // In the full implementation, this would send the memory to the model
        // with a compression prompt. For now, it's a placeholder.
        viewModelScope.launch {
            // TODO: Send to model with prompt:
            // "Compress and consolidate the following memory notes. Remove redundancy,
            //  merge related items, and keep only facts, decisions, and actionable info.
            //  Preserve all pinned items exactly as written."
        }
    }
}
