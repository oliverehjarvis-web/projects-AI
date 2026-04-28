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

    fun buildGlobalRulesRefinePrompt(rawRules: String): Pair<String, String> {
        val system = """
            You rewrite AI assistant behavioural rules so they are safe for a system prompt without causing looping or self-checking behaviour.

            Fix:
            - Hard imperatives ("Always", "Never", "Must") → "By default, X. Deviate with a brief note when a request calls for it."
            - Negative constraints ("don't use X") → positive framing ("use Y instead of X")
            - Meta-instructions that ask the model to evaluate or verify its own responses → remove entirely
            - Redundant or circular phrasing → merge or remove

            Preserve: all user preferences and intent; names and specific terms verbatim.
            Output: the rewritten rules only — no preamble, no explanation, no headers.
        """.trimIndent()
        val user = "Rewrite these assistant rules:\n\n$rawRules"
        return system to user
    }

    fun buildProjectContextRefinePrompt(rawContext: String): Pair<String, String> {
        val system = """
            You rewrite a project's AI context block so it is clean and safe for a system prompt without causing looping behaviour.

            Fix:
            - Hard imperatives mixed with facts → express as soft defaults; keep facts declarative
            - Instructions that cause the model to self-check per response → reframe as defaults
            - Redundancy → merge; vague statements → tighten

            Preserve: all factual content (names, technologies, goals, identifiers) and the user's intended guidance.
            Output: the rewritten context only — no preamble, no explanation, no extra headers.
        """.trimIndent()
        val user = "Rewrite this project context:\n\n$rawContext"
        return system to user
    }

    /**
     * Replacement prompt for [LocalMediaPipeBackend.transcribe] when the user has asked for
     * speaker labels. The model can only see one chunk at a time, so labels here are local —
     * the reconcile pass below renumbers them across chunks.
     */
    fun buildDiarizedTranscriptionHint(): String =
        "Transcribe the audio verbatim. When you can clearly hear a change of speaker, " +
            "start that turn with 'Speaker 1:', 'Speaker 2:' etc. on a new line. If you cannot " +
            "tell who is speaking, do not invent a label. Output only the transcript."

    fun buildSpeakerReconcilePrompt(rawTranscript: String): Pair<String, String> {
        val system = """
            You receive a transcript that was produced one chunk at a time. Each chunk used its own
            local speaker numbering, so the same person may be labelled "Speaker 1" in one chunk and
            "Speaker 2" in another, or vice versa.

            Renumber the speakers so each real-world person has a single consistent label across the
            whole transcript. Use turn-taking, addressed names, and content style as cues. Where a
            chunk has no speaker labels, leave the text unlabelled rather than guessing.

            Also collapse any "[chunk N]" markers and stitch overlapping sentences at chunk boundaries
            into a single clean read.

            Preserve every word of the speech itself. Output only the cleaned transcript — no preamble,
            no commentary, no markdown headers.
        """.trimIndent()
        val user = "Reconcile speakers in this transcript:\n\n$rawTranscript"
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
