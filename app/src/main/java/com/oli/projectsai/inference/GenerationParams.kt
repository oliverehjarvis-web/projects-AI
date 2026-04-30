package com.oli.projectsai.inference

import com.oli.projectsai.data.appscript.ResolvedAppScriptTool

data class GenerationParams(
    val chatId: Long,
    val currentUserContent: String?,
    val currentAttachments: List<String>,
    val systemPrompt: String,
    val webSearchEnabled: Boolean,
    val chatTitleHint: String,
    val backendId: String? = null,
    val applyDefaultPreamble: Boolean = true,
    val maxOutputTokens: Int = 16000,
    /** Forwarded to remote backends so Ollama loads with the right window. */
    val numCtx: Int? = null,
    /**
     * Per-project Apps Script tools the model may invoke via <appscript name="...">
     * tags. Secrets are resolved at send time so [GenerationController] never reads
     * the secret store.
     */
    val appScriptTools: List<ResolvedAppScriptTool> = emptyList()
)
