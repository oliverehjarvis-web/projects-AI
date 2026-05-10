package com.oli.projectsai.features.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oli.projectsai.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oli.projectsai.core.db.entity.MessageRole
import com.oli.projectsai.core.db.entity.Project
import com.oli.projectsai.core.db.relation.MessageSearchHit
import com.oli.projectsai.core.inference.ModelState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onProjectClick: (Long) -> Unit,
    onNewProject: () -> Unit,
    onNewChat: (Long) -> Unit,
    onChatClick: (Long, Long) -> Unit,
    onSettingsClick: () -> Unit,
    onTranscribeClick: () -> Unit,
    onLinkedInClick: () -> Unit,
    onModelStatusClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val needsModel = modelState is ModelState.Unloaded || modelState is ModelState.Error
    var projectToDelete by remember { mutableStateOf<Project?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) searchFocus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = viewModel::setSearchQuery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus),
                            placeholder = { Text("Search messages…") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            )
                        )
                    } else {
                        // The wordmark "Projects AI" was getting ellipsised on phone widths once
                        // four actions sat on the right. The logomark takes ~32 dp instead of
                        // ~110 dp and tints with the surface colour for free.
                        Icon(
                            painter = painterResource(R.drawable.ic_app_logo),
                            contentDescription = "Projects AI",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                },
                actions = {
                    if (searchActive) {
                        IconButton(onClick = {
                            searchActive = false
                            viewModel.clearSearch()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search messages")
                        }
                        ModelStatusChip(modelState, onClick = onModelStatusClick)
                        IconButton(onClick = onLinkedInClick) {
                            Icon(Icons.Default.Group, contentDescription = "LinkedIn")
                        }
                        IconButton(onClick = onTranscribeClick) {
                            Icon(Icons.Default.Mic, contentDescription = "Transcribe")
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!searchActive) {
                FloatingActionButton(onClick = onNewProject) {
                    Icon(Icons.Default.Add, contentDescription = "New Project")
                }
            }
        }
    ) { padding ->
        if (searchActive && searchQuery.isNotBlank()) {
            SearchResults(
                query = searchQuery,
                results = searchResults,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onResultClick = { hit ->
                    onChatClick(hit.chatId, hit.messageId)
                }
            )
        } else if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No projects yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to create your first project",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (needsModel) {
                        Spacer(Modifier.height(20.dp))
                        FirstLaunchModelTip(onSettingsClick)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = { onProjectClick(project.id) },
                        onNewChat = { onNewChat(project.id) },
                        onLongClick = { projectToDelete = project },
                        modifier = Modifier.animateContentSize()
                    )
                }
            }
        }
    }

    projectToDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Delete project?") },
            text = { Text("\"${project.name}\" and all its chats and memories will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProject(project)
                        projectToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onNewChat: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = onNewChat,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = "New Chat",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (project.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = project.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    query: String,
    results: List<MessageSearchHit>,
    modifier: Modifier = Modifier,
    onResultClick: (MessageSearchHit) -> Unit,
) {
    if (results.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "No matches for \"$query\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.messageId }) { hit ->
                SearchResultCard(hit = hit, query = query, onClick = { onResultClick(hit) })
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    hit: MessageSearchHit,
    query: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = hit.projectName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = hit.chatTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = snippetAround(hit.content, query),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = if (hit.role == MessageRole.USER) FontStyle.Italic else FontStyle.Normal
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Trims [content] to a ~140-character window centred on the first occurrence of [query] (case
 * insensitive), with leading/trailing ellipses when the snippet was cut. Falls back to the
 * head of the message when [query] isn't found, which can happen on whitespace-collapsed text.
 */
private fun snippetAround(content: String, query: String): String {
    val window = 140
    val flat = content.replace('\n', ' ').trim()
    if (flat.length <= window) return flat
    val idx = flat.indexOf(query, ignoreCase = true).coerceAtLeast(0)
    val half = window / 2
    val start = (idx - half).coerceAtLeast(0)
    val end = (start + window).coerceAtMost(flat.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < flat.length) "…" else ""
    return "$prefix${flat.substring(start, end)}$suffix"
}

@Composable
private fun FirstLaunchModelTip(onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Load a model first",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Chats need an on-device or remote model. Set one up in Settings before " +
                        "starting your first project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text("Open", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun ModelStatusChip(modelState: ModelState, onClick: () -> Unit) {
    val (label, color) = when (modelState) {
        is ModelState.Unloaded -> "Load model" to MaterialTheme.colorScheme.primary
        is ModelState.Loading -> "Loading…" to MaterialTheme.colorScheme.tertiary
        // Compact label so the TopAppBar title doesn't get ellipsised on narrow phones —
        // "Q4 (4-bit quantised)" was eating the whole title slot.
        is ModelState.Loaded -> modelState.modelInfo.precision.shortLabel to MaterialTheme.colorScheme.primary
        is ModelState.Error -> "Error" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.padding(end = 4.dp),
        colors = AssistChipDefaults.assistChipColors(labelColor = color)
    )
}
