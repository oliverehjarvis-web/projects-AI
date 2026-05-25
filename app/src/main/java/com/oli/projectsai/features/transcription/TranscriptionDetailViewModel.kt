package com.oli.projectsai.features.transcription

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.db.entity.Transcription
import com.oli.projectsai.core.repository.TranscriptionRepository
import com.oli.projectsai.core.ui.common.copyToClipboard
import com.oli.projectsai.core.ui.common.shareText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranscriptionDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TranscriptionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    sealed class SummaryState {
        data object Idle : SummaryState()
        data object Loading : SummaryState()
        data class Error(val message: String) : SummaryState()
    }

    private val id: Long = savedStateHandle.get<Long>("transcriptionId") ?: -1L

    val transcription: StateFlow<Transcription?> = repository.transcription(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _summaryState = MutableStateFlow<SummaryState>(SummaryState.Idle)
    val summaryState: StateFlow<SummaryState> = _summaryState.asStateFlow()

    /** Generates and persists a summary. The new text arrives via [transcription]'s flow. */
    fun summarise() {
        if (_summaryState.value == SummaryState.Loading) return
        _summaryState.value = SummaryState.Loading
        viewModelScope.launch {
            val result = runCatching { repository.summarise(id) }
            _summaryState.value = result.fold(
                onSuccess = { SummaryState.Idle },
                onFailure = { SummaryState.Error(it.message ?: "Summary failed. Load a model and try again.") }
            )
        }
    }

    fun rename(title: String) {
        viewModelScope.launch { repository.rename(id, title) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    fun copyToClipboard(text: String) = context.copyToClipboard(text, label = "Transcript")
    fun shareText(text: String) = context.shareText(text, chooserTitle = "Share transcript")
}
