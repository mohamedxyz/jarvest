package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.GoalStats
import dev.goaltracker.core.Momentum
import dev.goaltracker.core.MomentumStatus
import kotlin.math.roundToInt

/** Fixed greens and reds, because the accent color can itself be green. Never used alone: icons and words go with them. */
@Composable
fun positiveColor(): Color = if (LocalDarkTheme.current) Color(0xFF7FD99A) else Color(0xFF1B7F3B)

/** Amber for "due soon". It always comes with words such as "Today" or "2 days left". */
@Composable
fun warningColor(): Color = if (LocalDarkTheme.current) Color(0xFFFFC266) else Color(0xFF9A5B00)

@Composable
fun dueColor(due: String, today: String): Color = when (dueTone(due, today)) {
    DueTone.LATE -> negativeColor()
    DueTone.SOON -> warningColor()
    DueTone.CALM -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun negativeColor(): Color = if (LocalDarkTheme.current) Color(0xFFFF9C94) else Color(0xFFB3261E)

/** A goal needs this many periods in both windows before its trend is shown. */
private const val MIN_PERIODS = 3u

/** Moves smaller than this many percentage points are treated as noise. */
private const val CLEAR_MOVE = 5

/** How much a goal's completion rate moved, in percentage points. Null with too little history. */
fun goalTrend(stats: GoalStats): Int? {
    if (stats.eligible < MIN_PERIODS || stats.prevEligible < MIN_PERIODS) return null
    val now = stats.met.toDouble() / stats.eligible.toDouble() * 100
    val before = stats.prevMet.toDouble() / stats.prevEligible.toDouble() * 100
    return (now - before).roundToInt()
}

/** An arrow, a color and words, such as "Down 20 points". Shows nothing for a small move. */
@Composable
fun TrendChip(points: Int?, modifier: Modifier = Modifier) {
    if (points == null || kotlin.math.abs(points) < CLEAR_MOVE) return
    val up = points > 0
    val color = if (up) positiveColor() else negativeColor()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
            null,
            Modifier.size(16.dp),
            tint = color,
        )
        Text(
            if (up) "Up $points points" else "Down ${-points} points",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/** One line comparing a count with the one before it, such as "Logs: 12, up 20% on last week". */
@Composable
fun DeltaRow(label: String, now: Int, before: Int, previousName: String) {
    val change = if (before > 0) ((now - before) * 100.0 / before).roundToInt() else null
    val (icon, color, text) = when {
        change == null && now == 0 -> Triple(Icons.AutoMirrored.Filled.TrendingFlat, MaterialTheme.colorScheme.onSurfaceVariant, "none, and none $previousName")
        change == null -> Triple(Icons.AutoMirrored.Filled.TrendingUp, positiveColor(), "up from none $previousName")
        change > 0 -> Triple(Icons.AutoMirrored.Filled.TrendingUp, positiveColor(), "up $change% on $previousName")
        change < 0 -> Triple(Icons.AutoMirrored.Filled.TrendingDown, negativeColor(), "down ${-change}% on $previousName")
        else -> Triple(Icons.AutoMirrored.Filled.TrendingFlat, MaterialTheme.colorScheme.onSurfaceVariant, "same as $previousName")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon as ImageVector, null, Modifier.size(16.dp), tint = color as Color)
        Text("$label: $now, $text", style = MaterialTheme.typography.bodyMedium)
    }
}

/** The always-readable verdict at the top of Stats: advancing, steady or slipping, and why. */
@Composable
fun MomentumBanner(momentum: Momentum, modifier: Modifier = Modifier) {
    val (title, icon, color) = when (momentum.status) {
        MomentumStatus.ADVANCING -> Triple("Advancing", Icons.AutoMirrored.Filled.TrendingUp, positiveColor())
        MomentumStatus.STEADY -> Triple("Steady", Icons.AutoMirrored.Filled.TrendingFlat, MaterialTheme.colorScheme.onSurfaceVariant)
        MomentumStatus.SLIPPING -> Triple("Slipping", Icons.AutoMirrored.Filled.TrendingDown, negativeColor())
        MomentumStatus.NOT_ENOUGH_DATA -> Triple("Not enough data yet", Icons.Outlined.HourglassEmpty, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(28.dp), tint = color)
                Text(title, style = MaterialTheme.typography.headlineSmall, color = color)
                InfoHint("How this is worked out", momentum.compared + " A change of 5 percentage points or more counts as advancing or slipping.")
            }
            Text(momentum.reason, style = MaterialTheme.typography.bodyLarge)
            Text(momentum.compared, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A short status for the home-screen widgets, for example "Advancing +12 points". */
fun momentumShort(momentum: Momentum): String = when (momentum.status) {
    MomentumStatus.ADVANCING -> "Advancing, up ${momentum.changePoints} points"
    MomentumStatus.SLIPPING -> "Slipping, down ${-momentum.changePoints} points"
    MomentumStatus.STEADY -> "Steady"
    MomentumStatus.NOT_ENOUGH_DATA -> "Not enough data yet"
}
