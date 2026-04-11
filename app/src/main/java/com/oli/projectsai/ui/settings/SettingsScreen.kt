package com.oli.projectsai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oli.projectsai.inference.ModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onModelManagement: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val backends by viewModel.backends.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
                .verticalScroll(rememberScrollState())
        ) {
            // Model section
            Text(
                "Model",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            ListItem(
                headlineContent = { Text("Model Management") },
                supportingContent = {
                    Text(
                        when (modelState) {
                            is ModelState.Loaded -> "Loaded: ${(modelState as ModelState.Loaded).modelInfo.name}"
                            is ModelState.Loading -> "Loading..."
                            is ModelState.Error -> "Error: ${(modelState as ModelState.Error).message}"
                            ModelState.Unloaded -> "No model loaded"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Memory, null) },
                modifier = Modifier.clickable(onClick = onModelManagement)
            )

            HorizontalDivider()

            // Backends section
            Text(
                "Backends",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            backends.forEach { backend ->
                ListItem(
                    headlineContent = { Text(backend.displayName) },
                    supportingContent = {
                        Text(
                            when {
                                backend.isLoaded -> "Active"
                                backend.isAvailable -> "Available"
                                else -> "Unavailable"
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (backend.id.contains("local")) Icons.Default.PhoneAndroid
                            else Icons.Default.Cloud,
                            null
                        )
                    },
                    trailingContent = {
                        if (backend.isLoaded) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }

            // Remote backend placeholder
            ListItem(
                headlineContent = { Text("Remote Backend") },
                supportingContent = { Text("Coming soon - connect to a home NAS running a larger model") },
                leadingContent = { Icon(Icons.Default.Cloud, null) },
                trailingContent = {
                    Text("TODO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            )

            HorizontalDivider()

            // About section
            Text(
                "About",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            ListItem(
                headlineContent = { Text("Projects AI") },
                supportingContent = { Text("v1.0.0 - On-device AI with project context") },
                leadingContent = { Icon(Icons.Default.Info, null) }
            )
        }
    }
}
