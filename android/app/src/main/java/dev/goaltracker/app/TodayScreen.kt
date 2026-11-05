package dev.goaltracker.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.TodayItem

@Composable
fun TodayScreen(
    controller: AppController,
    onOpenGoal: (String) -> Unit,
    onEditGoal: (String) -> Unit,
    onEditTask: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRewards: () -> Unit,
    onAddGoal: () -> Unit,
    onAddTask: () -> Unit,
) {
    val items = remember(controller.version, controller.today) {
        controller.read(emptyList()) { today(controller.today) }
    }
    val dueTasks = remember(controller.version, controller.today) {
        controller.read(emptyList()) { dueTasks(controller.today) }
    }
    val hasGoals = remember(controller.version) {
        controller.read(emptyList()) { listGoals(false) }.isNotEmpty()
    }
    var logging by remember { mutableStateOf<TodayItem?>(null) }
    var openedGoal by remember { mutableStateOf<TodayItem?>(null) }
    var openedTask by remember { mutableStateOf<String?>(null) }

    if (items.isEmpty() && dueTasks.isEmpty()) {
        if (hasGoals) {
            EmptyState("You're all caught up", "No goals or tasks are due today.")
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    "Nothing here yet",
                    "A goal is something you track over time, such as a habit or a number to reach. A task is a one-off to-do.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddGoal) { Text("Add a goal") }
                    OutlinedButton(onClick = onAddTask) { Text("Add a task") }
                }
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // One line that answers "how am I doing today?" before the lists start.
                val goalsDone = items.count { it.complete }
                Text(
                    buildList {
                        if (items.isNotEmpty()) add("$goalsDone of ${items.size} goals done")
                        if (dueTasks.isNotEmpty()) add(countOf(dueTasks.size, "task") + " due")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item { EmptyJarBanner(controller, onAddTreat = onOpenRewards) }
            item {
                TipCard(controller, "today_cards", "Tap a card to see its details. Tick the box to log it for today.")
            }
            if (dueTasks.isNotEmpty()) {
                item { SectionTitle("Tasks due") }
                // The three most urgent. The rest are one tap away, so goals are not buried.
                items(dueTasks.take(3), key = { "task-${it.id}" }) { task ->
                    Card(Modifier.fillMaxWidth().clickable { openedTask = task.id }) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                task.due?.let {
                                    Text(
                                        dueLabel(it, controller.today),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (task.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Checkbox(checked = false, onCheckedChange = { controller.toggleTaskWithUndo(task.id, task.title) })
                        }
                    }
                }
                item {
                    TextButton(onClick = onOpenTasks) {
                        Text(if (dueTasks.size > 3) "${dueTasks.size - 3} more due. See all tasks" else "See all tasks")
                    }
                }
            }
            if (items.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Goals")
                        if (items.any { it.kind == GoalKind.NUMERIC }) {
                            InfoHint("Counter", "On a number goal, use - and + to count as you go. Nothing is saved until you press Submit, which logs the count as one entry. Exact lets you type an amount, such as 2.5.")
                        }
                    }
                }
            }
            items(items, key = { it.goalId }) { item ->
                TodayCard(
                    item,
                    onOpen = { openedGoal = item },
                    onCheck = { controller.log { toggleCheckIn(item.goalId, controller.today) } },
                    onLog = { logging = item },
                    count = controller.counters[item.goalId] ?: 0,
                    onCount = { controller.counters[item.goalId] = it },
                    onSubmit = { controller.submitCounter(item.goalId) },
                )
            }
        }
    }
    logging?.let { item ->
        LogAmountDialog(
            title = item.title,
            unit = item.unit,
            onLog = { amount ->
                if (controller.log { logAmount(item.goalId, amount, controller.today) }) logging = null
            },
            onDismiss = { logging = null },
        )
    }
    openedTask?.let { id ->
        TaskSheet(controller, id, onEdit = { openedTask = null; onEditTask(id) }, onDismiss = { openedTask = null })
    }
    openedGoal?.let { item ->
        InfoSheet(item.title, onDismiss = { openedGoal = null }) {
            progressLine(item)?.let { InfoRow("Progress", it) }
            item.period?.let { period ->
                InfoRow("Streak", if (item.currentStreak > 0u) "${item.currentStreak} ${period.noun()}s in a row" else "None yet")
            }
            item.deadline?.let { InfoRow("Deadline", formatDate(it)) }
            item.nextMilestone?.let {
                InfoRow("Milestones", "${item.milestonesDone} of ${item.milestonesTotal} done")
                InfoRow("Next", it)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { val id = item.goalId; openedGoal = null; onOpenGoal(id) }) { Text("Full details") }
                OutlinedButton(onClick = { val id = item.goalId; openedGoal = null; onEditGoal(id) }) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun TodayCard(
    item: TodayItem,
    onOpen: () -> Unit,
    onCheck: () -> Unit,
    onLog: () -> Unit,
    count: Int,
    onCount: (Int) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                progressLine(item)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                progressFraction(item)?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) }
                if (item.kind == GoalKind.NUMERIC) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CounterControl(count, onCount, onSubmit)
                        TextButton(onClick = onLog) { Text("Exact") }
                    }
                }
                val deadline = item.deadline
                if (item.overdue && deadline != null) {
                    OverdueLabel(deadline)
                }
            }
            if (item.kind != GoalKind.NUMERIC) {
                Checkbox(checked = item.loggedToday || (item.kind == GoalKind.YES_NO && item.complete), onCheckedChange = { onCheck() })
            }
        }
    }
}

internal fun progressLine(item: TodayItem): String? {
    val period = item.period
    val periodTarget = item.periodTarget
    val totalTarget = item.totalTarget
    return when {
        period != null && periodTarget != null ->
            "${formatAmount(item.periodProgress)} of ${withUnit(periodTarget, item.unit)} ${period.current()}"
        totalTarget != null -> "${formatAmount(item.totalProgress)} of ${withUnit(totalTarget, item.unit)}"
        item.kind == GoalKind.NUMERIC -> "${withUnit(item.totalProgress, item.unit)} so far"
        else -> null
    }
}

private fun progressFraction(item: TodayItem): Float? {
    val periodTarget = item.periodTarget
    val totalTarget = item.totalTarget
    return when {
        periodTarget != null -> (item.periodProgress / periodTarget).toFloat().coerceIn(0f, 1f)
        totalTarget != null -> (item.totalProgress / totalTarget).toFloat().coerceIn(0f, 1f)
        else -> null
    }
}
