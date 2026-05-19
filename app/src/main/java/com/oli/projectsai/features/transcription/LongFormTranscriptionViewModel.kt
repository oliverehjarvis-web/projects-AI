package com.oli.projectsai.features.transcription

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.core.db.entity.Chat
import com.oli.projectsai.core.db.entity.Message
import com.oli.projectsai.core.db.entity.MessageRole
import com.oli.projectsai.core.db.entity.Project
import com.oli.projectsai.core.inference.InferenceManager
import com.oli.projectsai.core.inference.LongTranscriptionState
import com.oli.projectsai.core.inference.TranscriptionController
import com.oli.projectsai.core.inference.TranscriptionForegroundService
import com.oli.projectsai.core.repository.ChatRepository
import com.oli.projectsai.core.repository.ProjectRepository
import com.oli.projectsai.core.ui.common.copyToClipboard
import com.oli.projectsai.core.ui.common.shareText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LongFormTranscriptionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: TranscriptionController,
    private val inferenceManager: InferenceManager,
    private val chatRepository: ChatRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    val state: StateFlow<LongTranscriptionState> = controller.state

    val projects: StateFlow<List<Project>> = projectRepository.getAllProjects()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun start(uri: Uri, fileName: String, identifySpeakers: Boolean) {
        val started = controller.start(uri, fileName, identifySpeakers)
        if (started) {
            val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
                action = TranscriptionForegroundService.ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }

    fun cancel() = controller.cancel()

    fun reset() = controller.reset()

    fun sendToChat(projectId: Long, transcript: String, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val title = "Transcript: ${java.time.LocalDate.now()}"
            val chatId = chatRepository.createChat(Chat(projectId = projectId, title = title))
            chatRepository.addMessage(
                Message(
                    chatId = chatId,
                    role = MessageRole.USER,
                    content = transcript,
                    tokenCount = inferenceManager.countTokens(transcript)
                )
            )
            onReady(chatId)
        }
    }

    fun copyToClipboard(text: String) = context.copyToClipboard(text, label = "Transcript")
    fun shareText(text: String) = context.shareText(text, chooserTitle = "Share transcript")
}
