package com.oli.cortex.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oli.cortex.data.db.entity.Chat
import com.oli.cortex.data.db.entity.QuickAction
import com.oli.cortex.ui.theme.TokenMemory
import com.oli.cortex.ui.theme.TokenSystemPrompt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    onNavigateBack: () -> Unit,
    onEditProject: (Long) -> Unit,
    onOpenChat: (Long) -> Unit,
    onNewChat: (Long, Long?) -> Unit,
    onOpenMemory: (Long) -> Unit,
    viewModel: ProjectViewModel = hiltViewModel()
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val quickActions by viewModel.quickActions.collectAsStateWithLifecycle()
    val contextTokens by viewModel.contextTokenCount.collectAsStateWithLifecycle()
    val memoryTokens by viewModel.memoryTokenCount.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddQuickAction by remember { mutableStateOf(false) }

    val p = project
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.name) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onEditProject(p.id) }) {
                        Icon(Icons.Default.Edit, "Edit")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNewChat(p.id, null) }) {
                Icon(Icons.Default.Add, "New Chat")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Description
            if (p.description.isNotBlank()) {
                item {
                    Text(
                        p.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Context & Memory summary
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoCard(
                        title = "Manual Context",
                        value = "$contextTokens tokens",
                        color = TokenSystemPrompt,
                        modifier = Modifier.weight(1f)
                    )
                    InfoCard(
                        title = "Memory",
                        value = "$memoryTokens / ${p.memoryTokenLimit}",
                        color = TokenMemory,
                        onClick = { onOpenMemory(p.id) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Quick Actions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quick Actions", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { showAddQuickAction = true }) {
                        Icon(Icons.Default.Add, "Add Quick Action", modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (quickActions.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(quickActions) { action ->
                            QuickActionChip(
                                action = action,
                                onClick = { onNewChat(p.id, action.id) },
                                onDelete = { viewModel.deleteQuickAction(action) }
                            )
                        }
                    }
                }
            }

            // Chat History
            item {
                Text("Chats", style = MaterialTheme.typography.titleSmall)
            }
            if (chats.isEmpty()) {
                item {
                    Text(
                        "No chats yet. Tap + to start one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(chats, key = { it.id }) { chat ->
                    ChatListItem(
                        chat = chat,
                        onClick = { onOpenChat(chat.id) },
                        onDelete = { viewModel.deleteChat(chat) }
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete project?") },
            text = { Text("This will permanently delete \"${p.name}\" and all its data.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProject()
                        showDeleteDialog = false
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddQuickAction) {
        AddQuickActionDialog(
            onDismiss = { showAddQuickAction = false },
            onAdd = { name, template ->
                viewModel.createQuickAction(name, template)
                showAddQuickAction = false
            }
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    OutlinedCard(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = color)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun QuickActionChip(
    action: QuickAction,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    InputChip(
        selected = false,
        onClick = onClick,
        label = { Text(action.name) },
        trailingIcon = {
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(18.dp)
                ) {
                    Icon(Icons.Default.MoreVert, "Options", modifier = Modifier.size(14.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                    )
                }
            }
        }
    )
}

@Composable
private fun ChatListItem(chat: Chat, onClick: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(chat.updatedAt)),
                style = MaterialTheme.typography.labelSmall
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "Options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun AddQuickActionDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Quick Action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("Prompt template") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name.trim(), template.trim()) },
                enabled = name.isNotBlank() && template.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
