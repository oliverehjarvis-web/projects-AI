package com.oli.cortex.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oli.cortex.ui.theme.PinnedHighlight
import com.oli.cortex.ui.theme.TokenMemory
import com.oli.cortex.ui.theme.TokenWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val memoryText by viewModel.memoryText.collectAsStateWithLifecycle()
    val memoryTokens by viewModel.memoryTokenCount.collectAsStateWithLifecycle()
    val pinnedMemories by viewModel.pinnedMemories.collectAsStateWithLifecycle()
    val isEditing by viewModel.isEditing.collectAsStateWithLifecycle()

    val p = project
    val tokenLimit = p?.memoryTokenLimit ?: 8000
    val isOverLimit = memoryTokens > tokenLimit
    val usagePercent = if (tokenLimit > 0) memoryTokens.toFloat() / tokenLimit else 0f

    var showCompressDialog by remember { mutableStateOf(false) }
    var lineToPromote by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { viewModel.cancelEditing() }) {
                            Icon(Icons.Default.Close, "Cancel")
                        }
                        IconButton(onClick = { viewModel.saveMemory() }) {
                            Icon(Icons.Default.Check, "Save")
                        }
                    } else {
                        IconButton(onClick = { viewModel.startEditing() }) {
                            Icon(Icons.Default.Edit, "Edit")
                        }
                        IconButton(onClick = { showCompressDialog = true }) {
                            Icon(Icons.Default.Compress, "Compress")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Token usage bar
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isOverLimit)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Token usage", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "$memoryTokens / $tokenLimit",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOverLimit) TokenWarning else TokenMemory
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { usagePercent.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = when {
                            isOverLimit -> TokenWarning
                            usagePercent > 0.75f -> TokenWarning
                            else -> TokenMemory
                        }
                    )
                    if (isOverLimit) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Memory exceeds token limit. Compress or trim before adding more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Pinned memories section
            if (pinnedMemories.isNotEmpty()) {
                Text("Pinned", style = MaterialTheme.typography.titleSmall, color = PinnedHighlight)
                pinnedMemories.forEach { pinned ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = PinnedHighlight.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.PushPin,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = PinnedHighlight
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                pinned,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.unpinLine(pinned) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, "Unpin", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            // Memory content
            Text("Accumulated Memory", style = MaterialTheme.typography.titleSmall)

            if (isEditing) {
                OutlinedTextField(
                    value = memoryText,
                    onValueChange = { viewModel.updateMemoryText(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            } else {
                if (memoryText.isBlank()) {
                    Text(
                        "No accumulated memory yet. Use 'Add to Memory' during a chat to save notes here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Render memory as selectable blocks
                    val blocks = memoryText.split("\n\n---\n\n")
                    blocks.forEachIndexed { index, block ->
                        if (block.isNotBlank()) {
                            MemoryBlock(
                                text = block,
                                isPinned = block.trim() in pinnedMemories,
                                onPin = { viewModel.pinLine(block.trim()) },
                                onPromote = { lineToPromote = block.trim() }
                            )
                            if (index < blocks.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCompressDialog) {
        AlertDialog(
            onDismissRequest = { showCompressDialog = false },
            title = { Text("Compress Memory?") },
            text = {
                Text("This will send the accumulated memory to the model to consolidate and compress it. Pinned items will be preserved.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.compressMemory()
                    showCompressDialog = false
                }) { Text("Compress") }
            },
            dismissButton = {
                TextButton(onClick = { showCompressDialog = false }) { Text("Cancel") }
            }
        )
    }

    lineToPromote?.let { text ->
        AlertDialog(
            onDismissRequest = { lineToPromote = null },
            title = { Text("Promote to Manual Context?") },
            text = {
                Text("This will copy the selected memory block into the project's permanent manual context.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.promoteToManualContext(text)
                    lineToPromote = null
                }) { Text("Promote") }
            },
            dismissButton = {
                TextButton(onClick = { lineToPromote = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MemoryBlock(
    text: String,
    isPinned: Boolean,
    onPin: () -> Unit,
    onPromote: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }

    Card(
        onClick = { showActions = !showActions },
        colors = CardDefaults.cardColors(
            containerColor = if (isPinned)
                PinnedHighlight.copy(alpha = 0.05f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)

            if (showActions) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isPinned) {
                        AssistChip(
                            onClick = onPin,
                            label = { Text("Pin") },
                            leadingIcon = {
                                Icon(Icons.Default.PushPin, null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                    AssistChip(
                        onClick = onPromote,
                        label = { Text("Promote to context") },
                        leadingIcon = {
                            Icon(Icons.Default.ArrowUpward, null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }
    }
}
