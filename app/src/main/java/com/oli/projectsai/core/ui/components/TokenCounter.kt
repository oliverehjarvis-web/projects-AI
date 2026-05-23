package com.oli.projectsai.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.oli.projectsai.core.ui.theme.*

@Immutable
data class TokenBreakdown(
    val systemPrompt: Int = 0,
    val memory: Int = 0,
    val conversation: Int = 0,
    // The usable input budget: the project's context window minus the reply reserve. The bar is
    // sized against this so "full" reads as 100% — what the model actually receives as input can
    // fill the whole bar, instead of the old behaviour where total could exceed the window while
    // generation still (silently, lossily) worked.
    val contextLimit: Int = 8192,
    // Older turns that have slid out of the window. They remain in the transcript/DB, just not in
    // what the model sees this turn.
    val droppedTurns: Int = 0,
    // Tokens held back from the input budget for the model's reply. Display-only.
    val reservedOutput: Int = 0,
) {
    val total get() = systemPrompt + memory + conversation
    val remaining get() = (contextLimit - total).coerceAtLeast(0)
    val usagePercent get() = if (contextLimit > 0) total.toFloat() / contextLimit else 0f
    val isWarning get() = usagePercent > 0.75f
    val isCritical get() = usagePercent > 0.9f
    val historyTrimmed get() = droppedTurns > 0
}

@Composable
fun TokenCounter(
    breakdown: TokenBreakdown,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val animatedUsage by animateFloatAsState(breakdown.usagePercent, label = "usage")
    val pulseAlpha = rememberDangerPulseAlpha(breakdown)

    if (compact) {
        CompactTokenCounter(breakdown, animatedUsage, pulseAlpha, modifier)
    } else {
        FullTokenCounter(breakdown, animatedUsage, pulseAlpha, modifier)
    }
}

/**
 * Pulses between 0.6 and 1.0 once you cross [TokenBreakdown.isWarning], speeding up past
 * [TokenBreakdown.isCritical]. Returns 1.0 (no pulse) below the warning threshold so the
 * common case stays visually quiet.
 */
@Composable
private fun rememberDangerPulseAlpha(breakdown: TokenBreakdown): Float {
    if (!breakdown.isWarning) return 1f
    val transition = rememberInfiniteTransition(label = "token-pulse")
    val cycleMs = if (breakdown.isCritical) 600 else 1200
    val alpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(cycleMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    return alpha
}

@Composable
private fun CompactTokenCounter(
    breakdown: TokenBreakdown,
    usage: Float,
    pulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    val warningColor = warningColorFor(breakdown)
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TokenBar(
            breakdown = breakdown,
            usage = usage,
            pulseAlpha = pulseAlpha,
            warningColor = warningColor,
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
        )
        Text(
            text = "${breakdown.remaining}",
            style = MaterialTheme.typography.labelSmall,
            color = if (breakdown.isWarning) warningColor.copy(alpha = pulseAlpha)
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Amber at >75%, smoothly shifting toward error red as we approach 100%. */
@Composable
private fun warningColorFor(breakdown: TokenBreakdown): Color {
    if (!breakdown.isWarning) return TokenWarning
    val errorColor = MaterialTheme.colorScheme.error
    // 0 at 75% usage, 1 at 95%+ usage.
    val t = ((breakdown.usagePercent - 0.75f) / 0.20f).coerceIn(0f, 1f)
    return lerp(TokenWarning, errorColor, t)
}

@Composable
private fun FullTokenCounter(
    breakdown: TokenBreakdown,
    usage: Float,
    pulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    val warningColor = warningColorFor(breakdown)
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TokenBar(
            breakdown = breakdown,
            usage = usage,
            pulseAlpha = pulseAlpha,
            warningColor = warningColor,
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
                if (breakdown.isWarning) warningColor.copy(alpha = pulseAlpha) else TokenRemaining
            )
        }
        if (breakdown.historyTrimmed || breakdown.reservedOutput > 0) {
            val parts = buildList {
                if (breakdown.historyTrimmed) {
                    val turns = breakdown.droppedTurns
                    add("$turns older ${if (turns == 1) "turn" else "turns"} out of context")
                }
                if (breakdown.reservedOutput > 0) add("${breakdown.reservedOutput} reserved for reply")
            }
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TokenBar(
    breakdown: TokenBreakdown,
    usage: Float,
    pulseAlpha: Float,
    warningColor: Color,
    modifier: Modifier = Modifier
) {
    val total = breakdown.contextLimit.toFloat().coerceAtLeast(1f)
    // Background tint of the unused segment: stays neutral grey when comfortable, picks up the
    // warning colour with the breathing alpha once we cross 75 %.
    val backgroundColor = if (breakdown.isWarning)
        lerp(TokenRemaining, warningColor, pulseAlpha * 0.45f)
    else
        TokenRemaining

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val radius = h / 2

        // Background (the "free" segment).
        drawRoundRect(
            color = backgroundColor,
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
