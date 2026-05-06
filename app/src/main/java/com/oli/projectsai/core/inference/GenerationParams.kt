package com.oli.projectsai.core.inference

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
     * Set when the user tapped "Answer now" mid-generation. Appends an extra system-prompt
     * directive telling the model to skip <think> deliberation and answer directly.
     */
    val forceShortAnswer: Boolean = false,
)
