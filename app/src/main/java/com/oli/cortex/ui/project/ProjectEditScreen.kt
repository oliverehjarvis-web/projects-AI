package com.oli.cortex.ui.project

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
    val contextTokenCount by viewModel.contextTokenCount.collectAsStateWithLifecycle()

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
        }
    }
}
