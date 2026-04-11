package com.oli.projectsai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oli.projectsai.ui.theme.*

data class TokenBreakdown(
    val systemPrompt: Int = 0,
    val memory: Int = 0,
    val conversation: Int = 0,
    val contextLimit: Int = 8192
) {
    val total get() = systemPrompt + memory + conversation
    val remaining get() = (contextLimit - total).coerceAtLeast(0)
    val usagePercent get() = if (contextLimit > 0) total.toFloat() / contextLimit else 0f
    val isWarning get() = usagePercent > 0.75f
}

@Composable
fun TokenCounter(
    breakdown: TokenBreakdown,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val animatedUsage by animateFloatAsState(breakdown.usagePercent, label = "usage")

    if (compact) {
        CompactTokenCounter(breakdown, animatedUsage, modifier)
    } else {
        FullTokenCounter(breakdown, animatedUsage, modifier)
    }
}

@Composable
private fun CompactTokenCounter(
    breakdown: TokenBreakdown,
    usage: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TokenBar(
            breakdown = breakdown,
            usage = usage,
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
        )
        Text(
            text = "${breakdown.remaining}",
            style = MaterialTheme.typography.labelSmall,
            color = if (breakdown.isWarning) TokenWarning else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FullTokenCounter(
    breakdown: TokenBreakdown,
    usage: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TokenBar(
            breakdown = breakdown,
            usage = usage,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TokenLabel("System", breakdown.systemPrompt, TokenSystemPrompt)
            TokenLabel("Memory", breakdown.memory, TokenMemory)
            TokenLabel("Chat", breakdown.conversation, TokenConversation)
            TokenLabel(
                "Free",
                breakdown.remaining,
                if (breakdown.isWarning) TokenWarning else TokenRemaining
            )
        }
    }
}

@Composable
private fun TokenBar(
    breakdown: TokenBreakdown,
    usage: Float,
    modifier: Modifier = Modifier
) {
    val total = breakdown.contextLimit.toFloat().coerceAtLeast(1f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val radius = h / 2

        // Background
        drawRoundRect(
            color = TokenRemaining,
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
        )

        var x = 0f

        // System prompt segment
        val sysWidth = (breakdown.systemPrompt / total) * w
        if (sysWidth > 0) {
            drawRect(color = TokenSystemPrompt, topLeft = Offset(x, 0f), size = Size(sysWidth, h))
            x += sysWidth
        }

        // Memory segment
        val memWidth = (breakdown.memory / total) * w
        if (memWidth > 0) {
            drawRect(color = TokenMemory, topLeft = Offset(x, 0f), size = Size(memWidth, h))
            x += memWidth
        }

        // Conversation segment
        val convWidth = (breakdown.conversation / total) * w
        if (convWidth > 0) {
            drawRect(color = TokenConversation, topLeft = Offset(x, 0f), size = Size(convWidth, h))
        }
    }
}

@Composable
private fun TokenLabel(label: String, count: Int, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Text(
            "$label: $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
