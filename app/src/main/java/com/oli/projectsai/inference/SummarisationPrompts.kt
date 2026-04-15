package com.oli.projectsai.inference

/**
 * Prompt builders used by memory summarisation flows. Kept here so the text lives next to the
 * inference code that consumes it rather than scattered across UI layers.
 */
object SummarisationPrompts {

    fun buildAddToMemoryPrompt(conversation: String): Pair<String, String> {
        val system = """
            You extract durable knowledge from a chat transcript into concise memory notes.

            Rules:
            - Output 3-6 short bullet points, each prefixed with "- ".
            - Preserve names, numbers, dates, and identifiers exactly.
            - Focus on: decisions made, facts established, open questions, and action items.
            - Skip pleasantries, acknowledgements, and anything the user could re-derive from context.
            - No preamble, no closing remarks, no markdown headers — just the bullets.
        """.trimIndent()
        val user = "Extract memory notes from this conversation:\n\n$conversation"
        return system to user
    }

    fun buildCompressPrompt(existingMemory: String, pinned: List<String>): Pair<String, String> {
        val pinnedBlock = if (pinned.isEmpty()) "" else
            "\n\nPinned lines (must appear in the output verbatim):\n" +
                pinned.joinToString("\n") { "- $it" }
        val system = """
            You consolidate a project's long-running memory into a shorter, lossless form.

            Rules:
            - Merge duplicates; unify near-duplicates.
            - Keep every pinned line verbatim.
            - Preserve names, numbers, dates, and identifiers exactly.
            - Group related items as bullet lists; use "---" on its own line between logical sections.
            - Drop pleasantries and anything that is not a durable fact, decision, or open item.
            - No preamble, no closing remarks — just the compressed memory.$pinnedBlock
        """.trimIndent()
        val user = "Compress the following memory:\n\n$existingMemory"
        return system to user
    }
}
