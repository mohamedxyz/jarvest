package dev.goaltracker.app

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

@Composable
fun GoalDetailScreen(controller: AppController, goalId: String, onEdit: (String) -> Unit, onClose: () -> Unit) {
    val goal = remember(controller.version, goalId) { controller.read(null) { getGoal(goalId) } } ?: return
    val stats = remember(controller.version, controller.today, goalId) {
        controller.read(null) { goalStats(goalId, controller.today) }
    }
    val milestones = remember(controller.version, goalId) { controller.read(emptyList()) { listMilestones(goalId) } }
    val entries = remember(controller.version, goalId) { controller.read(emptyList()) { listEntries(goalId, 20u) } }

    var newMilestone by remember { mutableStateOf("") }
    var logging by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<Entry?>(null) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(goal.title, style = MaterialTheme.typography.headlineSmall)
            Text(describe(goal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            goal.deadline?.let { deadline ->
                if (!goal.done && deadline < controller.today) {
                    OverdueLabel(deadline)
                } else {
                    Text("Due ${formatDate(deadline)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (goal.notes.isNotBlank()) Text(goal.notes, style = MaterialTheme.typography.bodyMedium)
            if (goal.archived) Text("Archived", style = MaterialTheme.typography.labelLarge)
        }

        if (!goal.archived) {
            when (goal.kind) {
                GoalKind.NUMERIC -> Button(onClick = { logging = true }, Modifier.fillMaxWidth()) { Text("Log amount") }
                GoalKind.YES_NO -> Button(
                    onClick = { controller.log { toggleCheckIn(goalId, controller.today) } },
                    Modifier.fillMaxWidth(),
                ) { Text(if (goal.done) "Mark as not done" else "Mark as done") }
                GoalKind.HABIT -> {
                    val checked = entries.any { it.localDate == controller.today }
                    Button(
                        onClick = { controller.log { toggleCheckIn(goalId, controller.today) } },
                        Modifier.fillMaxWidth(),
                    ) { Text(if (checked) "Undo today's check-in" else "Check in today") }
                }
            }
        }

        stats?.let { StreakTiles(it) }

        if (goal.kind != GoalKind.YES_NO) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Activity")
                Heatmap(controller, goalId)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Milestones")
            milestones.forEachIndexed { index, milestone ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = milestone.doneDate != null,
                        enabled = !goal.archived,
                        onCheckedChange = { controller.log { toggleMilestone(milestone.id, controller.today) } },
                    )
                    Text(milestone.title, Modifier.weight(1f))
                    IconButton(enabled = index > 0, onClick = { controller.act { moveMilestone(milestone.id, true) } }) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Move ${milestone.title} up")
                    }
                    IconButton(enabled = index < milestones.lastIndex, onClick = { controller.act { moveMilestone(milestone.id, false) } }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Move ${milestone.title} down")
                    }
                    IconButton(onClick = { controller.act { deleteMilestone(milestone.id) } }) {
                        Icon(Icons.Filled.Close, "Delete ${milestone.title}")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newMilestone, { newMilestone = it }, Modifier.weight(1f), label = { Text("New milestone") }, singleLine = true)
                TextButton(enabled = newMilestone.isNotBlank(), onClick = {
                    if (controller.act { addMilestone(goalId, newMilestone) }) newMilestone = ""
                }) { Text("Add") }
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
            OutlinedButton(onClick = { onEdit(goalId) }) { Text("Edit") }
            OutlinedButton(onClick = { controller.act { setArchived(goalId, !goal.archived) } }) {
                Text(if (goal.archived) "Restore" else "Archive")
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
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
            title = "Delete this goal?",
            body = "\"${goal.title}\" and all of its history and rewards will be permanently deleted. To keep the history, archive it instead.",
            confirm = "Delete",
            onConfirm = {
                confirmDelete = false
                onClose()
                controller.act { deleteGoal(goalId) }
            },
            onDismiss = { confirmDelete = false },
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
