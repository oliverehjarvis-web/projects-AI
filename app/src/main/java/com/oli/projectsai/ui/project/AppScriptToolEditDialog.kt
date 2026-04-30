package com.oli.projectsai.ui.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oli.projectsai.data.db.entity.AppScriptAuthMode
import com.oli.projectsai.data.db.entity.AppScriptTool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScriptToolEditDialog(
    initial: AppScriptTool,
    googleSignedIn: Boolean,
    googleAccount: String?,
    googleConfigured: Boolean,
    testResult: String?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        description: String,
        argSchemaHint: String,
        authMode: AppScriptAuthMode,
        webAppUrl: String,
        scriptId: String,
        functionName: String,
        enabled: Boolean,
        secretInput: String?
    ) -> Unit,
    onTest: (secretInputOverride: String?) -> Unit,
    onClearTest: () -> Unit,
    onConnectGoogle: () -> Unit,
    onDisconnectGoogle: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var argSchemaHint by rememberSaveable { mutableStateOf(initial.argSchemaHint) }
    var authMode by rememberSaveable { mutableStateOf(initial.authMode) }
    var webAppUrl by rememberSaveable { mutableStateOf(initial.webAppUrl) }
    var scriptId by rememberSaveable { mutableStateOf(initial.scriptId) }
    var functionName by rememberSaveable { mutableStateOf(initial.functionName) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }

    // Secret is a separate "did the user touch it" state. null = leave existing untouched.
    var secretText by rememberSaveable { mutableStateOf("") }
    var secretTouched by rememberSaveable { mutableStateOf(false) }
    var secretVisible by rememberSaveable { mutableStateOf(false) }

    val secretToSubmit: String? = if (secretTouched) secretText else null

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (initial.id == 0L) "Add data source" else "Edit data source",
                    style = MaterialTheme.typography.titleMedium
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(40) },
                    label = { Text("Tool name") },
                    placeholder = { Text("e.g. getTasks") },
                    singleLine = true,
                    supportingText = { Text("The model uses this in <appscript name=\"...\">. Letters, digits, and underscores.") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (shown to the model)") },
                    placeholder = { Text("e.g. Returns my open Todoist tasks") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = argSchemaHint,
                    onValueChange = { argSchemaHint = it },
                    label = { Text("Args hint (optional)") },
                    placeholder = { Text("e.g. { since?: ISO date, status?: 'open'|'done' }") },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Authentication", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = authMode == AppScriptAuthMode.SHARED_SECRET,
                        onClick = { authMode = AppScriptAuthMode.SHARED_SECRET },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("Shared secret") }
                    SegmentedButton(
                        selected = authMode == AppScriptAuthMode.OAUTH,
                        onClick = { authMode = AppScriptAuthMode.OAUTH },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("Google OAuth") }
                }

                when (authMode) {
                    AppScriptAuthMode.SHARED_SECRET -> {
                        OutlinedTextField(
                            value = webAppUrl,
                            onValueChange = { webAppUrl = it.trim() },
                            label = { Text("Web App URL") },
                            placeholder = { Text("https://script.google.com/macros/s/.../exec") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val placeholder = if (initial.secretRef != null && !secretTouched)
                            "•••••••• (leave blank to keep)" else "Optional shared secret"
                        OutlinedTextField(
                            value = secretText,
                            onValueChange = { secretText = it; secretTouched = true },
                            label = { Text("Shared secret (optional)") },
                            placeholder = { Text(placeholder) },
                            singleLine = true,
                            visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            trailingIcon = {
                                IconButton(onClick = { secretVisible = !secretVisible }) {
                                    Icon(
                                        if (secretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (secretVisible) "Hide" else "Show"
                                    )
                                }
                            },
                            supportingText = {
                                Text("Sent in the JSON body as { secret, args }. Your doPost reads e.postData.contents and JSON.parse's it.")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (secretTouched && secretText.isBlank() && initial.secretRef != null) {
                            Text(
                                "Saving will clear the stored secret on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    AppScriptAuthMode.OAUTH -> {
                        OutlinedTextField(
                            value = scriptId,
                            onValueChange = { scriptId = it.trim() },
                            label = { Text("Apps Script ID") },
                            placeholder = { Text("From script editor URL: /d/{ID}/edit") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = functionName,
                            onValueChange = { functionName = it.trim() },
                            label = { Text("Function name") },
                            placeholder = { Text("e.g. listRows") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!googleConfigured) {
                            Text(
                                "Google OAuth is not configured. Add GOOGLE_WEB_CLIENT_ID to local.properties — see README.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (googleSignedIn) {
                                Text(
                                    "Connected${googleAccount?.let { " — $it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDisconnectGoogle) { Text("Disconnect") }
                            } else {
                                Button(
                                    onClick = onConnectGoogle,
                                    enabled = googleConfigured,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Connect Google account") }
                            }
                        }
                        Text(
                            "Your Apps Script must live in the same GCP project as the Web client ID and " +
                                "have the Apps Script API enabled. The required scopes are requested at sign-in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Enabled", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                HorizontalDivider()

                if (testResult != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    "Response",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onClearTest) { Text("Clear") }
                            }
                            Text(
                                testResult.take(2000),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (testResult.length > 2000) {
                                Text(
                                    "(truncated to 2000 chars)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            // Use whatever's typed in the secret field if shared-secret; otherwise
                            // resolve via the stored secretRef inside the VM.
                            onTest(if (authMode == AppScriptAuthMode.SHARED_SECRET && secretTouched) secretText else null)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Test") }
                    Button(
                        onClick = {
                            onSave(name, description, argSchemaHint, authMode, webAppUrl, scriptId, functionName, enabled, secretToSubmit)
                        },
                        enabled = name.isNotBlank() && when (authMode) {
                            AppScriptAuthMode.SHARED_SECRET -> webAppUrl.isNotBlank()
                            AppScriptAuthMode.OAUTH -> scriptId.isNotBlank() && functionName.isNotBlank()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Wraps a launcher that fires the OAuth consent PendingIntent, handing the result back to
 * the ViewModel. Place this near where you observe `oauthConsentIntent` in the screen.
 */
@Composable
fun rememberOAuthLauncher(
    onResult: (android.content.Intent?) -> Unit
) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartIntentSenderForResult()
) { result -> onResult(result.data) }
