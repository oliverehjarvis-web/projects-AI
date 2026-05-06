package com.oli.projectsai.features.chat

import com.oli.projectsai.features.repo.github.RepoSelectionStore

/**
 * Pure helpers used by [ChatViewModel] to compose the system prompt from the user's global
 * profile, the current project's manual context + accumulated memory, and any GitHub repo
 * files the user has staged for the next turn.
 *
 * No state — all functions take their inputs explicitly so they're trivially testable and
 * the VM stays the single source of truth for the underlying values.
 */
internal object PromptBuilder {

    /** Caps memory at the project's token limit. ~4 chars/token (Gemma SentencePiece calibrated). */
    fun trimMemoryToLimit(memory: String, memoryTokenLimit: Int): String {
        if (memoryTokenLimit >= Int.MAX_VALUE || memoryTokenLimit <= 0) return memory
        val charBudget = memoryTokenLimit * 4
        return if (memory.length > charBudget) {
            memory.take(charBudget) + "\n[memory truncated — compress in Memory settings]"
        } else memory
    }

    fun buildSystemPrompt(
        name: String,
        rules: String,
        manualContext: String,
        memory: String,
        memoryTokenLimit: Int,
    ): String = buildList {
        val globalBlock = buildGlobalBlock(name, rules)
        if (globalBlock.isNotBlank()) add(globalBlock)
        if (manualContext.isNotBlank()) add("<project_context>\n$manualContext\n</project_context>")
        if (memory.isNotBlank()) {
            add("<memory>\n${trimMemoryToLimit(memory, memoryTokenLimit)}\n</memory>")
        }
    }.joinToString("\n\n")

    /**
     * User profile / standing rules block. Framed as soft preferences, not hard rules, and
     * wrapped in `<user_profile>` so the model clearly distinguishes this from project facts
     * and memory. Phrasing matches the server-side preamble so thinking-capable models don't
     * fall into a rule-checking loop where they re-evaluate each constraint against each draft
     * reply — a 15-minute-thinking failure mode we hit before.
     */
    fun buildGlobalBlock(name: String, rules: String): String {
        val parts = mutableListOf<String>()
        if (name.isNotBlank()) parts.add("You are speaking with ${name.trim()}.")
        if (rules.isNotBlank()) {
            parts.add(
                "The user has these standing guidelines (soft preferences — follow by default, " +
                    "deviate with a brief note when a specific request needs it):\n${rules.trim()}",
            )
        }
        if (parts.isEmpty()) return ""
        return "<user_profile>\n${parts.joinToString("\n\n")}\n</user_profile>"
    }

    /** Wraps the staged repo files in an XML block the model can refer back to. */
    fun buildRepoContextBlock(selection: RepoSelectionStore.Selection?): String {
        if (selection == null || selection.files.isEmpty()) return ""
        val header = "<repo_context owner=\"${selection.owner}\" repo=\"${selection.repo}\" ref=\"${selection.ref}\">"
        val body = selection.files.joinToString("\n") { f ->
            "<file path=\"${f.path}\">\n${f.text}\n</file>"
        }
        return "$header\n$body\n</repo_context>"
    }
}
