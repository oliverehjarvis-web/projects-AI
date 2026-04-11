package com.oli.projectsai.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.InferenceManager
import com.oli.projectsai.inference.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    val projects: StateFlow<List<Project>> = projectRepository.getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelState: StateFlow<ModelState> = inferenceManager.modelState

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            projectRepository.deleteProject(project)
        }
    }
}
