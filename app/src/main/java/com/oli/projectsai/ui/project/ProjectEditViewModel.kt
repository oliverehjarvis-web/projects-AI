package com.oli.projectsai.ui.project

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.InferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    private val projectId: Long = savedStateHandle.get<Long>("projectId") ?: -1L

    private val _isNew = MutableStateFlow(projectId == -1L)
    val isNew: StateFlow<Boolean> = _isNew.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    private val _manualContext = MutableStateFlow("")
    val manualContext: StateFlow<String> = _manualContext.asStateFlow()

    private val _memoryTokenLimit = MutableStateFlow(8000)
    val memoryTokenLimit: StateFlow<Int> = _memoryTokenLimit.asStateFlow()

    private val _contextTokenCount = MutableStateFlow(0)
    val contextTokenCount: StateFlow<Int> = _contextTokenCount.asStateFlow()

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
                    updateTokenCount(p.manualContext)
                }
            }
        }
    }

    fun updateName(value: String) { _name.value = value }
    fun updateDescription(value: String) { _description.value = value }

    fun updateManualContext(value: String) {
        _manualContext.value = value
        viewModelScope.launch { updateTokenCount(value) }
    }

    fun updateMemoryTokenLimit(value: Int) {
        _memoryTokenLimit.value = value.coerceIn(1000, 32000)
    }

    private suspend fun updateTokenCount(text: String) {
        _contextTokenCount.value = inferenceManager.countTokens(text)
    }

    fun save() {
        viewModelScope.launch {
            val existing = existingProject
            if (existing != null) {
                projectRepository.updateProject(
                    existing.copy(
                        name = _name.value.trim(),
                        description = _description.value.trim(),
                        manualContext = _manualContext.value,
                        memoryTokenLimit = _memoryTokenLimit.value
                    )
                )
            } else {
                projectRepository.createProject(
                    Project(
                        name = _name.value.trim(),
                        description = _description.value.trim(),
                        manualContext = _manualContext.value,
                        memoryTokenLimit = _memoryTokenLimit.value
                    )
                )
            }
        }
    }
}
