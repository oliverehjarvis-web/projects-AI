package com.oli.projectsai.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.itemsIndexed
import com.oli.projectsai.data.db.entity.Message
import com.oli.projectsai.data.db.entity.MessageRole
import com.oli.projectsai.ui.components.TokenCounter
import com.oli.projectsai.ui.memory.AddToMemoryDialog
import kotlinx.coroutines.launch
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
    val dictationRms by viewModel.dictationRms.collectAsStateWithLifecycle()
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showCopyToast: (String) -> Unit = { label ->
        scope.launch { snackbarHostState.showSnackbar(label, duration = SnackbarDuration.Short) }
    }

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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignored — service falls back to silent if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                rms = dictationRms,
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
                itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                    val isLastAssistant = message.role == MessageRole.ASSISTANT &&
                        index == messages.lastIndex
                    MessageBubble(
                        message = message,
                        canRegenerate = isLastAssistant && !isGenerating,
                        onCopy = {
                            viewModel.copyToClipboard(message.content)
                            showCopyToast("Copied message")
                        },
                        onShare = { viewModel.shareText(message.content) },
                        onCopyCode = { code ->
                            viewModel.copyToClipboard(code)
                            showCopyToast("Copied code")
                        },
                        onRegenerate = { viewModel.regenerateLastResponse() }
                    )
                }

                // Streaming response
                if (streamingContent.isNotBlank()) {
                    item {
                        StreamingBubble(
                            content = streamingContent,
                            onCopyCode = { code ->
                                viewModel.copyToClipboard(code)
                                showCopyToast("Copied code")
                            }
                        )
                    }
                }

                // Loading indicator — ticks an elapsed counter so the user can tell the model
                // is still working during slow prompt processing on CPU.
                if (isGenerating && streamingContent.isBlank()) {
                    item {
                        ThinkingIndicator()
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
                    if (isRecording) {
                        RecordingMicButton(onStop = { viewModel.stopDictation() })
                    } else {
                        IconButton(
                            onClick = {
                                if (!isTranscribing) {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) viewModel.startDictation()
                                    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = !isGenerating && !isTranscribing
                        ) {
                            Icon(Icons.Default.Mic, "Dictate")
                        }
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message...") },
                        maxLines = 6,
                        // Default IME action lets Enter insert a newline; send is via the button.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
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
private fun ThinkingIndicator() {
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - started) / 1000).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }
    val label = when {
        elapsedSec < 5 -> "Thinking…"
        elapsedSec < 20 -> "Processing prompt… ${elapsedSec}s"
        else -> "Still working… ${elapsedSec}s (large prompts can take a few minutes on CPU)"
    }
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DictationBanner(
    state: ChatViewModel.DictationState,
    rms: Float,
    onDismissError: () -> Unit
) {
    when (state) {
        is ChatViewModel.DictationState.Recording -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AudioWaveform(rms = rms, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Tap stop when done",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }
                    if (state.partialText.isNotBlank()) {
                        Text(
                            state.partialText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
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
                    Text("Recognising…", style = MaterialTheme.typography.bodySmall)
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
private fun RecordingMicButton(onStop: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "mic-pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "scale"
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.45f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "alpha"
    )
    val errorColor = MaterialTheme.colorScheme.error
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = errorColor.copy(alpha = alpha), radius = size.minDimension / 2 * scale)
        }
        FilledIconButton(
            onClick = onStop,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Stop, "Stop recording")
        }
    }
}

@Composable
private fun AudioWaveform(rms: Float, color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val barMaxHeights = listOf(10f, 16f, 22f, 16f, 10f)
    val barDelays    = listOf(0,   120,  240,  360,  480)
    // Animate between fixed bounds so the spec is stable; scale at draw-time
    // so bars shrink when silent and grow with the user's voice level.
    val scale = 0.3f + 0.7f * rms
    val heights = barMaxHeights.zip(barDelays).map { (maxH, delay) ->
        transition.animateFloat(
            initialValue = 3f, targetValue = maxH,
            animationSpec = infiniteRepeatable(
                tween(500, delayMillis = delay, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "bar-$delay"
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.height(24.dp)
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((h.value * scale).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
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
    canRegenerate: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCopyCode: (String) -> Unit,
    onRegenerate: () -> Unit
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
                        SelectionContainer {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    } else {
                        ThinkAwareMarkdown(
                            content = message.content,
                            isStreaming = false,
                            onCopyCode = onCopyCode
                        )
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
                if (canRegenerate) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, "Regenerate", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(content: String, onCopyCode: (String) -> Unit) {
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
            Column(modifier = Modifier.padding(12.dp)) {
                ThinkAwareMarkdown(content = content, isStreaming = true, onCopyCode = onCopyCode)
            }
        }
    }
}

/**
 * Renders assistant content, splitting any `<think>…</think>` blocks into
 * collapsible cards so the chain-of-thought doesn't crowd the main answer.
 * While streaming, the last think block stays expanded so the user sees the
 * model work; once the message is finalised, thinking collapses by default.
 */
@Composable
private fun ThinkAwareMarkdown(
    content: String,
    isStreaming: Boolean,
    onCopyCode: (String) -> Unit
) {
    val segments = remember(content) { parseThinkSegments(content) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { index, seg ->
            if (seg.isThinking) {
                val isLast = index == segments.lastIndex
                ThinkingBlock(
                    text = seg.text,
                    // While streaming, keep the currently-growing think block open.
                    initiallyExpanded = isStreaming && isLast && !seg.closed
                )
            } else if (seg.text.isNotBlank()) {
                MarkdownContent(content = seg.text, onCopyCode = onCopyCode)
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, initiallyExpanded: Boolean) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (expanded) "Hide thinking" else "Show thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = text.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private data class ThinkSegment(val isThinking: Boolean, val text: String, val closed: Boolean)

private fun parseThinkSegments(content: String): List<ThinkSegment> {
    if (!content.contains("<think>")) return listOf(ThinkSegment(false, content, true))
    val out = mutableListOf<ThinkSegment>()
    val pattern = Regex("<think>([\\s\\S]*?)(</think>|$)")
    var cursor = 0
    for (m in pattern.findAll(content)) {
        if (m.range.first > cursor) {
            out += ThinkSegment(false, content.substring(cursor, m.range.first), true)
        }
        val inner = m.groupValues[1]
        val closed = m.groupValues[2] == "</think>"
        out += ThinkSegment(true, inner, closed)
        cursor = m.range.last + 1
    }
    if (cursor < content.length) {
        out += ThinkSegment(false, content.substring(cursor), true)
    }
    return out
}
