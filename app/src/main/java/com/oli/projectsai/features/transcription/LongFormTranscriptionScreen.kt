package com.oli.projectsai.features.transcription

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongFormTranscriptionScreen(
    onNavigateBack: () -> Unit,
    onNavigateToModelManagement: () -> Unit,
    onNavigateToChat: (chatId: Long) -> Unit,
    viewModel: LongFormTranscriptionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsState()
    val context = LocalContext.current
    var identifySpeakers by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf<String?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        // Persist read access so the decoder can stream from the URI later in the coroutine.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = queryDisplayName(context, uri) ?: "audio file"
        viewModel.start(uri, name, identifySpeakers)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Long-form transcribe") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val s = state) {
                is LongFormTranscriptionViewModel.State.Idle -> IdleSection(
                    identifySpeakers = identifySpeakers,
                    onToggleSpeakers = { identifySpeakers = it },
                    onPickFile = {
                        pickerLauncher.launch(
                            arrayOf(
                                "audio/mp4",
                                "audio/x-m4a",
                                "audio/mpeg",
                                "audio/mp3",
                                "audio/wav",
                                "audio/x-wav"
                            )
                        )
                    }
                )

                is LongFormTranscriptionViewModel.State.Decoding -> StatusSection(
                    title = "Decoding ${s.fileName}…",
                    subtitle = "Converting to 16 kHz mono PCM. Large files take a few seconds."
                )

                is LongFormTranscriptionViewModel.State.Transcribing -> TranscribingSection(
                    state = s,
                    onCancel = { viewModel.cancel() }
                )

                is LongFormTranscriptionViewModel.State.Reconciling -> {
                    StatusSection(
                        title = "Identifying speakers…",
                        subtitle = "Renumbering speaker labels across chunks for consistency."
                    )
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(s.partialTranscript, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is LongFormTranscriptionViewModel.State.Done -> DoneSection(
                    transcript = s.transcript,
                    cancelled = s.cancelled,
                    onCopy = { viewModel.copyToClipboard(s.transcript) },
                    onShare = { viewModel.shareText(s.transcript) },
                    onSendToChat = { showProjectPicker = s.transcript },
                    onStartOver = { viewModel.reset() }
                )

                is LongFormTranscriptionViewModel.State.Error -> {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (s.needsModel) {
                        Button(onClick = onNavigateToModelManagement) {
                            Text("Open model management")
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.reset() }) {
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }

    val pendingTranscript = showProjectPicker
    if (pendingTranscript != null) {
        AlertDialog(
            onDismissRequest = { showProjectPicker = null },
            title = { Text("Send transcript to project") },
            text = {
                if (projects.isEmpty()) {
                    Text("No projects yet — create one from the home screen first.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(projects, key = { it.id }) { project ->
                            ListItem(
                                headlineContent = { Text(project.name) },
                                supportingContent = if (project.description.isNotBlank())
                                    { { Text(project.description, maxLines = 1) } } else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            )
                            TextButton(
                                onClick = {
                                    showProjectPicker = null
                                    viewModel.sendToChat(project.id, pendingTranscript) { chatId ->
                                        onNavigateToChat(chatId)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Send to ${project.name}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProjectPicker = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun IdleSection(
    identifySpeakers: Boolean,
    onToggleSpeakers: (Boolean) -> Unit,
    onPickFile: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.UploadFile,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Pick an audio file (M4A, MP3, or WAV) and the on-device model will transcribe it 28 seconds at a time.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Identify speakers", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Best-effort speaker labels with a reconcile pass at the end. Slower.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = identifySpeakers, onCheckedChange = onToggleSpeakers)
                }
            }

            Button(onClick = onPickFile) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pick audio file")
            }

            Text(
                "Each 28 s chunk takes 30–60 s on this device, so a 30-minute recording is roughly 30–60 minutes of compute. The transcript appears live as chunks complete.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusSection(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TranscribingSection(
    state: LongFormTranscriptionViewModel.State.Transcribing,
    onCancel: () -> Unit
) {
    val progress = if (state.totalChunks > 0)
        state.completedChunks.toFloat() / state.totalChunks else 0f
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Chunk ${state.completedChunks} of ${state.totalChunks} • ${state.elapsedSec}s",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel")
                }
            }
        }
    }

    Text("Transcript so far", style = MaterialTheme.typography.titleSmall)
    SelectionContainer(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = if (state.partialTranscript.isBlank()) "(waiting for first chunk…)" else state.partialTranscript,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DoneSection(
    transcript: String,
    cancelled: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSendToChat: () -> Unit,
    onStartOver: () -> Unit
) {
    if (cancelled) {
        Text(
            "Cancelled — partial transcript shown below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
    SelectionContainer(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = if (transcript.isBlank()) "(no transcript)" else transcript,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopy, enabled = transcript.isNotBlank()) {
            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Copy")
        }
        OutlinedButton(onClick = onShare, enabled = transcript.isNotBlank()) {
            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Share")
        }
        OutlinedButton(onClick = onSendToChat, enabled = transcript.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Send to chat")
        }
    }
    TextButton(onClick = onStartOver) { Text("Start over") }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}
