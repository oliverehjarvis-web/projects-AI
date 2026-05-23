package com.oli.projectsai.core.inference

/**
 * Decides which conversation turns fit inside a model's context window once the
 * (always-present) system prompt and a reserve for the model's reply are accounted for.
 *
 * Pure and deterministic — no token *counting* happens here; callers pass token counts in.
 * That's deliberate: the exact same arithmetic now backs both the generation path (what we
 * actually send to the backend) and the chat UI's token bar (what we show the user). Before
 * this layer existed the bar measured the whole stored conversation while the backend silently
 * truncated a different slice — the remote (Ollama) dropped the oldest tokens, which are the
 * system prompt + project memory, and the local backend kept an arbitrary fixed window. The two
 * never agreed, so changing the context-length setting only resized the bar without changing
 * what the model actually received.
 */
object ContextBudget {
    /** Calibration fallback: average characters per SentencePiece token for Gemma-family models. */
    const val DEFAULT_CHARS_PER_TOKEN = 4.0f

    /** Smallest reply we always try to leave room for, in tokens. */
    private const val MIN_OUTPUT_RESERVE = 512

    /** Cap on the reply reserve so a very large window doesn't starve the prompt. */
    private const val MAX_OUTPUT_RESERVE = 4096

    /** Cheap length-based token estimate used when a message has no stored count yet. */
    fun estimateTokens(text: String, charsPerToken: Float = DEFAULT_CHARS_PER_TOKEN): Int =
        if (text.isEmpty()) 0 else (text.length / charsPerToken).toInt().coerceAtLeast(1)

    /** Tokens we hold back for the model's reply, scaled to the window (¼, clamped). */
    fun outputReserveFor(contextLimit: Int): Int =
        (contextLimit / 4).coerceIn(MIN_OUTPUT_RESERVE, MAX_OUTPUT_RESERVE)

    /** The portion of [contextLimit] that prompt content (system + history) may actually use. */
    fun inputBudgetFor(contextLimit: Int, outputReserve: Int = outputReserveFor(contextLimit)): Int =
        (contextLimit - outputReserve).coerceAtLeast(0)

    data class Fit(
        /** Index into the chronological message list; messages before this are dropped. */
        val keepFrom: Int,
        /** Summed token count of the kept messages. */
        val conversationTokens: Int,
        /** Number of older turns left out of the window. */
        val droppedCount: Int,
        /** Tokens left for the reply after the system prompt + kept messages. */
        val outputBudget: Int,
    )

    /**
     * Greedily keeps the newest messages that fit in [contextLimit] after reserving
     * [outputReserve] for the reply and [systemPromptTokens] for the system prompt.
     *
     * The most recent message is always kept even if it alone overflows the budget — we can't
     * answer a turn we've dropped, so we hand it to the backend and let the engine truncate it
     * rather than silently producing no reply.
     */
    fun fit(
        contextLimit: Int,
        systemPromptTokens: Int,
        messageTokens: List<Int>,
        outputReserve: Int = outputReserveFor(contextLimit),
    ): Fit {
        if (messageTokens.isEmpty()) {
            return Fit(
                keepFrom = 0,
                conversationTokens = 0,
                droppedCount = 0,
                outputBudget = (contextLimit - systemPromptTokens).coerceAtLeast(MIN_OUTPUT_RESERVE),
            )
        }
        val inputBudget = (contextLimit - systemPromptTokens - outputReserve).coerceAtLeast(0)
        var used = 0
        var keepFrom = messageTokens.size
        for (i in messageTokens.indices.reversed()) {
            val isNewest = i == messageTokens.lastIndex
            val next = used + messageTokens[i]
            if (next > inputBudget && !isNewest) break
            used = next
            keepFrom = i
        }
        return Fit(
            keepFrom = keepFrom,
            conversationTokens = used,
            droppedCount = keepFrom,
            outputBudget = (contextLimit - systemPromptTokens - used).coerceAtLeast(MIN_OUTPUT_RESERVE),
        )
    }
}
