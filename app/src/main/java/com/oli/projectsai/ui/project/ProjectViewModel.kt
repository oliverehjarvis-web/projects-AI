package com.oli.projectsai.ui.project

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.db.entity.Chat
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.data.db.entity.QuickAction
import com.oli.projectsai.data.repository.ChatRepository
import com.oli.projectsai.data.repository.ProjectRepository
import com.oli.projectsai.inference.InferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val chatRepository: ChatRepository,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    private val projectId: Long = savedStateHandle.get<Long>("projectId") ?: -1L

    val project: StateFlow<Project?> = projectRepository.getProjectFlow(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chats: StateFlow<List<Chat>> = chatRepository.getChatsByProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quickActions: StateFlow<List<QuickAction>> = projectRepository.getQuickActions(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _contextTokenCount = MutableStateFlow(0)
    val contextTokenCount: StateFlow<Int> = _contextTokenCount.asStateFlow()

    private val _memoryTokenCount = MutableStateFlow(0)
    val memoryTokenCount: StateFlow<Int> = _memoryTokenCount.asStateFlow()

    init {
        viewModelScope.launch {
            combine(project.filterNotNull(), inferenceManager.tokenizerVersion) { p, _ -> p }
                .collect { p ->
                    _contextTokenCount.value = inferenceManager.countTokens(p.manualContext)
                    _memoryTokenCount.value = inferenceManager.countTokens(p.accumulatedMemory)
                }
        }
    }

    fun deleteChat(chat: Chat) {
        viewModelScope.launch { chatRepository.deleteChat(chat) }
    }

    fun deleteChats(ids: List<Long>) {
        viewModelScope.launch { chatRepository.deleteChats(ids) }
    }

    fun createQuickAction(name: String, template: String) {
        viewModelScope.launch {
            projectRepository.createQuickAction(
                QuickAction(projectId = projectId, name = name, promptTemplate = template)
            )
        }
    }

    fun deleteQuickAction(action: QuickAction) {
        viewModelScope.launch { projectRepository.deleteQuickAction(action) }
    }

    fun deleteProject() {
        viewModelScope.launch {
            project.value?.let { projectRepository.deleteProject(it) }
        }
    }
}
