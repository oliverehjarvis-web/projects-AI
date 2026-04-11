package com.oli.projectsai.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oli.projectsai.data.db.entity.Project
import com.oli.projectsai.inference.ModelState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onProjectClick: (Long) -> Unit,
    onNewProject: () -> Unit,
    onNewChat: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    var projectToDelete by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects AI") },
                actions = {
                    ModelStatusChip(modelState)
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewProject) {
                Icon(Icons.Default.Add, contentDescription = "New Project")
            }
        }
    ) { padding ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun ModelStatusChip(modelState: ModelState) {
    val (label, color) = when (modelState) {
        is ModelState.Unloaded -> "No model" to MaterialTheme.colorScheme.onSurfaceVariant
        is ModelState.Loading -> "Loading..." to MaterialTheme.colorScheme.tertiary
        is ModelState.Loaded -> modelState.modelInfo.precision.displayName to MaterialTheme.colorScheme.primary
        is ModelState.Error -> "Error" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = { },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.padding(end = 4.dp),
        colors = AssistChipDefaults.assistChipColors(labelColor = color)
    )
}
