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
import androidx.compose.ui.Alignment
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
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    // Auto-launch installer when APK is ready
    val currentUpdateState = updateState
    LaunchedEffect(currentUpdateState) {
        if (currentUpdateState is UpdateState.ReadyToInstall) {
            viewModel.launchInstaller(currentUpdateState.apkFile)
        }
    }

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

            HorizontalDivider()

            // About section
            Text(
                "About",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            ListItem(
                headlineContent = { Text("Projects AI") },
                supportingContent = { Text("v1.1.2 - On-device AI with project context") },
                leadingContent = { Icon(Icons.Default.Info, null) }
            )

            // Update UI
            UpdateSection(
                updateState = updateState,
                onCheckForUpdate = { viewModel.checkForUpdate() },
                onDownloadAndInstall = { info -> viewModel.downloadAndInstall(info) },
                onCancel = { viewModel.cancelDownload() },
                onDismiss = { viewModel.dismissUpdateState() }
            )
        }
    }
}

@Composable
private fun UpdateSection(
    updateState: UpdateState,
    onCheckForUpdate: () -> Unit,
    onDownloadAndInstall: (com.oli.projectsai.data.update.UpdateInfo) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    when (updateState) {
        is UpdateState.Idle, is UpdateState.UpToDate -> {
            ListItem(
                headlineContent = {
                    val label = if (updateState is UpdateState.UpToDate) "Up to date" else "Check for updates"
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                },
                leadingContent = { Icon(Icons.Default.Refresh, null) },
                modifier = Modifier.clickable(onClick = onCheckForUpdate)
            )
        }

        is UpdateState.Checking -> {
            ListItem(
                headlineContent = { Text("Checking for updates…") },
                leadingContent = {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            )
        }

        is UpdateState.Available -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Update available: v${updateState.info.version}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        "Your data is preserved during an update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownloadAndInstall(updateState.info) }) {
                            Text("Download & Install")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Later")
                        }
                    }
                }
            }
        }

        is UpdateState.Downloading -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Downloading update…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { updateState.progress?.coerceIn(0f, 1f) ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (updateState.progress != null) {
                        Text(
                            "${(updateState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }

        is UpdateState.ReadyToInstall -> {
            ListItem(
                headlineContent = { Text("Opening installer…") },
                leadingContent = {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            )
        }

        is UpdateState.Error -> {
            ListItem(
                headlineContent = { Text("Update check failed", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text(updateState.message, style = MaterialTheme.typography.bodySmall) },
                leadingContent = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                trailingContent = {
                    TextButton(onClick = onCheckForUpdate) { Text("Retry") }
                }
            )
        }
    }
}
