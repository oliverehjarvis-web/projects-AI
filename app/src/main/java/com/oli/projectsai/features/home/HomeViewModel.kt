package com.oli.projectsai.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.db.entity.Project
import com.oli.projectsai.core.db.relation.MessageSearchHit
import com.oli.projectsai.core.repository.ChatRepository
import com.oli.projectsai.core.repository.ProjectRepository
import com.oli.projectsai.core.inference.InferenceManager
import com.oli.projectsai.core.inference.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val chatRepository: ChatRepository,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    val projects: StateFlow<List<Project>> = projectRepository.getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelState: StateFlow<ModelState> = inferenceManager.modelState

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(value: String) { _searchQuery.value = value }
    fun clearSearch() { _searchQuery.value = "" }

    /**
     * Debounced search results across every (visible) chat. Empty when the trimmed query is
     * blank, so the home screen falls back to the project list. Returns up to 50 hits ordered
     * newest first — see [com.oli.projectsai.core.db.dao.MessageDao.searchMessages].
     */
    val searchResults: StateFlow<List<MessageSearchHit>> = _searchQuery
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0 else 250 }
        .flatMapLatest { q ->
            flow {
                if (q.isEmpty()) emit(emptyList())
                else emit(chatRepository.searchMessages(q))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            projectRepository.deleteProject(project)
        }
    }
}
