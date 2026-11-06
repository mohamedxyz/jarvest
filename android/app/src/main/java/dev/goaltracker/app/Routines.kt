package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import dev.goaltracker.core.Goal
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.Routine

/** A routine on Today: its name and how far through it you are. Tap to walk through the steps. */
@Composable
fun RoutineCard(routine: Routine, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    SoftCard(modifier.fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔁 ${routine.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (routine.stepsDone == routine.stepsToday) "Done" else "${routine.stepsDone} of ${routine.stepsToday}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                { if (routine.stepsToday == 0u) 0f else routine.stepsDone.toFloat() / routine.stepsToday.toFloat() },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The steps of a routine that are planned today. Each tick is an ordinary tick of that goal, so streaks and
 * treats work exactly as they do on Today. Finishing the routine gives nothing extra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineSheet(controller: AppController, routineId: String, onDismiss: () -> Unit) {
    val routine = remember(controller.version, controller.today) {
        controller.read(emptyList()) { listRoutines(controller.today) }.firstOrNull { it.id == routineId }
    }
    val steps = remember(controller.version, controller.today) {
        controller.read(emptyList()) { routineItems(routineId, controller.today) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🔁 ${routine?.name ?: "Routine"}", style = MaterialTheme.typography.titleLarge)
            val done = steps.count { it.complete }
            if (steps.isEmpty()) {
                Text("Nothing in this routine is planned today.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("$done of ${steps.size} done", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator({ done.toFloat() / steps.size }, Modifier.fillMaxWidth())
            }
            steps.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title.withIcon(item.icon), style = MaterialTheme.typography.titleMedium)
                        progressLine(item)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.kind == GoalKind.NUMERIC) {
                            CounterControl(
                                controller.counters[item.goalId] ?: 0,
                                { controller.counters[item.goalId] = it },
                                { controller.submitCounter(item.goalId) },
                            )
                        }
                    }
                    if (item.kind != GoalKind.NUMERIC) {
                        TickBox(
                            checked = item.loggedToday || (item.kind == GoalKind.YES_NO && item.complete),
                            cue = Cue.HABIT,
                            onCheckedChange = { controller.log { toggleCheckIn(item.goalId, controller.today) } },
                            modifier = Modifier.label("Log ${item.title} for today"),
                        )
                    }
                }
            }
            if (steps.isNotEmpty() && done == steps.size) {
                Text("🎉 Routine complete", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Make, change and delete routines. */
@Composable
fun RoutinesScreen(controller: AppController) {
    val routines = remember(controller.version, controller.today) {
        controller.read(emptyList()) { listRoutines(controller.today) }
    }
    val goals = remember(controller.version) {
        // Quit habits log slips, not successes, so they can't be steps.
        controller.read(emptyList()) { listGoals(false) }.filter { !it.avoid }
    }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var deleting by remember { mutableStateOf<Routine?>(null) }
    val titles = goals.associate { it.id to it.title }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "A routine is a list of goals and habits you do together, such as a morning routine. It shows on Today " +
                "with the steps planned for that day. Ticking a step counts exactly like ticking the goal, and finishing " +
                "the routine gives nothing extra.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { creating = true }, enabled = goals.isNotEmpty()) { Text("New routine") }
        if (goals.isEmpty()) {
            Text("Make a goal or habit first, then put it in a routine.", style = MaterialTheme.typography.bodyMedium)
        }
        routines.forEach { routine ->
            SoftCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(routine.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            routine.goalIds.joinToString(", ") { titles[it] ?: "…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { editing = routine }) { Text("Edit") }
                    TextButton(onClick = { deleting = routine }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (creating || editing != null) {
        RoutineEditor(controller, goals, editing, onDismiss = { creating = false; editing = null })
    }
    deleting?.let { routine ->
        ConfirmDialog(
            title = "Delete this routine?",
            body = "\"${routine.name}\" is removed. Its goals and everything you logged stay as they are.",
            confirm = "Delete",
            onConfirm = { deleting = null; controller.act { deleteRoutine(routine.id) } },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(controller: AppController, goals: List<Goal>, existing: Routine?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var steps by remember { mutableStateOf(existing?.goalIds ?: emptyList()) }
    val titles = goals.associate { it.id to it.title }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New routine" else "Edit routine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, such as Morning") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Steps, in order", style = MaterialTheme.typography.labelLarge)
                if (steps.isEmpty()) {
                    Text("Add at least one goal below.", style = MaterialTheme.typography.bodySmall)
                }
                steps.forEachIndexed { index, id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${titles[id] ?: "…"}", Modifier.weight(1f))
                        IconButton(enabled = index > 0, onClick = {
                            steps = steps.toMutableList().also { it.add(index - 1, it.removeAt(index)) }
                        }) { Icon(Icons.Filled.KeyboardArrowUp, "Move ${titles[id]} up") }
                        IconButton(enabled = index < steps.lastIndex, onClick = {
                            steps = steps.toMutableList().also { it.add(index + 1, it.removeAt(index)) }
                        }) { Icon(Icons.Filled.KeyboardArrowDown, "Move ${titles[id]} down") }
                        IconButton(onClick = { steps = steps - id }) { Icon(Icons.Filled.Close, "Remove ${titles[id]}") }
                    }
                }
                val left = goals.filter { it.id !in steps }
                if (left.isNotEmpty() && steps.size < 12) {
                    Text("Add a goal", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        left.forEach { goal ->
                            AssistChip(onClick = { steps = steps + goal.id }, label = { Text(goal.title) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val saved = controller.act {
                    if (existing == null) createRoutine(name, steps) else updateRoutine(existing.id, name, steps)
                }
                if (saved) onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
