package com.oli.projectsai.features.transcription

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oli.projectsai.core.inference.TRANSCRIPTION_MAX_SECONDS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionScreen(
    onNavigateBack: () -> Unit,
    onNavigateToModelManagement: () -> Unit,
    onNavigateToLongForm: () -> Unit,
    viewModel: TranscriptionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcribe") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    val canOpenLongForm = state is TranscriptionViewModel.RecordingState.Idle ||
                        state is TranscriptionViewModel.RecordingState.Done ||
                        state is TranscriptionViewModel.RecordingState.Error
                    if (canOpenLongForm) {
                        IconButton(onClick = onNavigateToLongForm) {
                            Icon(Icons.Default.UploadFile, "Long-form transcribe")
                        }
                    }
                    if (state is TranscriptionViewModel.RecordingState.Done ||
                        state is TranscriptionViewModel.RecordingState.Error
                    ) {
                        IconButton(onClick = { viewModel.reset() }) {
                            Icon(Icons.Default.Refresh, "Start over")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            val isRecording = state is TranscriptionViewModel.RecordingState.Recording
            val isBusy = state is TranscriptionViewModel.RecordingState.Transcribing ||
                state is TranscriptionViewModel.RecordingState.PreparingModel
            FloatingActionButton(
                onClick = {
                    if (isBusy) return@FloatingActionButton
                    when (state) {
                        is TranscriptionViewModel.RecordingState.Idle,
                        is TranscriptionViewModel.RecordingState.Error,
                        is TranscriptionViewModel.RecordingState.Done -> {
                            if (state is TranscriptionViewModel.RecordingState.Done ||
                                state is TranscriptionViewModel.RecordingState.Error
                            ) {
                                viewModel.reset()
                            }
                            if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.start()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        is TranscriptionViewModel.RecordingState.Recording -> viewModel.stop()
                        is TranscriptionViewModel.RecordingState.Transcribing,
                        is TranscriptionViewModel.RecordingState.PreparingModel -> Unit
                    }
                },
                containerColor = when {
                    isRecording -> MaterialTheme.colorScheme.error
                    isBusy -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
            ) {
                Icon(
                    if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Record"
                )
            }
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
                is TranscriptionViewModel.RecordingState.Idle -> IdleContent(onNavigateToLongForm)

                is TranscriptionViewModel.RecordingState.PreparingModel -> PreparingModelContent()

                is TranscriptionViewModel.RecordingState.Recording -> RecordingContent(s.elapsedMs)

                is TranscriptionViewModel.RecordingState.Transcribing -> TranscribingContent()

                is TranscriptionViewModel.RecordingState.Done -> {
                    SelectionContainer {
                        Text(s.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.copyToClipboard(s.text) }) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy")
                        }
                        OutlinedButton(onClick = { viewModel.shareText(s.text) }) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share")
                        }
                    }
                }

                is TranscriptionViewModel.RecordingState.Error -> {
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
}

@Composable
private fun IdleContent(onNavigateToLongForm: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Tap the mic to record. Audio is transcribed on-device by the voice model picked in Settings (up to ${TRANSCRIPTION_MAX_SECONDS}s).",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onNavigateToLongForm) {
                Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Have a longer recording? Upload a file")
            }
        }
    }
}

@Composable
private fun RecordingContent(elapsedMs: Long) {
    val totalMs = TRANSCRIPTION_MAX_SECONDS * 1000L
    val progress = (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    val seconds = (elapsedMs / 1000).coerceIn(0, TRANSCRIPTION_MAX_SECONDS.toLong())
    val warn = seconds >= TRANSCRIPTION_MAX_SECONDS - 5
    val accent = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
        color = accent
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Recording…",
            style = MaterialTheme.typography.bodyLarge,
            color = accent
        )
        Text(
            "${seconds}s / ${TRANSCRIPTION_MAX_SECONDS}s",
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontFamily = FontFamily.Monospace
        )
    }
    Text(
        "Tap stop when you're done. Recording auto-stops at ${TRANSCRIPTION_MAX_SECONDS}s.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PreparingModelContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Loading on-device voice model…",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "First mic press of the session — usually 5–15s on this device. Stays warm afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TranscribingContent() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(
            "Transcribing with the loaded model…",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

