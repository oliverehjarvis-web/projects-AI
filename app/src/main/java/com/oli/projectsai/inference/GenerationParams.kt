package com.oli.projectsai.inference

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
    val numCtx: Int? = null
)
