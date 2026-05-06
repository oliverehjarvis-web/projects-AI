package com.oli.projectsai.features.linkedin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oli.projectsai.core.db.entity.LinkedInSuggestion
import com.oli.projectsai.core.db.entity.SuggestedAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedInScreen(
    onNavigateBack: () -> Unit,
    viewModel: LinkedInViewModel = hiltViewModel()
) {
    val agentUrl by viewModel.agentUrl.collectAsStateWithLifecycle()
    val agentToken by viewModel.agentToken.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); viewModel.consumeToast() }
    }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("LinkedIn") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !isRefreshing && agentUrl.isNotBlank() && agentToken.isNotBlank()
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AgentConnectionCard(
                    agentUrl = agentUrl,
                    agentToken = agentToken,
                    onAgentUrlChange = viewModel::setAgentUrl,
                    onAgentTokenChange = viewModel::setAgentToken
                )
            }

            if (suggestions.isEmpty()) {
                item {
                    Text(
                        if (agentUrl.isBlank() || agentToken.isBlank())
                            "Configure the agent above, then tap refresh."
                        else
                            "No pending suggestions. Tap refresh to scrape your feed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            } else {
                items(suggestions, key = { it.id }) { s ->
                    SuggestionCard(
                        suggestion = s,
                        onApprove = { edited -> viewModel.approve(s.id, edited) },
                        onSkip = { viewModel.reject(s.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentConnectionCard(
    agentUrl: String,
    agentToken: String,
    onAgentUrlChange: (String) -> Unit,
    onAgentTokenChange: (String) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Agent connection", style = MaterialTheme.typography.titleSmall)
            Text(
                "Reach the standalone linkedin-agent service over HTTPS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = agentUrl,
                onValueChange = onAgentUrlChange,
                label = { Text("Agent URL") },
                singleLine = true,
                placeholder = { Text("https://linkedin.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = agentToken,
                onValueChange = onAgentTokenChange,
                label = { Text("Bearer token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: LinkedInSuggestion,
    onApprove: (editedComment: String?) -> Unit,
    onSkip: () -> Unit
) {
    var editedComment by remember(suggestion.id) {
        mutableStateOf(suggestion.suggestedComment.orEmpty())
    }

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(suggestion.authorName, style = MaterialTheme.typography.titleSmall)
                    if (suggestion.authorHeadline.isNotBlank()) {
                        Text(
                            suggestion.authorHeadline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                AssistChip(
                    onClick = {},
                    label = { Text("${(suggestion.score * 100).toInt()}%") },
                    enabled = false
                )
            }

            Text(
                suggestion.postText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6
            )

            HorizontalDivider()

            when (suggestion.suggestedAction) {
                SuggestedAction.LIKE -> {
                    Text("Suggested: Like", style = MaterialTheme.typography.labelLarge)
                }
                SuggestedAction.COMMENT -> {
                    Text("Suggested comment", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = editedComment,
                        onValueChange = { editedComment = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
                SuggestedAction.SKIP -> {
                    Text(
                        suggestion.errorMessage ?: "Suggested: Skip",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onSkip) { Text("Skip") }
                Button(
                    onClick = {
                        val edited = if (suggestion.suggestedAction == SuggestedAction.COMMENT)
                            editedComment.takeIf { it != suggestion.suggestedComment.orEmpty() }
                        else null
                        onApprove(edited)
                    },
                    enabled = suggestion.suggestedAction != SuggestedAction.SKIP &&
                        (suggestion.suggestedAction != SuggestedAction.COMMENT || editedComment.isNotBlank())
                ) {
                    Text(
                        when (suggestion.suggestedAction) {
                            SuggestedAction.LIKE -> "Like"
                            SuggestedAction.COMMENT -> "Comment"
                            SuggestedAction.SKIP -> "Skip"
                        }
                    )
                }
            }
        }
    }
}
