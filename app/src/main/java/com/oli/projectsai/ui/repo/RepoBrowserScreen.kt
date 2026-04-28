package com.oli.projectsai.ui.repo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoBrowserScreen(
    onNavigateBack: () -> Unit,
    onInjected: () -> Unit,
    viewModel: RepoBrowserViewModel = hiltViewModel()
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val repos by viewModel.repos.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val stats by viewModel.selectionStats.collectAsStateWithLifecycle()
    val injecting by viewModel.injecting.collectAsStateWithLifecycle()
    val contextLimit by viewModel.contextLimitFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (val s = phase) {
                        is RepoBrowserViewModel.Phase.Tree -> "${s.owner}/${s.name}"
                        else -> "Pick repository"
                    }
                    Text(title, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadRepoList() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        },
        bottomBar = {
            val tree = phase as? RepoBrowserViewModel.Phase.Tree
            if (tree != null) {
                SelectionFooter(
                    stats = stats,
                    contextLimit = contextLimit,
                    injecting = injecting,
                    onClear = { viewModel.clearSelection() },
                    onInject = { viewModel.injectIntoChat(onInjected) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            when (val s = phase) {
                is RepoBrowserViewModel.Phase.PickRepo -> RepoListContent(repos, viewModel::pickRepo)
                is RepoBrowserViewModel.Phase.Loading -> LoadingContent(s.label)
                is RepoBrowserViewModel.Phase.Error -> ErrorContent(s.message, viewModel::loadRepoList)
                is RepoBrowserViewModel.Phase.Tree -> TreeContent(
                    rootNode = s.rootNode,
                    truncated = s.truncated,
                    selected = selected,
                    expanded = expanded,
                    onToggleExpanded = viewModel::toggleExpanded,
                    onToggleSelected = viewModel::toggleSelected
                )
            }
        }
    }
}

@Composable
private fun RepoListContent(
    repos: List<com.oli.projectsai.data.github.GitHubClient.Repo>,
    onPick: (com.oli.projectsai.data.github.GitHubClient.Repo) -> Unit
) {
    if (repos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No repositories returned. Check your PAT scope in Settings → GitHub.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(repos, key = { "${it.owner}/${it.name}" }) { repo ->
            ListItem(
                headlineContent = { Text("${repo.owner}/${repo.name}") },
                supportingContent = { Text("${repo.sizeKb} KB · ${repo.defaultBranch}") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(repo) }
            )
        }
    }
}

@Composable
private fun LoadingContent(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun TreeContent(
    rootNode: RepoBrowserViewModel.TreeNode,
    truncated: Boolean,
    selected: Set<String>,
    expanded: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onToggleSelected: (String) -> Unit
) {
    val visible = remember(rootNode, expanded) { flatten(rootNode, expanded) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (truncated) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(
                        "GitHub returned a truncated tree (very large repo). Some files may not be listed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
        items(visible, key = { it.path.ifBlank { "<root>" } }) { node ->
            TreeRow(
                node = node,
                isExpanded = node.path in expanded,
                isSelected = node.path in selected,
                onToggleExpanded = onToggleExpanded,
                onToggleSelected = onToggleSelected
            )
        }
    }
}

@Composable
private fun TreeRow(
    node: RepoBrowserViewModel.TreeNode,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpanded: (String) -> Unit,
    onToggleSelected: (String) -> Unit
) {
    val indent = (node.path.count { it == '/' }) * 16
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (node.isDir) onToggleExpanded(node.path) else onToggleSelected(node.path)
            }
            .padding(start = (8 + indent).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDir) {
            Icon(
                if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(node.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        } else {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelected(node.path) }
            )
            Icon(
                Icons.AutoMirrored.Filled.Article,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(node.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                formatSize(node.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SelectionFooter(
    stats: RepoBrowserViewModel.SelectionStats,
    contextLimit: Int,
    injecting: Boolean,
    onClear: () -> Unit,
    onInject: () -> Unit
) {
    val pctOfWindow = if (contextLimit > 0) (stats.approxTokens.toFloat() / contextLimit).coerceIn(0f, 1f) else 0f
    val warning = pctOfWindow > 0.6f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${stats.files} files · ${formatSize(stats.totalBytes)} · ≈${stats.approxTokens / 1000}k tokens",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (stats.files > 0) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, "Clear selection")
                    }
                }
            }
            LinearProgressIndicator(
                progress = { pctOfWindow },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (contextLimit > 0)
                    "${(pctOfWindow * 100).toInt()}% of the active backend's ${contextLimit / 1024}k window"
                else
                    "Load a model to see the budget bar.",
                style = MaterialTheme.typography.labelSmall,
                color = if (warning) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onInject,
                    enabled = stats.files > 0 && !injecting
                ) {
                    if (injecting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (injecting) "Fetching files…" else "Inject into chat")
                }
            }
        }
    }
}

private fun flatten(
    node: RepoBrowserViewModel.TreeNode,
    expanded: Set<String>
): List<RepoBrowserViewModel.TreeNode> = buildList {
    node.children.forEach { child ->
        add(child)
        if (child.isDir && child.path in expanded) {
            addAll(flatten(child, expanded))
        }
    }
}

private fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1024f / 1024f)} MB"
}
