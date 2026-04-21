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
import com.oli.projectsai.BuildConfig
import com.oli.projectsai.data.preferences.SearchDepth
import com.oli.projectsai.inference.ModelState

private const val REVEAL_TAPS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onModelManagement: () -> Unit,
    onGlobalContext: () -> Unit,
    onPrivate: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var aboutTapCount by remember { mutableStateOf(0) }
    val privateRevealed = aboutTapCount >= REVEAL_TAPS
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val backends by viewModel.backends.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val searxngUrl by viewModel.searxngUrl.collectAsStateWithLifecycle()
    val searchDepth by viewModel.searchDepth.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val apiToken by viewModel.apiToken.collectAsStateWithLifecycle()
    val remoteModel by viewModel.remoteModel.collectAsStateWithLifecycle()
    val remoteModels by viewModel.remoteModels.collectAsStateWithLifecycle()
    val remoteError by viewModel.remoteError.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

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

            Text(
                "Assistant",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            ListItem(
                headlineContent = { Text("Global context") },
                supportingContent = { Text("Your name and rules the assistant follows in every project.") },
                leadingContent = { Icon(Icons.Default.Person, null) },
                modifier = Modifier.clickable(onClick = onGlobalContext)
            )

            HorizontalDivider()

            Text(
                "Web search",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SearxngUrlField(
                currentUrl = searxngUrl,
                onSave = { viewModel.setSearxngUrl(it) }
            )

            SearchDepthSelector(
                current = searchDepth,
                onSelect = { viewModel.setSearchDepth(it) }
            )

            HorizontalDivider()

            HorizontalDivider()

            // Remote server section
            Text(
                "Remote server",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            RemoteServerSection(
                serverUrl = serverUrl,
                apiToken = apiToken,
                remoteModel = remoteModel,
                remoteModels = remoteModels,
                remoteError = remoteError,
                syncState = syncState,
                onSave = { url, token, model -> viewModel.saveRemoteSettings(url, token, model) },
                onTest = { url, token, onResult -> viewModel.testConnection(url, token, onResult) },
                onFetchModels = { url, token -> viewModel.fetchRemoteModels(url, token) },
                onSync = { viewModel.syncNow() },
                onDismissSync = { viewModel.dismissSyncState() }
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
                supportingContent = { Text("v${BuildConfig.VERSION_NAME} - On-device AI with project context") },
                leadingContent = { Icon(Icons.Default.Info, null) },
                modifier = Modifier.clickable {
                    if (aboutTapCount < REVEAL_TAPS) aboutTapCount++
                }
            )

            if (privateRevealed) {
                ListItem(
                    headlineContent = { Text("Private") },
                    supportingContent = { Text("Hidden projects, PIN-gated") },
                    leadingContent = { Icon(Icons.Default.Lock, null) },
                    modifier = Modifier.clickable(onClick = onPrivate)
                )
            }

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

@Composable
private fun SearxngUrlField(
    currentUrl: String,
    onSave: (String) -> Unit
) {
    var draft by remember(currentUrl) { mutableStateOf(currentUrl) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Point at a SearXNG instance (e.g. running on your TrueNAS over Tailscale) to enable " +
                "per-chat web search. Unlimited and no API key — include scheme and port, e.g. " +
                "http://100.x.x.x:8888.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("http://100.x.x.x:8888") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(draft) },
                enabled = draft != currentUrl
            ) { Text("Save") }
            if (currentUrl.isNotEmpty()) {
                OutlinedButton(onClick = {
                    draft = ""
                    onSave("")
                }) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun SearchDepthSelector(
    current: SearchDepth,
    onSelect: (SearchDepth) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Search depth",
            style = MaterialTheme.typography.titleSmall
        )
        DepthOption(
            selected = current == SearchDepth.AUTO_FETCH,
            title = "Auto-fetch top results",
            subtitle = "Runs search, then auto-downloads the top 2 pages. One extra round-trip; consistent.",
            onClick = { onSelect(SearchDepth.AUTO_FETCH) }
        )
        DepthOption(
            selected = current == SearchDepth.TOOL_LOOP,
            title = "Model decides (fetch tool)",
            subtitle = "Model can search, then pick specific URLs to read in full. Slower and can loop; more flexible.",
            onClick = { onSelect(SearchDepth.TOOL_LOOP) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteServerSection(
    serverUrl: String,
    apiToken: String,
    remoteModel: String,
    remoteModels: List<SettingsViewModel.RemoteModel>,
    remoteError: String?,
    syncState: SyncState,
    onSave: (String, String, String) -> Unit,
    onTest: (String, String, (Boolean, String) -> Unit) -> Unit,
    onFetchModels: (String, String) -> Unit,
    onSync: () -> Unit,
    onDismissSync: () -> Unit
) {
    var draftUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var draftToken by remember(apiToken) { mutableStateOf(apiToken) }
    var draftModel by remember(remoteModel) { mutableStateOf(remoteModel) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val installedModels = remoteModels.filter { it.installed }

    LaunchedEffect(installedModels) {
        if (draftModel.isBlank() && installedModels.isNotEmpty()) {
            draftModel = installedModels.first().id
            onSave(draftUrl, draftToken, installedModels.first().id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Point at your Projects AI Docker server to enable remote AI inference and data sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = draftUrl,
            onValueChange = { draftUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server URL") },
            placeholder = { Text("http://100.x.x.x:8765") },
            singleLine = true
        )
        OutlinedTextField(
            value = draftToken,
            onValueChange = { draftToken = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API token") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )

        when {
            remoteModels.isEmpty() -> {
                val hint = when {
                    remoteError != null -> "Couldn't fetch models: $remoteError"
                    draftUrl.isBlank() || draftToken.isBlank() ->
                        "Enter your server URL and token, then Save & Test to pick a model."
                    else -> "Save & Test your connection to pick a model."
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remoteError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            installedModels.isEmpty() -> {
                Text(
                    "No models installed on the server yet. Use the web UI to pull one — " +
                        "${remoteModels.size} are available in the catalogue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it }
                ) {
                    val selectedLabel = remoteModels.find { it.id == draftModel }?.let {
                        val suffix = if (!it.installed) " (not installed)" else ""
                        "${it.label} (${it.sizeGb} GB)$suffix"
                    } ?: draftModel.ifBlank { "Select a model" }
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        isError = draftModel.isNotBlank() &&
                            remoteModels.none { it.id == draftModel && it.installed },
                        label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        remoteModels.forEach { model ->
                            DropdownMenuItem(
                                enabled = model.installed,
                                text = {
                                    Column {
                                        Text(model.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${model.sizeGb} GB" +
                                                if (!model.installed) " · not installed" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    draftModel = model.id
                                    modelDropdownExpanded = false
                                    onSave(draftUrl, draftToken, model.id)
                                }
                            )
                        }
                    }
                }
                if (draftModel.isNotBlank() &&
                    remoteModels.none { it.id == draftModel && it.installed }) {
                    Text(
                        "The saved model isn't installed on the server anymore — pick another.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onSave(draftUrl, draftToken, draftModel)
                testResult = null
                onTest(draftUrl, draftToken) { ok, msg ->
                    testResult = ok to msg
                    if (ok) onFetchModels(draftUrl, draftToken)
                }
            }) { Text("Save & Test") }
            OutlinedButton(
                onClick = onSync,
                enabled = syncState !is SyncState.Syncing
            ) {
                if (syncState is SyncState.Syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Sync now")
                }
            }
        }
        testResult?.let { (ok, msg) ->
            Text(
                msg,
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        when (syncState) {
            is SyncState.Success -> Text(
                "Sync complete",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            is SyncState.Error -> Text(
                "Sync failed: ${syncState.message}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            else -> Unit
        }
    }
}

@Composable
private fun DepthOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
