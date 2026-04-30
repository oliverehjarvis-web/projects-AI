package com.oli.projectsai.data.appscript

object AppScriptToolPromptBuilder {

    /**
     * Renders the per-project Apps Script tool block that goes into the system prompt.
     * Returns an empty string if there are no usable tools.
     */
    fun format(tools: List<ResolvedAppScriptTool>): String {
        if (tools.isEmpty()) return ""
        val lines = tools.joinToString("\n") { tool ->
            val args = if (tool.argSchemaHint.isNotBlank()) "  Args: ${tool.argSchemaHint}" else "  Args: { } (none)"
            "- ${tool.name} — ${tool.description}\n$args"
        }
        return """
            You also have access to project-specific data tools that read live data from the
            user's own Google Apps Script projects. Each tool returns its raw response as
            text (typically JSON). Invoke a tool by emitting EXACTLY one tag and nothing
            else on that turn:

            <appscript name="TOOL_NAME">{...args as JSON...}</appscript>

            Available tools:
            $lines

            Only call a tool when you need data you don't already have. After the tool
            result arrives you may either call another tool or answer the user. Do not
            invent tools — only use the names listed above.
        """.trimIndent()
    }
}
