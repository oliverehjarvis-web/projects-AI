package com.oli.cortex.ui.settings

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
import com.oli.cortex.inference.ModelPrecision
import com.oli.cortex.inference.ModelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: ModelManagementViewModel = hiltViewModel()
) {
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val modelFiles by viewModel.modelFiles.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()

    var selectedPrecision by remember { mutableStateOf(ModelPrecision.SFP8) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

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
                                "${state.modelInfo.precision.displayName} | ~${state.modelInfo.precision.estimatedRamGb}GB RAM",
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

            // Precision selector
            Text("Precision", style = MaterialTheme.typography.titleSmall)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModelPrecision.entries.forEach { precision ->
                    FilterChip(
                        selected = selectedPrecision == precision,
                        onClick = { selectedPrecision = precision },
                        label = { Text(precision.displayName) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                "SFP8 (~7.5GB) gives better quality. Q4 (~4.5GB) uses less RAM. " +
                    "Your OnePlus 13 has 16GB, so SFP8 is recommended.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                            "Import a Gemma 4 E4B .task file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                modelFiles.forEach { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = { Text(file.path) },
                        trailingContent = {
                            Button(
                                onClick = { viewModel.loadModel(file.path, file.name, selectedPrecision) },
                                enabled = modelState !is ModelState.Loading
                            ) { Text("Load") }
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
                Text("Import .task File")
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
}
