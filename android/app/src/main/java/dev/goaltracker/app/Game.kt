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
import androidx.compose.ui.draw.clip
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

/** A thick rounded bar that fills from the left. `fraction` is clamped to 0..1 and animates when motion is on. */
@Composable
private fun GameBar(
    fraction: Float,
    brush: Brush,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    val target = fraction.coerceIn(0f, 1f)
    val shown by animateFloatAsState(target, motionTween(LocalMotion.current), label = "gameBar")
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = description },
    ) {
        if (shown > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(shape).background(brush))
        }
    }
}

/** Experience toward the next level, in gold. */
@Composable
fun XpBar(fraction: Float, description: String, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val scheme = MaterialTheme.colorScheme
    GameBar(fraction, Brush.horizontalGradient(listOf(scheme.secondary.copy(alpha = 0.75f), scheme.secondary)), description, modifier, height)
}

/** What is left of a boss's health, in ember red. */
@Composable
fun BossHealthBar(fraction: Float, description: String, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val scheme = MaterialTheme.colorScheme
    GameBar(fraction, Brush.horizontalGradient(listOf(scheme.tertiary.copy(alpha = 0.75f), scheme.tertiary)), description, modifier, height)
}

/** Progress toward a goal or target, in violet. */
@Composable
fun QuestBar(fraction: Float, description: String, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val scheme = MaterialTheme.colorScheme
    GameBar(fraction, Brush.horizontalGradient(listOf(scheme.primary.copy(alpha = 0.75f), scheme.primary)), description, modifier, height)
}

/** A round gold badge with the hero's level number in it. */
@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(scheme.secondary, scheme.secondary.copy(alpha = 0.7f))))
            .padding(2.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .clearAndSetSemantics { contentDescription = "Level $level" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$level",
            style = MaterialTheme.typography.titleLarge,
            color = scheme.secondary,
        )
    }
}

/** A gold pill showing a coin count. */
@Composable
fun CoinChip(coins: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(scheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = "$coins coins" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(scheme.secondary))
        Text("$coins", style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
    }
}

/** A dim color for decoration only. */
internal fun Color.faint(): Color = copy(alpha = 0.35f)
