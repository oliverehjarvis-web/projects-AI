package com.oli.projectsai.features.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val isSecret by viewModel.isSecret.collectAsStateWithLifecycle()
    val canTogglePrivate by viewModel.canTogglePrivate.collectAsStateWithLifecycle()
    val useRemoteBackend by viewModel.useRemoteBackend.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefining.collectAsStateWithLifecycle()
    val refineError by viewModel.refineError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(refineError) {
        if (refineError != null) {
            snackbarHostState.showSnackbar(refineError!!)
            viewModel.clearRefineError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            OutlinedButton(
                onClick = { viewModel.refineContext() },
                enabled = manualContext.isNotBlank() && !isRefining,
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
                    "The model is rewriting your context to avoid looping patterns. This may take a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            val autoContextHint by viewModel.autoContextHint.collectAsStateWithLifecycle()
            val isComputingAutoContext by viewModel.isComputingAutoContext.collectAsStateWithLifecycle()

            ContextBudgetSection(
                contextLength = contextLength,
                contextOptions = contextOptions,
                memoryTokenLimit = memoryTokenLimit,
                manualContextTokens = contextTokenCount,
                autoContextHint = autoContextHint,
                isComputingAutoContext = isComputingAutoContext,
                onContextLengthChange = { viewModel.updateContextLength(it) },
                onMemoryTokenLimitChange = { viewModel.updateMemoryTokenLimit(it) },
                onAutoFill = { viewModel.autoFillContextLength() }
            )

            HorizontalDivider()
            val isRemoteConfigured by viewModel.isRemoteConfigured.collectAsStateWithLifecycle()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use remote backend", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Run AI inference on your Docker server instead of on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useRemoteBackend,
                    onCheckedChange = { viewModel.updateUseRemoteBackend(it) }
                )
            }
            if (useRemoteBackend && !isRemoteConfigured) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Remote server isn't configured yet. Add your server URL and API token " +
                                "in Settings → Remote server, otherwise this project will fall back " +
                                "to the on-device model.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (canTogglePrivate) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Private", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Hide this project from the main list. Only visible after PIN unlock.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isSecret,
                        onCheckedChange = { viewModel.updateIsSecret(it) }
                    )
                }
            }
        }
    }
}

/**
 * "Context budget" section: one slider for the model's context window, a stacked bar that shows
 * how memory + manual context eat into it, and a memory-cap slider clamped to leave 2k for
 * the live conversation. Replaces the old "Memory token limit" text field that lived in
 * isolation from the context picker.
 */
@Composable
private fun ContextBudgetSection(
    contextLength: Int,
    contextOptions: List<Int>,
    memoryTokenLimit: Int,
    manualContextTokens: Int,
    autoContextHint: String?,
    isComputingAutoContext: Boolean,
    onContextLengthChange: (Int) -> Unit,
    onMemoryTokenLimitChange: (Int) -> Unit,
    onAutoFill: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            "Context budget",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAutoFill, enabled = !isComputingAutoContext) {
            if (isComputingAutoContext) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text("Auto")
        }
    }
    Text(
        "Memory and manual context share this window with the live conversation. Larger values " +
            "remember more but slow generation, and reload the model on next chat open.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Stacked allocation bar: memory + manual context filling the chosen window. The live
    // conversation gets whatever's left.
    val total = contextLength.coerceAtLeast(1)
    val memShare = (memoryTokenLimit.toFloat() / total).coerceIn(0f, 1f)
    val manualShare = (manualContextTokens.toFloat() / total).coerceIn(0f, 1f - memShare)
    val freeShare = (1f - memShare - manualShare).coerceAtLeast(0f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (memShare > 0f) {
            Box(
                modifier = Modifier
                    .weight(memShare)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.tertiary)
            )
        }
        if (manualShare > 0f) {
            Box(
                modifier = Modifier
                    .weight(manualShare)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary)
            )
        }
        if (freeShare > 0f) {
            Box(
                modifier = Modifier
                    .weight(freeShare)
                    .fillMaxHeight()
                    .background(Color.Transparent)
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BudgetLegend("Memory ${memoryTokenLimit / 1000}k", MaterialTheme.colorScheme.tertiary)
        BudgetLegend("Context ${manualContextTokens / 1000}k", MaterialTheme.colorScheme.secondary)
        Text(
            "Free ≈${((freeShare * total) / 1000).toInt()}k",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(4.dp))
    Text(
        "Window: ${contextLength / 1024}k tokens",
        style = MaterialTheme.typography.labelMedium
    )
    val selectedIdx = contextOptions.indexOf(contextLength).let { if (it < 0) 2 else it }
    Slider(
        value = selectedIdx.toFloat(),
        onValueChange = { v ->
            onContextLengthChange(contextOptions[v.toInt().coerceIn(0, contextOptions.lastIndex)])
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
                color = if (v == contextLength) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    autoContextHint?.let { hint ->
        Text(hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Memory cap: ${memoryTokenLimit / 1000}k tokens",
        style = MaterialTheme.typography.labelMedium
    )
    val memoryCeiling = (contextLength - 2000).coerceAtLeast(1000)
    Slider(
        value = memoryTokenLimit.toFloat(),
        onValueChange = { onMemoryTokenLimitChange(it.toInt()) },
        valueRange = 1000f..memoryCeiling.toFloat(),
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Memory is capped at the window minus 2k so replies always have room. Increase the " +
            "window above first if you want a bigger memory.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun BudgetLegend(label: String, dotColor: Color) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(dotColor)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
