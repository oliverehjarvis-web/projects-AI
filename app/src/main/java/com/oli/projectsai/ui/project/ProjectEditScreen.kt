package com.oli.projectsai.ui.project

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectEditScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProjectEditViewModel = hiltViewModel()
) {
    val isNew by viewModel.isNew.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val description by viewModel.description.collectAsStateWithLifecycle()
    val manualContext by viewModel.manualContext.collectAsStateWithLifecycle()
    val memoryTokenLimit by viewModel.memoryTokenLimit.collectAsStateWithLifecycle()
    val contextLength by viewModel.contextLength.collectAsStateWithLifecycle()
    val contextTokenCount by viewModel.contextTokenCount.collectAsStateWithLifecycle()
    val contextOptions = viewModel.contextLengthOptions

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New Project" else "Edit Project") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.save()
                            onNavigateBack()
                        },
                        enabled = name.isNotBlank()
                    ) {
                        Icon(Icons.Default.Check, "Save")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = description,
                onValueChange = { viewModel.updateDescription(it) },
                label = { Text("Description") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Text("Manual Context", style = MaterialTheme.typography.titleSmall)
            Text(
                "Permanent system-prompt-level information the model should always know about this project.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = manualContext,
                onValueChange = { viewModel.updateManualContext(it) },
                label = { Text("Context") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "$contextTokenCount tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            OutlinedTextField(
                value = memoryTokenLimit.toString(),
                onValueChange = { it.toIntOrNull()?.let { v -> viewModel.updateMemoryTokenLimit(v) } },
                label = { Text("Memory token limit") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Text("Context length", style = MaterialTheme.typography.titleSmall)
            Text(
                "Larger context lets the model remember more of a conversation but slows generation. " +
                    "Changing this reloads the model when you next open a chat in this project.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val selectedIdx = contextOptions.indexOf(contextLength).let { if (it < 0) 2 else it }
            Slider(
                value = selectedIdx.toFloat(),
                onValueChange = { v ->
                    viewModel.updateContextLength(contextOptions[v.toInt().coerceIn(0, contextOptions.lastIndex)])
                },
                valueRange = 0f..(contextOptions.lastIndex).toFloat(),
                steps = contextOptions.size - 2,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                contextOptions.forEach { v ->
                    val label = if (v >= 1024) "${v / 1024}K" else v.toString()
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (v == contextLength)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
