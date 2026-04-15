package com.oli.projectsai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.selection.SelectionContainer
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.ui.components.TokenCounter
import com.oli.projectsai.ui.memory.AddToMemoryDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val streamingContent by viewModel.streamingContent.collectAsStateWithLifecycle()
    val tokenBreakdown by viewModel.tokenBreakdown.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val chatTitle by viewModel.chatTitle.collectAsStateWithLifecycle()

    val systemContext by viewModel.systemContext.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var showTokenDetail by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty() || streamingContent.isNotBlank()) {
            listState.animateScrollToItem(
                (messages.size + if (streamingContent.isNotBlank()) 1 else 0).coerceAtLeast(0)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chatTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.shareConversation() }) {
                        Icon(Icons.Default.Share, "Export conversation")
                    }
                    IconButton(onClick = { showContextDialog = true }) {
                        Icon(Icons.Default.Info, "View Context")
                    }
                    IconButton(onClick = { showMemoryDialog = true }) {
                        Icon(Icons.Default.Psychology, "Add to Memory")
                    }
                    IconButton(onClick = { showTokenDetail = !showTokenDetail }) {
                        Icon(Icons.Default.DataUsage, "Token Usage")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Token counter (compact or full)
            TokenCounter(
                breakdown = tokenBreakdown,
                compact = !showTokenDetail
            )

            // Context limit warning
            if (tokenBreakdown.isCritical) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Column {
                            Text(
                                "Context nearly full",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Compress memory or start a new chat.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Error banner
            error?.let { err ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            err.message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (err.retryable) {
                            TextButton(onClick = { viewModel.retryLastPrompt() }) {
                                Text("Retry")
                            }
                        }
                        IconButton(onClick = { viewModel.dismissError() }) {
                            Icon(Icons.Default.Close, "Dismiss")
                        }
                    }
                }
            }

            // Messages
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onCopy = { viewModel.copyToClipboard(message.content) },
                        onShare = { viewModel.shareText(message.content) }
                    )
                }

                // Streaming response
                if (streamingContent.isNotBlank()) {
                    item {
                        StreamingBubble(content = streamingContent)
                    }
                }

                // Loading indicator
                if (isGenerating && streamingContent.isBlank()) {
                    item {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Thinking...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Input area
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message...") },
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank() && !isGenerating) {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            }
                        )
                    )
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !isGenerating
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }

    if (showContextDialog) {
        AlertDialog(
            onDismissRequest = { showContextDialog = false },
            title = { Text("Active Context") },
            text = {
                val text = if (systemContext.isBlank())
                    "(No context — project has no manual context or memory)"
                else systemContext
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showContextDialog = false }) { Text("Close") }
            }
        )
    }

    if (showMemoryDialog) {
        AddToMemoryDialog(
            conversationText = viewModel.getConversationForMemory(),
            modelLoaded = viewModel.isModelLoaded,
            autoSummarise = { conv -> viewModel.autoSummariseForMemory(conv) },
            onSave = { summary ->
                viewModel.addToMemory(summary)
                showMemoryDialog = false
            },
            onDismiss = { showMemoryDialog = false }
        )
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val isUser = message.role == MessageRole.USER
    var showActions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Action row
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(14.dp))
            }
            if (!isUser) {
                IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Share, "Share", modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(content: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                text = content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
