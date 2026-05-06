package com.oli.projectsai.features.linkedin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.db.entity.LinkedInSuggestion
import com.oli.projectsai.features.linkedin.data.LinkedInRepository
import com.oli.projectsai.core.preferences.LinkedInSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinkedInViewModel @Inject constructor(
    private val settings: LinkedInSettings,
    private val repository: LinkedInRepository
) : ViewModel() {

    val agentUrl: StateFlow<String> = settings.agentUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val agentToken: StateFlow<String> = settings.agentToken
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val suggestions: StateFlow<List<LinkedInSuggestion>> = repository.pending
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Latest snackbar-style message (e.g. "Comment posted", "Session expired"). */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() { _toast.value = null }
    fun clearError() { _error.value = null }

    fun setAgentUrl(value: String) {
        viewModelScope.launch { settings.setAgentUrl(value) }
    }

    fun setAgentToken(value: String) {
        viewModelScope.launch { settings.setAgentToken(value) }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val added = repository.refresh(maxPosts = 10)
                _toast.value = if (added > 0) "Found $added new" else "No new posts"
            } catch (t: Throwable) {
                _error.value = t.message ?: "Refresh failed"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun approve(id: Long, editedComment: String? = null) {
        viewModelScope.launch {
            try {
                repository.approve(id, editedComment)
                _toast.value = "Sent"
            } catch (t: Throwable) {
                _error.value = t.message ?: "Action failed"
            }
        }
    }

    fun reject(id: Long) {
        viewModelScope.launch { repository.reject(id) }
    }
}
