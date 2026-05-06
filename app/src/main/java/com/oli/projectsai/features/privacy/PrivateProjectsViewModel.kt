package com.oli.projectsai.features.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.db.entity.Project
import com.oli.projectsai.features.privacy.data.PrivacySession
import com.oli.projectsai.core.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrivateProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val privacySession: PrivacySession
) : ViewModel() {

    val isUnlocked: StateFlow<Boolean> = privacySession.isUnlocked

    val projects: StateFlow<List<Project>> = projectRepository.getSecretProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            projectRepository.deleteProject(project)
        }
    }

    fun lock() {
        privacySession.lock()
    }
}
