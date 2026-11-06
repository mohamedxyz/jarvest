package dev.goaltracker.app

import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.GoalStats
import dev.goaltracker.core.HeatmapDay
import dev.goaltracker.core.Period
import dev.goaltracker.core.Recap
import dev.goaltracker.core.WeekdayStat
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
        val momentum = remember(controller.version, controller.today) {
            controller.read(null) { momentum(controller.today) }
        }
        if (overview != null && overview.eligible > 0u) {
            momentum?.let { MomentumBanner(it) }
            // Streak lengths count different units, so the best one is named with its unit and goal.
            val best = goals.maxByOrNull { it.bestStreak }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Targets met", percent(overview.met, overview.eligible), "${overview.met} of ${overview.eligible} recent periods", Modifier.weight(1f))
                if (best != null) {
                    StatTile("Best streak", countOf(best.bestStreak.toInt(), best.period.noun()), best.title, Modifier.weight(1f))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Activity")
            Heatmap(controller, goalId = null)
        }
        RecapCard(controller)
        WeekdayCard(controller)
        if (goals.map { it.category }.distinct().size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("By category")
                goals.groupBy { it.category ?: "No category" }.entries
                    .map { (name, group) -> Triple(name, group.sumOf { it.met.toInt() }.toUInt(), group.sumOf { it.eligible.toInt() }.toUInt()) }
                    .sortedByDescending { (_, met, eligible) -> if (eligible == 0u) -1.0 else met.toDouble() / eligible.toDouble() }
                    .forEach { (name, met, eligible) ->
                        SoftCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Text(percent(met, eligible), style = MaterialTheme.typography.titleMedium)
                                }
                                CompletionBar(met, eligible)
                            }
                        }
                    }
            }
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
    SoftCard(modifier) {
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
    SoftCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stats.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(percent(stats.met, stats.eligible), style = MaterialTheme.typography.titleMedium)
            }
            CompletionBar(stats.met, stats.eligible)
            TrendChip(goalTrend(stats))
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
    val target = if (eligible == 0u) 0f else met.toFloat() / eligible.toFloat()
    val motion = LocalMotion.current
    var started by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { started = true }
    val fraction by androidx.compose.animation.core.animateFloatAsState(
        if (started) target else 0f, motionTween(motion, 700), label = "completion",
    )
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
    val weekStart = remember(controller.version) { controller.weekStart() }
    val start = remember(todayDate, weekStart) {
        // The first column begins on the week-start day chosen in Settings.
        val since = (todayDate.dayOfWeek.value - weekStart + 7) % 7
        todayDate.minusDays(since.toLong()).minusWeeks(HEATMAP_WEEKS - 1)
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
                    // Row labels follow the week start: the 1st, 3rd and 5th rows are named.
                    (0 until 7).map { row ->
                        if (row % 2 == 0 && row < 5) "MTWTFSS"[(weekStart - 1 + row) % 7].toString() else ""
                    }.forEach { label ->
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

@Composable
private fun RecapCard(controller: AppController) {
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    var back by rememberSaveable { mutableIntStateOf(0) }
    val recap = remember(controller.version, controller.today, period, back) {
        controller.read(null) { recap(period, back.toUInt(), controller.today) }
    }
    val before = remember(controller.version, controller.today, period, back) {
        controller.read(null) { recap(period, (back + 1).toUInt(), controller.today) }
    }
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Summary", Modifier.weight(1f))
                FilterChip(period == Period.WEEK, { period = Period.WEEK; back = 0 }, label = { Text("Week") })
                FilterChip(period == Period.MONTH, { period = Period.MONTH; back = 0 }, label = { Text("Month") })
            }
            if (recap != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back++ }) { Icon(Icons.Filled.ChevronLeft, "Earlier ${period.noun()}") }
                    Text(rangeLabel(recap), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(enabled = back > 0, onClick = { back-- }) { Icon(Icons.Filled.ChevronRight, "Later ${period.noun()}") }
                }
                Text(
                    "${countOf(recap.entries.toInt(), "log")} on ${countOf(recap.activeDays.toInt(), "day")} " +
                        "across ${countOf(recap.goalsLogged.toInt(), "goal")}. " +
                        "${countOf(recap.targetsMet.toInt(), "target")} met.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Tasks are reported on their own: they never count in goal stats.
                Text(
                    "${countOf(recap.tasksDone.toInt(), "task")} done." +
                        if (recap.tasksMissed > 0u) " ${countOf(recap.tasksMissed.toInt(), "task")} missed." else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (recap.missedTasks.isNotEmpty()) {
                    SectionTitle("Missed tasks")
                    recap.missedTasks.forEach {
                        Text(
                            "${it.title} (due ${formatDate(it.due)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val more = recap.tasksMissed.toInt() - recap.missedTasks.size
                    if (more > 0) {
                        Text("and $more more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (before != null) {
                    val previous = if (back == 0) "last ${period.noun()}" else "the ${period.noun()} before"
                    DeltaRow("Logs", recap.entries.toInt(), before.entries.toInt(), previous)
                    DeltaRow("Active days", recap.activeDays.toInt(), before.activeDays.toInt(), previous)
                    DeltaRow("Targets met", recap.targetsMet.toInt(), before.targetsMet.toInt(), previous)
                    if (back == 0) {
                        Text(
                            "This ${period.noun()} isn't over yet, so these numbers can still rise.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun rangeLabel(recap: Recap): String {
    val start = LocalDate.parse(recap.start)
    val end = LocalDate.parse(recap.end)
    val short = DateTimeFormatter.ofPattern("MMM d")
    return if (start.month == end.month && start.dayOfMonth == 1) start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    else "${start.format(short)} to ${end.format(short)}"
}

@Composable
private fun WeekdayCard(controller: AppController) {
    val stats = remember(controller.version, controller.today) {
        controller.read(emptyList()) { weekdayStats(controller.today) }
    }
    if (stats.all { it.eligible == 0u }) return
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    fun rate(s: WeekdayStat) = s.met.toDouble() / s.eligible.toDouble()
    val ranked = stats.filter { it.eligible >= 2u }.sortedBy { rate(it) }
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Best and worst days")
            val weekStart = controller.weekStart()
            stats.sortedBy { (it.weekday.toInt() - (weekStart - 1) + 7) % 7 }.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(names[s.weekday.toInt()], Modifier.width(36.dp), style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.weight(1f)) { CompletionBar(s.met, s.eligible) }
                    Text(percent(s.met, s.eligible), Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ranked.size >= 2 && rate(ranked.first()) < rate(ranked.last())) {
                Text(
                    "Strongest: ${names[ranked.last().weekday.toInt()]}. Weakest: ${names[ranked.first().weekday.toInt()]}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Daily goals only. Days off and skipped days are left out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
