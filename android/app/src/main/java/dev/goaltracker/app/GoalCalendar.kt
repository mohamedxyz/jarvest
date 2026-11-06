package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.Goal
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.Period
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** What one calendar day shows. Words are always given too, so color is never the only signal. */
private enum class DayState(val word: String) {
    DONE("done"),
    SLIPPED("slipped"),
    CLEAN("clean"),
    MISSED("missed"),
    SKIPPED("skipped"),
    OPEN("nothing logged"),
    OFF("not available"),
}

/**
 * A month grid for one goal. Tapping a day up to today toggles its check-in, using the
 * same call as "Log a past day". Weeks start on the day chosen in Settings.
 * Habits and quit habits only: other kinds log amounts, not ticks.
 */
@Composable
fun GoalCalendar(controller: AppController, goal: Goal, skipped: List<String>) {
    val today = remember(controller.today) { LocalDate.parse(controller.today) }
    val created = remember(goal.createdDate) { LocalDate.parse(goal.createdDate) }
    var month by remember(goal.id) { mutableStateOf(YearMonth.from(today)) }
    val weekStart = remember(controller.version) { controller.weekStart() }
    // Index 0 of the grid is the chosen first day of the week (1 = Monday ... 7 = Sunday).
    val leading = ((month.atDay(1).dayOfWeek.value - weekStart) + 7) % 7
    val logged = remember(controller.version, goal.id, month) {
        controller.read(emptyList()) { heatmap(goal.id, month.atDay(1).toString(), month.atEndOfMonth().toString()) }
            .filter { it.count > 0u }.map { it.date }.toSet()
    }
    val skippedSet = remember(skipped) { skipped.toSet() }
    val dayNames = listOf("M", "T", "W", "T", "F", "S", "S")

    fun stateOf(date: LocalDate): DayState {
        if (date > today || date < created) return DayState.OFF
        val key = date.toString()
        val hasEntry = key in logged
        return when {
            goal.avoid -> if (hasEntry) DayState.SLIPPED else if (key in skippedSet) DayState.SKIPPED else DayState.CLEAN
            hasEntry -> DayState.DONE
            key in skippedSet -> DayState.SKIPPED
            // Only a daily goal can miss a single day. Weekly and monthly ones count per period.
            goal.period == Period.DAY && date < today && weekdayDue(goal, date) -> DayState.MISSED
            else -> DayState.OPEN
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(enabled = month < YearMonth.from(today), onClick = { month = month.plusMonths(1) }) {
                Icon(Icons.Filled.KeyboardArrowRight, "Next month")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { column ->
                // Day names start from the chosen first day of the week.
                val name = dayNames[(weekStart - 1 + column) % 7]
                Text(
                    name,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val rows = (leading + month.lengthOfMonth() + 6) / 7
        (0 until rows).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                (0 until 7).forEach { column ->
                    val dayNumber = row * 7 + column - leading + 1
                    if (dayNumber < 1 || dayNumber > month.lengthOfMonth()) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(dayNumber)
                        CalendarCell(
                            number = dayNumber,
                            state = stateOf(date),
                            isToday = date == today,
                            isCreation = date == created,
                            label = formatDate(date.toString()),
                            modifier = Modifier.weight(1f),
                            onTap = {
                                controller.log { toggleCheckInOn(goal.id, date.toString(), controller.today) }
                            },
                        )
                    }
                }
            }
        }
        Text(
            if (goal.avoid) "Tap a day to add or remove a slip. A star marks the day you started."
            else "Tap a day to check it in or undo it. A star marks the day you started.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A daily habit's days off are never counted as missed. */
private fun weekdayDue(goal: Goal, date: LocalDate): Boolean {
    val mask = goal.weekdays?.toInt() ?: return true
    return mask and (1 shl (date.dayOfWeek.value - 1)) != 0
}

@Composable
private fun CalendarCell(
    number: Int,
    state: DayState,
    isToday: Boolean,
    isCreation: Boolean,
    label: String,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (fill, text) = when (state) {
        DayState.DONE, DayState.CLEAN -> scheme.primaryContainer to scheme.onPrimaryContainer
        DayState.SLIPPED, DayState.MISSED -> scheme.errorContainer to scheme.onErrorContainer
        DayState.SKIPPED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        DayState.OPEN -> scheme.surfaceVariant to scheme.onSurfaceVariant
        DayState.OFF -> scheme.surface to scheme.outline
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .background(fill, shape)
            .then(if (isToday) Modifier.border(2.dp, scheme.onSurface, shape) else Modifier)
            .semantics {
                contentDescription = "$label, ${state.word}" + if (isCreation) ", the day you started" else ""
            }
            .then(if (state != DayState.OFF) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(number.toString(), color = text, style = MaterialTheme.typography.labelMedium)
        // Words for the two states that colour alone could blur: a tick or a cross.
        val mark = when (state) {
            DayState.DONE, DayState.CLEAN -> "✓"
            DayState.SLIPPED, DayState.MISSED -> "✕"
            else -> null
        }
        mark?.let {
            Text(it, Modifier.align(Alignment.BottomEnd).padding(end = 3.dp), color = text, style = MaterialTheme.typography.labelSmall)
        }
        if (isCreation) {
            Text(stringResource(R.string.ui_text), Modifier.align(Alignment.TopStart).padding(start = 3.dp), color = text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** True for goals that show the calendar: habits, including quit habits. */
fun showsCalendar(goal: Goal) = goal.kind == GoalKind.HABIT
