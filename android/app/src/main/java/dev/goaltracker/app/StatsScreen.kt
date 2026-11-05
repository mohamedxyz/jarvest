package dev.goaltracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.GoalStats
import dev.goaltracker.core.HeatmapDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val HEATMAP_WEEKS = 18L

@Composable
fun StatsScreen(controller: AppController, onOpenGoal: (String) -> Unit) {
    val overview = remember(controller.version, controller.today) {
        controller.read(null) { statsOverview(controller.today) }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val goals = overview?.goals.orEmpty()
        if (overview != null && overview.eligible > 0u) {
            val best = goals.maxOf { it.bestStreak }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Targets met", percent(overview.met, overview.eligible), "${overview.met} of ${overview.eligible} recent", Modifier.weight(1f))
                StatTile("Best streak", best.toString(), "across all goals", Modifier.weight(1f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Activity")
            Heatmap(controller, goalId = null)
        }
        if (goals.isEmpty()) {
            EmptyState("No repeating targets yet", "Streaks and completion rates appear for habits and goals with a daily, weekly, or monthly target.")
        } else {
            SectionTitle("By goal")
            goals.forEach { GoalStatsCard(it, onClick = { onOpenGoal(it.goalId) }) }
        }
    }
}

private fun percent(met: UInt, eligible: UInt): String =
    if (eligible == 0u) "–" else "${(met.toDouble() / eligible.toDouble() * 100).roundToInt()}%"

@Composable
private fun StatTile(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Current and best streaks, as used by both the stats list and a goal's own screen. */
@Composable
fun StreakTiles(stats: GoalStats) {
    val noun = stats.period.noun()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Current streak", stats.currentStreak.toString(), countOf(stats.currentStreak.toInt(), noun), Modifier.weight(1f))
        StatTile("Best streak", stats.bestStreak.toString(), countOf(stats.bestStreak.toInt(), noun), Modifier.weight(1f))
        StatTile("Met", percent(stats.met, stats.eligible), "${stats.met} of ${countOf(stats.eligible.toInt(), noun)}", Modifier.weight(1f))
    }
}

@Composable
private fun GoalStatsCard(stats: GoalStats, onClick: () -> Unit) {
    val noun = stats.period.noun()
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stats.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(percent(stats.met, stats.eligible), style = MaterialTheme.typography.titleMedium)
            }
            CompletionBar(stats.met, stats.eligible)
            Text(
                "${stats.met} of the last ${countOf(stats.eligible.toInt(), noun)} · " +
                    "streak ${stats.currentStreak}, best ${stats.bestStreak}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompletionBar(met: UInt, eligible: UInt) {
    val fraction = if (eligible == 0u) 0f else met.toFloat() / eligible.toFloat()
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(heatColor(0)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier.width(maxWidth * fraction).height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Logs per day for one goal, or for all active goals when [goalId] is null. Each
 * column is a week starting on Monday. Tapping a day reads out its exact count.
 */
@Composable
fun Heatmap(controller: AppController, goalId: String?) {
    val todayDate = remember(controller.today) { LocalDate.parse(controller.today) }
    val start = remember(todayDate) {
        todayDate.minusDays(todayDate.dayOfWeek.value - 1L).minusWeeks(HEATMAP_WEEKS - 1)
    }
    val days = remember(controller.version, controller.today, goalId) {
        controller.read(emptyList()) { heatmap(goalId, start.toString(), controller.today) }
    }
    val byDate = remember(days) { days.associateBy { it.date } }
    var selected by remember(controller.today, goalId) { mutableStateOf<HeatmapDay?>(null) }
    val gap = 2.dp
    val cellShape = RoundedCornerShape(2.dp)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelWidth = 14.dp
            val cell = (maxWidth - labelWidth - gap * HEATMAP_WEEKS.toInt()) / HEATMAP_WEEKS.toInt()
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.width(labelWidth), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.height(16.dp))
                    listOf("M", "", "W", "", "F", "", "").forEach { label ->
                        Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                (0 until HEATMAP_WEEKS).forEach { week ->
                    val monday = start.plusWeeks(week)
                    Column(Modifier.width(cell), verticalArrangement = Arrangement.spacedBy(gap)) {
                        Box(Modifier.height(16.dp)) {
                            if (monday.dayOfMonth <= 7 && week < HEATMAP_WEEKS - 1) {
                                Text(
                                    monday.format(DateTimeFormatter.ofPattern("MMM")),
                                    // A month name is wider than one column, so it may overflow its cell.
                                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = false,
                                )
                            }
                        }
                        (0L until 7L).forEach { offset ->
                            val date = monday.plusDays(offset)
                            val day = byDate[date.toString()]
                            if (day == null) {
                                Box(Modifier.size(cell)) // After today.
                            } else {
                                val isSelected = selected?.date == day.date
                                val label = dayLabel(day)
                                Box(
                                    Modifier.size(cell)
                                        .clip(cellShape)
                                        .background(heatColor(day.count.toInt()))
                                        .then(
                                            if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, cellShape)
                                            else Modifier,
                                        )
                                        .semantics { contentDescription = label }
                                        .clickable { selected = day },
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            Text(
                selected?.let { dayLabel(it) } ?: "Tap a day for details",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Less ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (0..4).forEach { Box(Modifier.size(10.dp).clip(cellShape).background(heatColor(it))) }
            Text(" More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val total = days.sumOf { it.count.toInt() }
        val activeDays = days.count { it.count > 0u }
        Text(
            "${countOf(total, "log")} on ${countOf(activeDays, "day")} over the last ${ChronoUnit.WEEKS.between(start, todayDate) + 1} weeks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun dayLabel(day: HeatmapDay): String =
    "${LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("EEE, MMM d"))}: ${countOf(day.count.toInt(), "log")}"
