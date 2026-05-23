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
    /** Per-`<think>`-block character budget; <= 0 disables the runaway-thinking abort. */
    val thinkBudgetChars: Int = THINK_BUDGET_CHARS,
)
