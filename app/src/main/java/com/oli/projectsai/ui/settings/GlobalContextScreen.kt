package com.oli.projectsai.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalContextScreen(
    onNavigateBack: () -> Unit,
    viewModel: GlobalContextViewModel = hiltViewModel()
) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefining.collectAsStateWithLifecycle()
    val refineError by viewModel.refineError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(saved) {
        if (saved) {
            snackbarHostState.showSnackbar("Saved")
            delay(100)
        }
    }

    LaunchedEffect(refineError) {
        if (refineError != null) {
            snackbarHostState.showSnackbar(refineError!!)
            viewModel.clearRefineError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Global context") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "These apply to every project. They're prepended to the system prompt ahead of project-specific context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = name,
                onValueChange = viewModel::updateName,
                label = { Text("Your name") },
                supportingText = { Text("How the assistant should address you.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = rules,
                onValueChange = viewModel::updateRules,
                label = { Text("Rules for the assistant") },
                placeholder = {
                    Text(
                        "e.g. Use British English. No em-dashes. Keep answers under 200 words.",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                supportingText = { Text("One rule per line works well. Applies to every chat.") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = { viewModel.refineRules() },
                enabled = rules.isNotBlank() && !isRefining,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRefining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Refining...")
                } else {
                    Text("Refine with AI")
                }
            }

            if (isRefining) {
                Text(
                    "The model is rewriting your rules to avoid looping patterns. This may take a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
