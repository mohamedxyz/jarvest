package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.Entry
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.Period
import java.time.LocalDate

@Composable
fun GoalDetailScreen(
    controller: AppController,
    goalId: String,
    onEdit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val goal = remember(controller.version, goalId) { controller.read(null) { getGoal(goalId) } } ?: return
    val isHabit = goal.kind == GoalKind.HABIT
    val noun = goal.noun()
    val stats = remember(controller.version, controller.today, goalId) {
        controller.read(null) { goalStats(goalId, controller.today) }
    }
    val milestones = remember(controller.version, goalId) { controller.read(emptyList()) { listMilestones(goalId) } }
    val entries = remember(controller.version, goalId) { controller.read(emptyList()) { listEntries(goalId, 20u) } }
    var milestoneToDelete by remember { mutableStateOf<dev.goaltracker.core.Milestone?>(null) }

    val chartPeriod = goal.period ?: Period.WEEK
    val series = remember(controller.version, controller.today, goalId) {
        controller.read(emptyList()) { periodSeries(goalId, chartPeriod, 12u, controller.today) }
    }
    val runs = remember(controller.version, controller.today, goalId) {
        controller.read(emptyList()) { streakRuns(goalId, controller.today) }
    }
    val skipped = remember(controller.version, goalId) { controller.read(emptyList()) { skippedDays(goalId) } }
    val upcomingSkips = skipped.count { it >= controller.today }

    var pickingPastDay by remember { mutableStateOf(false) }
    var pastDay by remember { mutableStateOf<String?>(null) }
    var pickingPause by remember { mutableStateOf(false) }
    var newMilestone by remember { mutableStateOf("") }
    var logging by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<Entry?>(null) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(goal.title.withIcon(goal.icon), style = MaterialTheme.typography.headlineSmall)
            // Pick or clear an emoji shown on this goal's cards.
            IconPickerRow(goal.icon) { icon -> controller.act { setGoalIcon(goalId, icon) } }
            Text(describe(goal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            goal.deadline?.let { deadline ->
                if (!goal.done && deadline < controller.today) {
                    OverdueLabel(deadline)
                } else {
                    Text("Due ${formatDate(deadline)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (goal.notes.isNotBlank()) Text(goal.notes, style = MaterialTheme.typography.bodyMedium)
            if (goal.archived) Text(stringResource(R.string.ui_archived), style = MaterialTheme.typography.labelLarge)
        }

        if (!goal.archived) {
            when (goal.kind) {
                GoalKind.NUMERIC -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CounterControl(
                        controller.counters[goalId] ?: 0,
                        { controller.counters[goalId] = it },
                        { controller.submitCounter(goalId) },
                    )
                    TextButton(onClick = { logging = true }) { Text(stringResource(R.string.ui_exact_amount)) }
                }
                GoalKind.YES_NO -> Button(
                    onClick = { controller.log { toggleCheckIn(goalId, controller.today) } },
                    Modifier.fillMaxWidth(),
                ) { Text(if (goal.done) "Mark as not done" else "Mark as done") }
                GoalKind.HABIT -> {
                    val checked = entries.any { it.localDate == controller.today }
                    Button(
                        onClick = { controller.log { toggleCheckIn(goalId, controller.today) } },
                        Modifier.fillMaxWidth(),
                    ) { Text(if (goal.avoid) (if (checked) "Undo today's slip" else "I slipped today") else if (checked) "Undo today's check-in" else "Check in today") }
                }
            }
        }

        if (!goal.archived) {
            OutlinedButton(onClick = { pickingPastDay = true }, Modifier.fillMaxWidth()) {
                Text(if (goal.avoid) "Log a slip on a past day" else "Log a past day")
            }
            if (goal.period != null) {
                val skippedToday = controller.today in skipped
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            controller.act {
                                if (skippedToday) unpauseGoal(goalId, controller.today, controller.today)
                                else skipDay(goalId, controller.today)
                            }
                        },
                        Modifier.weight(1f),
                    ) { Text(if (skippedToday) "Unskip today" else "Skip today") }
                    OutlinedButton(onClick = { pickingPause = true }, Modifier.weight(1f)) { Text(stringResource(R.string.ui_pause_days)) }
                    InfoHint(
                        "Skip and pause",
                        "Skip today excuses today only. Pause days excuses a range of days, such as a holiday. " +
                            "A skipped or paused period never breaks your streak, and it is left out of your completion rate.",
                    )
                }
                if (upcomingSkips > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${countOf(upcomingSkips, "skipped day")} from today on. Skipped periods keep your streak.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            val end = LocalDate.parse(controller.today).plusDays(400).toString()
                            controller.act { unpauseGoal(goalId, controller.today, end) }
                        }) { Text(stringResource(R.string.ui_resume)) }
                    }
                }
            }
        }

        stats?.let { StreakTiles(it) }

        if (showsCalendar(goal)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Calendar")
                GoalCalendar(controller, goal, skipped)
            }
        }

        if (goal.kind != GoalKind.YES_NO && !goal.avoid) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Progress")
                PeriodChart(series, chartPeriod, goal.unit)
            }
        }

        if (runs.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle("Streak history")
                runs.reversed().forEach { run ->
                    val noun = chartPeriod.noun()
                    Text(
                        "${countOf(run.length.toInt(), noun)} · ${formatDate(run.start)} to ${formatDate(run.end)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (goal.kind != GoalKind.YES_NO) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(if (goal.avoid) "Slips" else "Activity")
                Heatmap(controller, goalId)
            }
        }

        // A goal is broken into milestones. A habit has no milestones of its own, so the section
        // only shows for goals (or for a habit that already has some from an older version).
        if (!isHabit || milestones.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Milestones")
                InfoHint(
                    "Milestones",
                    "Break the goal into steps. Ticking a milestone counts for the goal and can win a treat. " +
                        "Each milestone also shows up in your Tasks tab, and ticking either one ticks both.",
                )
            }
            if (milestones.isNotEmpty()) {
                Text(
                    "${milestones.count { it.doneDate != null }} of ${milestones.size} done",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            milestones.forEachIndexed { index, milestone ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TickBox(
                        checked = milestone.doneDate != null,
                        enabled = !goal.archived,
                        onCheckedChange = { controller.log { toggleMilestone(milestone.id, controller.today) } },
                        modifier = Modifier.label(if (milestone.doneDate != null) "Untick milestone ${milestone.title}" else "Tick milestone ${milestone.title}"),
                    )
                    Text("\uD83C\uDFC1 ${milestone.title}", Modifier.weight(1f))
                    IconButton(enabled = index > 0, onClick = { controller.act { moveMilestone(milestone.id, true) } }) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Move ${milestone.title} up")
                    }
                    IconButton(enabled = index < milestones.lastIndex, onClick = { controller.act { moveMilestone(milestone.id, false) } }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Move ${milestone.title} down")
                    }
                    IconButton(onClick = { milestoneToDelete = milestone }) {
                        Icon(Icons.Filled.Close, "Delete ${milestone.title}")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val addMilestone = {
                    if (newMilestone.isNotBlank() && controller.act { addMilestone(goalId, newMilestone, controller.today) }) newMilestone = ""
                }
                OutlinedTextField(
                    newMilestone, { newMilestone = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ui_new_milestone)) }, singleLine = true,
                    keyboardOptions = doneKey, keyboardActions = onDone(addMilestone),
                )
                TextButton(enabled = newMilestone.isNotBlank(), onClick = { addMilestone() }) { Text(stringResource(R.string.ui_add)) }
            }
        }

        if (entries.isNotEmpty()) {
            Column {
                SectionTitle("Recent logs")
                entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val amount = if (goal.kind == GoalKind.NUMERIC) " · ${withUnit(entry.amount, goal.unit)}" else ""
                        Text("${formatDate(entry.localDate)}$amount", Modifier.weight(1f))
                        IconButton(onClick = { entryToDelete = entry }) {
                            Icon(Icons.Filled.Close, "Delete log from ${formatDate(entry.localDate)}")
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEdit(goalId) }) { Text(stringResource(R.string.ui_edit)) }
            OutlinedButton(onClick = { controller.act { setArchived(goalId, !goal.archived) } }) {
                Text(if (goal.archived) "Restore" else "Archive")
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (pickingPastDay) {
        DayPickerDialog(
            min = goal.createdDate,
            max = controller.today,
            onPick = { day ->
                pickingPastDay = false
                if (goal.kind == GoalKind.NUMERIC) {
                    pastDay = day
                } else if (controller.log { toggleCheckInOn(goalId, day, controller.today) }) {
                    controller.message = "Updated ${formatDate(day)}"
                }
            },
            onDismiss = { pickingPastDay = false },
        )
    }
    pastDay?.let { day ->
        LogAmountDialog(
            title = "${goal.title} on ${formatDate(day)}",
            unit = goal.unit,
            onLog = { amount ->
                if (controller.log { logAmountOn(goalId, amount, day, controller.today) }) pastDay = null
            },
            onDismiss = { pastDay = null },
        )
    }
    if (pickingPause) {
        RangePickerDialog(
            min = goal.createdDate,
            max = LocalDate.parse(controller.today).plusDays(365).toString(),
            onPick = { from, to ->
                pickingPause = false
                controller.act { pauseGoal(goalId, from, to) }
            },
            onDismiss = { pickingPause = false },
        )
    }
    if (logging) {
        LogAmountDialog(
            title = goal.title,
            unit = goal.unit,
            onLog = { amount ->
                if (controller.log { logAmount(goalId, amount, controller.today) }) logging = false
            },
            onDismiss = { logging = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Move this $noun to the trash?",
            body = "\"${goal.title}\" leaves your lists, stats and reminders. Its history and rewards are kept for 30 days, " +
                "and you can restore it from the trash on the Tasks tab.",
            confirm = "Move to trash",
            onConfirm = {
                confirmDelete = false
                if (controller.act { trashGoal(goalId, controller.today) }) {
                    controller.undo = UndoOffer("\"${goal.title}\" moved to the trash.") {
                        controller.act { restoreGoal(goalId) }
                    }
                }
                onClose()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    milestoneToDelete?.let { milestone ->
        ConfirmDialog(
            title = "Delete this milestone?",
            body = "\"${milestone.title}\" will be removed from this goal and from your Tasks tab. If you already ticked it, the treat it earned is removed too.",
            confirm = "Delete",
            onConfirm = {
                milestoneToDelete = null
                controller.act { deleteMilestone(milestone.id) }
            },
            onDismiss = { milestoneToDelete = null },
        )
    }
    entryToDelete?.let { entry ->
        ConfirmDialog(
            title = "Delete this log?",
            body = "If it completed a target, any treat that target earned is removed too.",
            confirm = "Delete",
            onConfirm = {
                entryToDelete = null
                controller.act { deleteEntry(entry.id, controller.today) }
            },
            onDismiss = { entryToDelete = null },
        )
    }
}
