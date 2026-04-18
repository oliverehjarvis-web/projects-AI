package com.oli.projectsai.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.selection.SelectionContainer
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.inference.TRANSCRIPTION_MAX_SECONDS
import com.oli.projectsai.ui.components.TokenCounter
import com.oli.projectsai.ui.memory.AddToMemoryDialog
import java.io.File

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
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val dictationState by viewModel.dictationState.collectAsStateWithLifecycle()
    val transcribedText by viewModel.transcribedText.collectAsStateWithLifecycle()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsStateWithLifecycle()
    val searchStatus by viewModel.searchStatus.collectAsStateWithLifecycle()

    val systemContext by viewModel.systemContext.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var showTokenDetail by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        uris.forEach { viewModel.addAttachment(it) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startDictation()
    }

    // Consume transcribed text into the input field as soon as it arrives.
    LaunchedEffect(transcribedText) {
        transcribedText?.let { text ->
            if (text.isNotBlank()) {
                inputText = if (inputText.isBlank()) text
                else inputText.trimEnd() + " " + text
            }
            viewModel.consumeTranscribedText()
        }
    }

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
                    IconButton(onClick = { viewModel.toggleWebSearch() }) {
                        Icon(
                            if (webSearchEnabled) Icons.Default.TravelExplore else Icons.Default.Public,
                            if (webSearchEnabled) "Web search on" else "Web search off",
                            tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current
                        )
                    }
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

            // Dictation status banner
            DictationBanner(
                state = dictationState,
                onDismissError = { viewModel.dismissDictationError() }
            )

            searchStatus?.let { status ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(status, style = MaterialTheme.typography.bodySmall)
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

            // Pending attachments strip
            if (pendingAttachments.isNotEmpty()) {
                Surface(tonalElevation = 2.dp) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pendingAttachments, key = { it }) { path ->
                            PendingAttachmentThumb(
                                path = path,
                                onRemove = { viewModel.removePendingAttachment(path) }
                            )
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !isGenerating
                    ) {
                        Icon(Icons.Default.AttachFile, "Attach image")
                    }

                    val isRecording = dictationState is ChatViewModel.DictationState.Recording
                    val isTranscribing = dictationState is ChatViewModel.DictationState.Transcribing
                    IconButton(
                        onClick = {
                            when {
                                isRecording -> viewModel.stopDictation()
                                isTranscribing -> Unit
                                else -> {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) viewModel.startDictation()
                                    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        enabled = !isGenerating && !isTranscribing
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            if (isRecording) "Stop dictation" else "Dictate",
                            tint = if (isRecording) MaterialTheme.colorScheme.error
                            else LocalContentColor.current
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message...") },
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val hasContent = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
                                if (hasContent && !isGenerating) {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            }
                        )
                    )
                    if (isGenerating) {
                        FilledIconButton(
                            onClick = { viewModel.cancelGeneration() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Stop, "Stop")
                        }
                    } else {
                        val canSend = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
                        FilledIconButton(
                            onClick = {
                                if (canSend) {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            },
                            enabled = canSend
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
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
private fun DictationBanner(
    state: ChatViewModel.DictationState,
    onDismissError: () -> Unit
) {
    when (state) {
        is ChatViewModel.DictationState.Recording -> {
            val elapsedSec = (state.elapsedMs / 1000).toInt()
            val remaining = (TRANSCRIPTION_MAX_SECONDS - elapsedSec).coerceAtLeast(0)
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Recording… ${elapsedSec}s (${remaining}s left)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        is ChatViewModel.DictationState.Transcribing -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Transcribing…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is ChatViewModel.DictationState.Error -> {
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
                        state.message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Default.Close, "Dismiss")
                    }
                }
            }
        }
        ChatViewModel.DictationState.Idle -> Unit
    }
}

@Composable
private fun PendingAttachmentThumb(path: String, onRemove: () -> Unit) {
    val bitmap = rememberImageBitmap(path, maxDim = 192)
    Box(modifier = Modifier.size(64.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(
                    MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                    RoundedCornerShape(10.dp)
                )
        ) {
            Icon(
                Icons.Default.Close,
                "Remove",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }
}

@Composable
private fun MessageAttachmentThumb(path: String) {
    val bitmap = rememberImageBitmap(path, maxDim = 384)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Image attachment",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 160.dp, height = 160.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun rememberImageBitmap(path: String, maxDim: Int): ImageBitmap? {
    return remember(path, maxDim) {
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val isUser = message.role == MessageRole.USER
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
                if (message.attachmentPaths.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = if (message.content.isBlank()) 0.dp else 8.dp)
                    ) {
                        items(message.attachmentPaths, key = { it }) { path ->
                            MessageAttachmentThumb(path)
                        }
                    }
                }
                if (message.content.isNotBlank()) {
                    if (isUser) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        RichText {
                            Markdown(content = message.content)
                        }
                    }
                }
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
            // Render streaming tokens as markdown so the layout doesn't visibly reflow
            // when the final message replaces this bubble on completion.
            Column(modifier = Modifier.padding(12.dp)) {
                RichText {
                    Markdown(content = content)
                }
            }
        }
    }
}
