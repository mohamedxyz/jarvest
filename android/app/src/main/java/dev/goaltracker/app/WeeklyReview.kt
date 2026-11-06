package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.FocusKind
import dev.goaltracker.core.FocusPick
import dev.goaltracker.core.Period
import dev.goaltracker.core.TaskFilter
import dev.goaltracker.core.TaskSort

/** Offered once at the start of a week. "Not now" costs nothing and it is not asked again that week. */
@Composable
fun ReviewCard(controller: AppController, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val due = remember(controller.version, controller.today) { controller.read(null) { reviewDue(controller.today) } }
    if (due == null) return
    SoftCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🗓️ A new week", style = MaterialTheme.typography.titleMedium)
            Text(
                "Take two minutes to look back, tidy up overdue tasks and pick what matters this week.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text("Start review") }
                TextButton(onClick = { controller.act { dismissReview(controller.today) } }) { Text("Not now") }
            }
        }
    }
}

/** The up to three things picked for this week. A goal or task that was deleted drops off. */
@Composable
fun FocusCard(controller: AppController, modifier: Modifier = Modifier) {
    val items = remember(controller.version, controller.today) { controller.read(emptyList()) { weekFocus(controller.today) } }
    if (items.isEmpty()) return
    SoftCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("⭐ This week's focus", style = MaterialTheme.typography.titleMedium)
            items.forEach {
                Text(
                    (if (it.done) "✓ " else "○ ") + it.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The weekly review: look back, clear up, choose a focus. It only plans, and gives no XP. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeeklyReviewScreen(controller: AppController, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }
    var picks by remember { mutableStateOf(listOf<FocusPick>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator({ (step + 1) / 3f }, Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Step ${step + 1} of 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (step) {
                0 -> {
                    Text("Look back", style = MaterialTheme.typography.headlineSmall)
                    val last = remember(controller.version, controller.today) {
                        controller.read(null) { insights(Period.WEEK, 1u, controller.today) }
                    }
                    if (last == null || last.previousPoints == 0u && last.points == 0u) {
                        Text("Nothing was recorded last week, so there is nothing to compare yet. A fresh start.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("${formatDate(last.from)} to ${formatDate(last.to)}", style = MaterialTheme.typography.labelLarge)
                        Text("${last.points} points over ${last.activeDays} active days · ${last.titanDays} Titan days")
                        val diff = last.points.toInt() - last.previousPoints.toInt()
                        Text(
                            when {
                                last.previousPoints == 0u -> "Nothing before it to compare with."
                                diff > 0 -> "$diff points more than the week before."
                                diff < 0 -> "${-diff} points fewer than the week before."
                                else -> "The same as the week before."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        last.bestDate?.let { Text("Best day: ${formatDate(it)} with ${last.bestPoints} points", style = MaterialTheme.typography.bodyMedium) }
                    }
                    val records = remember(controller.version, controller.today) { controller.read(null) { dayRecords(controller.today) } }
                    records?.let {
                        if (it.blazeStreak > 0u) Text("🔥 Blaze streak: ${countOf(it.blazeStreak.toInt(), "day")}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                1 -> {
                    Text("Clear up", style = MaterialTheme.typography.headlineSmall)
                    val overdue = remember(controller.version, controller.today) {
                        controller.read(emptyList()) { listTasks(TaskFilter.OVERDUE, TaskSort.DUE, controller.today) }
                            .filter { it.milestoneId == null }
                    }
                    if (overdue.isEmpty()) {
                        Text("Nothing is overdue. Nice.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Pick the overdue tasks to deal with, then choose what to do.", style = MaterialTheme.typography.bodyMedium)
                        overdue.forEach { task ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = task.id in selected,
                                    onCheckedChange = { selected = if (it) selected + task.id else selected - task.id },
                                    modifier = Modifier.label("Select ${task.title}"),
                                )
                                Column {
                                    Text(task.title)
                                    task.due?.let { Text(dueLabel(it, controller.today), style = MaterialTheme.typography.bodySmall, color = dueColor(it, controller.today)) }
                                }
                            }
                        }
                        val picked = overdue.filter { it.id in selected }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = picked.isNotEmpty(), onClick = {
                                controller.bulkTasks(picked, bulk(changeDue = true, due = controller.today), "Moved {n} to today"); selected = emptySet()
                            }) { Text("Move to today") }
                            OutlinedButton(enabled = picked.isNotEmpty(), onClick = {
                                val next = bulkDueChoices(controller.today).first { it.label == "Next week" }
                                controller.bulkTasks(picked, bulk(changeDue = true, due = next.date), next.verb); selected = emptySet()
                            }) { Text("Next week") }
                            OutlinedButton(enabled = picked.isNotEmpty(), onClick = {
                                controller.bulkTasks(picked, bulk(archive = true), "Archived"); selected = emptySet()
                            }) { Text("Archive") }
                            OutlinedButton(enabled = picked.isNotEmpty(), onClick = {
                                controller.bulkTasks(picked, bulk(trash = true), "Deleted"); selected = emptySet()
                            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                else -> {
                    Text("Choose a focus", style = MaterialTheme.typography.headlineSmall)
                    Text("Pick up to three goals or tasks that matter most this week. They show on Today.", style = MaterialTheme.typography.bodyMedium)
                    val goals = remember(controller.version) { controller.read(emptyList()) { listGoals(false) }.filter { !it.avoid } }
                    val tasks = remember(controller.version, controller.today) {
                        controller.read(emptyList()) { listTasks(TaskFilter.ALL, TaskSort.PRIORITY, controller.today) }
                            .filter { !it.done && it.milestoneId == null }.take(15)
                    }
                    Text("Goals and habits", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        goals.forEach { goal ->
                            val pick = FocusPick(FocusKind.GOAL, goal.id)
                            FilterChip(
                                selected = pick in picks,
                                enabled = pick in picks || picks.size < 3,
                                onClick = { picks = if (pick in picks) picks - pick else picks + pick },
                                label = { Text(goal.title) },
                            )
                        }
                    }
                    if (tasks.isNotEmpty()) {
                        Text("Tasks", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tasks.forEach { task ->
                                val pick = FocusPick(FocusKind.TASK, task.id)
                                FilterChip(
                                    selected = pick in picks,
                                    enabled = pick in picks || picks.size < 3,
                                    onClick = { picks = if (pick in picks) picks - pick else picks + pick },
                                    label = { Text(task.title) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= 2000) note = it },
                        label = { Text("A note for yourself (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step == 0) {
                TextButton(onClick = { controller.act { dismissReview(controller.today) }; onDone() }) { Text("Not now") }
            } else {
                TextButton(onClick = { step -= 1 }) { Text("Back") }
            }
            if (step < 2) {
                Button(onClick = { step += 1 }) { Text("Next") }
            } else {
                Button(onClick = { if (controller.act { finishReview(controller.today, note, picks) }) onDone() }) { Text("Finish") }
            }
        }
    }
}
