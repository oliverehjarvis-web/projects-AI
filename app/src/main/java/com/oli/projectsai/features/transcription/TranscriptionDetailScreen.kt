package com.oli.projectsai.features.transcription

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: TranscriptionDetailViewModel = hiltViewModel()
) {
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val summaryState by viewModel.summaryState.collectAsStateWithLifecycle()
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val item = transcription

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcript", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (item != null) {
                        IconButton(onClick = { showRename = true }) {
                            Icon(Icons.Default.Edit, "Rename")
                        }
                        IconButton(onClick = { viewModel.copyToClipboard(item.text) }) {
                            Icon(Icons.Default.ContentCopy, "Copy")
                        }
                        IconButton(onClick = { viewModel.shareText(item.text) }) {
                            Icon(Icons.Default.Share, "Share")
                        }
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (item == null) {
            // Either still loading or the row was deleted out from under us.
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                item.title.ifBlank { "Untitled transcript" },
                style = MaterialTheme.typography.headlineSmall
            )

            SummaryCard(
                summary = item.summary,
                state = summaryState,
                onSummarise = viewModel::summarise
            )

            HorizontalDivider()

            SelectionContainer {
                Text(
                    item.text.ifBlank { "(no transcript)" },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    if (showRename && item != null) {
        var draft by remember(item.id) { mutableStateOf(item.title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename transcript") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(draft)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            }
        )
    }

    if (showDelete && item != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete transcript?") },
            text = { Text("This removes it from your history.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete(onNavigateBack)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SummaryCard(
    summary: String?,
    state: TranscriptionDetailViewModel.SummaryState,
    onSummarise: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Summary", style = MaterialTheme.typography.titleSmall)
            }

            when (state) {
                is TranscriptionDetailViewModel.SummaryState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text("Summarising on-device…", style = MaterialTheme.typography.bodyMedium)
                }

                is TranscriptionDetailViewModel.SummaryState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )

                is TranscriptionDetailViewModel.SummaryState.Idle -> Unit
            }

            if (!summary.isNullOrBlank()) {
                SelectionContainer {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state !is TranscriptionDetailViewModel.SummaryState.Loading) {
                Text(
                    "No summary yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state !is TranscriptionDetailViewModel.SummaryState.Loading) {
                OutlinedButton(onClick = onSummarise) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (summary.isNullOrBlank()) "Summarise" else "Regenerate summary")
                }
            }
        }
    }
}
