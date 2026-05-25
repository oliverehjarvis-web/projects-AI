package com.oli.projectsai.features.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.db.entity.Transcription
import com.oli.projectsai.core.repository.TranscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class TranscriptionHistoryViewModel @Inject constructor(
    private val repository: TranscriptionRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) { _query.value = value }
    fun clearQuery() { _query.value = "" }

    /**
     * Debounced, reactive history. A blank query streams the whole list; otherwise it filters by
     * title and body via the DAO's `LIKE` search. Mirrors the home-screen search cadence.
     */
    val history: StateFlow<List<Transcription>> = _query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0 else 250 }
        .flatMapLatest { repository.history(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
