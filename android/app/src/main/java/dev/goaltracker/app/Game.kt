package dev.goaltracker.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared pieces of the game look: bars for XP and boss health, a level badge and a coin chip.
 * They only draw what they are given. They never read or change data.
 */

/**
 * A bar that fills from the left, drawn in the current look: a thick rounded bar (fantasy),
 * square segments (pixel), a slim lit bar with ticks (neon) or a thin flat line (clean).
 * `fraction` is clamped to 0..1 and animates when motion is on.
 */
@Composable
private fun GameBar(
    fraction: Float,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    val style = LocalStyle.current
    val scheme = MaterialTheme.colorScheme
    val target = fraction.coerceIn(0f, 1f)
    val shown by animateFloatAsState(target, motionTween(LocalMotion.current), label = "gameBar")
    val h = barHeight(style, height)
    val base = modifier.fillMaxWidth().height(h).semantics { contentDescription = description }
    when (style) {
        Style.PIXEL -> {
            // Ten blocks inside a chunky frame; a block lights only when it is full.
            val blocks = 10
            val lit = (shown * blocks + 0.001f).toInt()
            Row(
                base.border(2.dp, scheme.outline).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(blocks) { i ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (i < lit) color else scheme.surfaceVariant))
                }
            }
        }
        Style.NEON -> Box(
            base
                .background(scheme.surfaceContainerHighest)
                .border(1.dp, color.copy(alpha = 0.5f))
                .drawWithContent {
                    drawContent()
                    // Ticks every tenth, like a gauge.
                    for (k in 1 until 10) {
                        val x = size.width * k / 10f
                        drawLine(scheme.surface.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                },
        ) {
            if (shown > 0f) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(shown)
                        .shadow(8.dp, RectangleShape, ambientColor = color, spotColor = color)
                        .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color))),
                )
            }
        }
        Style.CLEAN -> {
            val shape = RoundedCornerShape(percent = 50)
            Box(base.clip(shape).background(scheme.surfaceContainerHighest)) {
                if (shown > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(shape).background(color))
            }
        }
        Style.FANTASY -> {
            val shape = RoundedCornerShape(percent = 50)
            Box(base.clip(shape).background(scheme.surfaceVariant)) {
                if (shown > 0f) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(shown).clip(shape)
                            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.75f), color))),
                    )
                }
            }
        }
    }
}

/** Experience toward the next level, in the reward color. */
@Composable
fun XpBar(fraction: Float, description: String, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    GameBar(fraction, MaterialTheme.colorScheme.secondary, description, modifier, height)
}

/** What is left of a boss's health, in the danger color. */
@Composable
fun BossHealthBar(fraction: Float, description: String, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    GameBar(fraction, MaterialTheme.colorScheme.tertiary, description, modifier, height)
}

/** Progress toward a goal or target, in the main color. */
@Composable
fun QuestBar(fraction: Float, description: String, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    GameBar(fraction, MaterialTheme.colorScheme.primary, description, modifier, height)
}

/** A badge with the hero's level number: a gold ring, a pixel frame, a neon hexagon or a plain circle. */
@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val scheme = MaterialTheme.colorScheme
    val style = LocalStyle.current
    val shape = badgeShape(style)
    val ring: Brush = when (style) {
        Style.CLEAN -> SolidColor(scheme.outlineVariant)
        Style.PIXEL, Style.NEON -> SolidColor(scheme.secondary)
        Style.FANTASY -> Brush.verticalGradient(listOf(scheme.secondary, scheme.secondary.copy(alpha = 0.7f)))
    }
    val outer = if (style == Style.NEON) modifier.shadow(10.dp, shape, ambientColor = scheme.secondary, spotColor = scheme.secondary) else modifier
    Box(
        outer
            .size(size)
            .clip(shape)
            .background(ring)
            .padding(if (style == Style.PIXEL) 3.dp else if (style == Style.CLEAN) 1.5.dp else 2.dp)
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .clearAndSetSemantics { contentDescription = "Level $level" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$level",
            style = MaterialTheme.typography.titleLarge,
            color = if (style == Style.CLEAN) scheme.primary else scheme.secondary,
        )
    }
}

/** A pill showing a coin count; the coin is round, square (pixel) or a diamond (neon). */
@Composable
fun CoinChip(coins: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val style = LocalStyle.current
    Row(
        modifier
            .clip(pillShape(style))
            .background(scheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = "$coins coins" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coin = when (style) {
            Style.PIXEL -> Modifier.size(10.dp).background(scheme.secondary)
            Style.NEON -> Modifier.size(9.dp).rotate(45f).background(scheme.secondary)
            else -> Modifier.size(12.dp).clip(CircleShape).background(scheme.secondary)
        }
        Box(coin)
        Text("$coins", style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
    }
}

/** A dim color for decoration only. */
internal fun Color.faint(): Color = copy(alpha = 0.35f)
