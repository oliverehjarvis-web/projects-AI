package com.oli.projectsai.features.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import com.oli.projectsai.core.inference.ModelPrecision
import com.oli.projectsai.core.inference.ModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: ModelManagementViewModel = hiltViewModel()
) {
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val modelFiles by viewModel.modelFiles.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    var customUrl by remember { mutableStateOf("") }
    var customUrlError by remember { mutableStateOf<String?>(null) }
    // Per-row state for the local-model overflow menu + delete-confirmation dialog. One row
    // can have its menu open at a time; modelToDelete is set when the user picks Delete.
    var openMenuFile by remember { mutableStateOf<ModelFile?>(null) }
    var modelToDelete by remember { mutableStateOf<ModelFile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Management") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Current status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (modelState) {
                        is ModelState.Loaded -> MaterialTheme.colorScheme.primaryContainer
                        is ModelState.Error -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    when (val state = modelState) {
                        is ModelState.Unloaded -> Text("No model loaded")
                        is ModelState.Loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Loading ${state.modelInfo.name}...")
                            }
                        }
                        is ModelState.Loaded -> {
                            Text("${state.modelInfo.name}")
                            Text(
                                "${stringResource(state.modelInfo.precision.displayNameRes)} | ~${state.modelInfo.precision.estimatedRamGb}GB RAM",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        is ModelState.Error -> {
                            Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Unload button
            if (modelState is ModelState.Loaded) {
                OutlinedButton(
                    onClick = { viewModel.unloadModel() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PowerSettingsNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Unload Model")
                }
            }

            HorizontalDivider()

            // Recommended models
            Text("Recommended Models", style = MaterialTheme.typography.titleSmall)

            viewModel.recommendedModels.forEach { model ->
                val state = downloadStates[model.filename] ?: DownloadState.Idle
                RecommendedModelCard(
                    model = model,
                    state = state,
                    onDownload = { viewModel.downloadRecommended(model) },
                    onCancel = { viewModel.cancelActiveDownload() }
                )
            }

            HorizontalDivider()

            // Custom URL download
            Text("Download from URL", style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = customUrl,
                onValueChange = {
                    customUrl = it
                    customUrlError = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://…/model.litertlm") },
                singleLine = true,
                isError = customUrlError != null,
                supportingText = customUrlError?.let { { Text(it) } }
            )
            Button(
                onClick = {
                    val err = viewModel.downloadCustomUrl(customUrl)
                    if (err == null) customUrl = ""
                    customUrlError = err
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Download")
            }

            HorizontalDivider()

            // Model files
            Text("Available Models", style = MaterialTheme.typography.titleSmall)

            if (modelFiles.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FileDownload,
                            null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No model files found")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Download a model above, or import a model file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                modelFiles.forEach { file ->
                    val isLoadedFile = (modelState as? ModelState.Loaded)?.modelInfo?.filePath == file.path
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = { Text(file.path) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { viewModel.loadModel(file.path, file.name, ModelPrecision.Q4) },
                                    enabled = modelState !is ModelState.Loading
                                ) { Text("Load") }
                                Box {
                                    IconButton(onClick = { openMenuFile = file }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                                    }
                                    DropdownMenu(
                                        expanded = openMenuFile == file,
                                        onDismissRequest = { openMenuFile = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(if (isLoadedFile) "Delete (unload first)" else "Delete")
                                            },
                                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                                            enabled = !isLoadedFile,
                                            onClick = {
                                                openMenuFile = null
                                                modelToDelete = file
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Import button
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Import Model File")
            }

            loadError?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    modelToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("Delete model?") },
            text = { Text("\"${file.name}\" will be permanently removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteLocalModel(file.path)
                    modelToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RecommendedModelCard(
    model: RecommendedModel,
    state: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${model.sizeLabel} · ${model.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                when (state) {
                    is DownloadState.Idle -> {
                        Button(onClick = onDownload) { Text("Download") }
                    }
                    is DownloadState.Downloading -> {
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    is DownloadState.Completed -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    is DownloadState.Failed -> {
                        Button(onClick = onDownload) { Text("Retry") }
                    }
                }
            }
            when (state) {
                is DownloadState.Downloading -> {
                    if (state.progress != null) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is DownloadState.Failed -> {
                    Text(
                        "Error: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> {}
            }
        }
    }
}
