package com.oli.projectsai.data.appscript

import com.oli.projectsai.data.db.entity.AppScriptAuthMode

/**
 * Snapshot of an [com.oli.projectsai.data.db.entity.AppScriptTool] with secrets resolved.
 *
 * Built once at message-send time in ChatViewModel so [com.oli.projectsai.inference.GenerationController]
 * never touches the secret store. A null [secret] in SHARED_SECRET mode is allowed —
 * scripts can be deployed with no secret check.
 */
data class ResolvedAppScriptTool(
    val name: String,
    val description: String,
    val argSchemaHint: String,
    val authMode: AppScriptAuthMode,
    val webAppUrl: String,
    val scriptId: String,
    val functionName: String,
    val secret: String?
)
