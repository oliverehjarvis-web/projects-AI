package com.oli.projectsai.core.inference

/**
 * Prompt builders used by memory summarisation flows. Kept here so the text lives next to the
 * inference code that consumes it rather than scattered across UI layers.
 */
object SummarisationPrompts {

    /**
     * Compacts an in-flight chat into a self-contained summary the model can use to keep going
     * after the original turns are dropped. Differs from [buildAddToMemoryPrompt] in goal:
     * memory bullets are durable knowledge; this is "everything you need to continue *this*
     * conversation" — recent state, open threads, the user's working preferences in-thread.
     */
    fun buildConversationCompactionPrompt(conversation: String): Pair<String, String> {
        val system = """
            You compress a chat transcript so the assistant can continue the conversation after
            the original turns are removed from context. Output is the only thing the assistant
            will see of what came before, so it must be self-contained.

            Capture:
            - The user's goal in this chat, and any sub-tasks still open.
            - Decisions, conclusions, and answers already reached (so they aren't re-derived).
            - Concrete facts the user shared: names, numbers, dates, identifiers, file paths,
              code snippets — preserved verbatim.
            - Preferences the user expressed during the chat (tone, format, what to avoid).
            - The most recent exchange in slightly more detail so the thread of conversation
              survives the cut.

            Skip pleasantries, acknowledgements, and anything trivially re-derivable.

            Format:
            - Short prose paragraphs or tight bullets — whatever reads cleanly.
            - No preamble, no closing remarks, no headings like "Summary:".
            - Write in third person referring to "the user" and "the assistant".
        """.trimIndent()
        val user = "Compact this conversation so it can continue:\n\n$conversation"
        return system to user
    }

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
     * Replacement prompt for [LocalLiteRtBackend.transcribe] when the user has asked for
     * speaker labels. The model can only see one chunk at a time, so labels here are local —
     * the reconcile pass below renumbers them across chunks.
     */
    fun buildDiarizedTranscriptionHint(): String =
        "Transcribe the audio verbatim. When you can clearly hear a change of speaker, " +
            "start that turn with 'Speaker 1:', 'Speaker 2:' etc. on a new line. If you cannot " +
            "tell who is speaking, do not invent a label. Output only the transcript."

    fun buildSpeakerReconcilePrompt(rawTranscript: String): Pair<String, String> {
        val system = """
            You receive a transcript produced one chunk at a time by an audio model that cannot
            reliably tell speakers apart within a chunk. Treat any existing "Speaker N:" labels as
            unreliable — the same label across chunks does not mean the same person, and missing
            labels do not mean a single speaker.

            Re-segment the transcript into speaker turns using only textual cues:
            - Direct address ("Thanks, Sarah", "What do you think, John?") implies the next turn is
              a different person, often the one addressed.
            - Question → answer patterns usually mark a turn change.
            - Shifts in voice — register, vocabulary, role (interviewer vs interviewee, parent vs
              child, customer vs agent), first-person claims that contradict an earlier turn.
            - Greetings, introductions, and self-references ("I'm Alex…") anchor a speaker.

            Then assign a single consistent label per person across the whole transcript. Prefer
            real names when the transcript reveals them ("Alex:", "Sarah:"); otherwise use
            "Speaker 1:", "Speaker 2:", etc., numbered in order of first appearance. Place each
            label on its own line at the start of that person's turn.

            If after honest reading you genuinely cannot detect any turn changes, output the
            transcript with a single "Speaker 1:" label at the top — but only as a last resort.
            Bias toward finding turn changes when content cues are present.

            Also collapse any "[chunk N]" markers and stitch overlapping sentences at chunk
            boundaries into a single clean read. Preserve every word of the speech itself. Output
            only the cleaned transcript — no preamble, no commentary, no markdown headers.
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
