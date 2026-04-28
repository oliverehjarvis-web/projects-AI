package com.oli.projectsai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText

/**
 * Renders assistant markdown content with cloud-AI-style fenced code blocks:
 * each ``` fence becomes a card with a language header and a copy button, and a
 * horizontally-scrollable monospace body. Prose between fences flows through the
 * standard markdown renderer wrapped in [SelectionContainer] so users can long-press
 * to select arbitrary spans.
 */
@Composable
fun MarkdownContent(
    content: String,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segments = remember(content) { parseMarkdownSegments(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is MarkdownSegment.Code -> CodeBlock(
                    language = seg.language,
                    code = seg.code,
                    onCopy = { onCopyCode(seg.code) }
                )
                is MarkdownSegment.Text -> if (seg.text.isNotBlank()) {
                    SelectionContainer {
                        RichText { Markdown(content = seg.text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String, onCopy: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
            }
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

internal sealed class MarkdownSegment {
    data class Text(val text: String) : MarkdownSegment()
    data class Code(val language: String, val code: String) : MarkdownSegment()
}

// Matches a fenced code block with optional language. The closing fence is
// optional so we still render a code card while the model is mid-stream.
private val FENCE_REGEX = Regex("```([\\w+\\-.]*)\\n([\\s\\S]*?)(?:\\n```|$)")

internal fun parseMarkdownSegments(content: String): List<MarkdownSegment> {
    if (!content.contains("```")) return listOf(MarkdownSegment.Text(content))
    val out = mutableListOf<MarkdownSegment>()
    var cursor = 0
    for (m in FENCE_REGEX.findAll(content)) {
        if (m.range.first > cursor) {
            out += MarkdownSegment.Text(content.substring(cursor, m.range.first))
        }
        out += MarkdownSegment.Code(
            language = m.groupValues[1],
            code = m.groupValues[2]
        )
        cursor = m.range.last + 1
    }
    if (cursor < content.length) {
        out += MarkdownSegment.Text(content.substring(cursor))
    }
    return out
}
