package dev.goaltracker.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.NewTask
import dev.goaltracker.core.Priority
import dev.goaltracker.core.Repeat
import dev.goaltracker.core.TaskEdit

/** Create a task ([taskId] is null) or edit one. [presetGoalId] links a new task to a goal. */
@Composable
fun TaskEditorScreen(controller: AppController, taskId: String?, presetGoalId: String?, onDone: () -> Unit) {
    val existing = remember(taskId) { taskId?.let { id -> controller.read(null) { getTask(id, controller.today) } } }
    val goals = remember(controller.version) { controller.read(emptyList()) { listGoals(false) } }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var due by rememberSaveable { mutableStateOf(if (taskId == null) controller.defaultDueDate() else existing?.due) }
    // New tasks start with the defaults from Settings. Existing ones keep what they have.
    var priority by rememberSaveable { mutableStateOf(existing?.priority ?: controller.defaultPriority) }
    var tag by rememberSaveable { mutableStateOf(existing?.tag ?: "") }
    var repeat by rememberSaveable { mutableStateOf(existing?.repeat) }
    var goalId by rememberSaveable { mutableStateOf(existing?.goalId ?: presetGoalId) }
    var pickDate by remember { mutableStateOf(false) }
    var newItem by rememberSaveable { mutableStateOf("") }
    // New tasks collect checklist lines here. Existing tasks change their checklist right away.
    val draftItems = remember { mutableStateListOf<String>() }
    val savedItems = remember(taskId, controller.version) {
        taskId?.let { id -> controller.read(emptyList()) { listTaskItems(id) } } ?: emptyList()
    }

    fun addItem() {
        val text = newItem.trim()
        if (text.isEmpty()) return
        if (taskId == null) draftItems.add(text) else controller.act { addTaskItem(taskId, text) }
        newItem = ""
    }

    // Removing a step can be undone by adding it back.
    fun removeSaved(id: String, text: String) {
        if (controller.act { deleteTaskItem(id) } && taskId != null) {
            controller.undo = UndoOffer("Step removed.") { controller.act { addTaskItem(taskId, text) } }
        }
    }

    fun save() {
        // A step that was typed but not added yet is added instead of silently lost.
        val pending = newItem.trim()
        val steps = draftItems.toList() + if (taskId == null && pending.isNotEmpty()) listOf(pending) else emptyList()
        if (taskId != null && pending.isNotEmpty()) controller.act { addTaskItem(taskId, pending) }
        val ok = if (taskId == null) {
            controller.act {
                createTask(
                    NewTask(title, notes, due, priority, tag.ifBlank { null }, repeat, steps, goalId),
                    controller.today,
                )
            }
        } else {
            controller.act { updateTask(taskId, TaskEdit(title, notes, due, priority, tag.ifBlank { null }, repeat, goalId)) }
        }
        if (ok) onDone()
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                title, { title = it },
                Modifier.fillMaxWidth().then(if (taskId == null) Modifier.autoFocus() else Modifier),
                label = { Text("Task") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            Text("Due date", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate = true }) { Text(due?.let { formatDate(it) } ?: "No date") }
                if (due != null) TextButton(onClick = { due = null }) { Text("Clear") }
            }

            Text("Priority", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.reversed().forEach {
                    FilterChip(selected = priority == it, onClick = { priority = it }, label = { Text(it.label()) })
                }
            }

            val isMilestone = existing?.milestoneId != null
            if (isMilestone) {
                Text(
                    "This task is a milestone of \"${existing?.goalTitle}\". You can rename it and add a date, priority or notes. " +
                        "Its goal stays the same, and it can't repeat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isMilestone) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Goal (optional)", style = MaterialTheme.typography.titleSmall)
                InfoHint(
                    "Linking a task to a goal",
                    "The task shows up on that goal's page with its progress, such as 2 of 5 done. " +
                        "It only groups the task: finishing it does not log the goal and does not earn a treat.",
                )
            }
            val linkedMissing = existing?.goalId != null && goals.none { it.id == existing.goalId }
            if (!isMilestone) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = goalId == null, onClick = { goalId = null }, label = { Text("None") })
                if (linkedMissing) {
                    FilterChip(selected = goalId == existing?.goalId, onClick = { goalId = existing?.goalId }, label = { Text("${existing?.goalTitle} (archived)") })
                }
                goals.forEach {
                    FilterChip(selected = goalId == it.id, onClick = { goalId = it.id }, label = { Text(it.title) })
                }
            }
            if (goalId != null && !isMilestone) {
                Text(
                    "Finishing this task will not log the goal or earn a treat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isMilestone) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repeat", style = MaterialTheme.typography.titleSmall)
                InfoHint(
                    "Repeat",
                    "When you finish a repeating task, the next one is created for you with the next due date. " +
                        "If you tick it by mistake, untick it and the new copy goes away.",
                )
            }
            if (!isMilestone) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = repeat == null, onClick = { repeat = null }, label = { Text("Never") })
                Repeat.entries.forEach {
                    FilterChip(selected = repeat == it, onClick = { repeat = it }, label = { Text(it.label()) })
                }
            }

            OutlinedTextField(
                tag, { tag = it.removePrefix("#") }, Modifier.fillMaxWidth(),
                label = { Text("Tag (optional)") }, prefix = { Text("#") }, singleLine = true,
            )
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes (optional)") }, minLines = 2)

            Text("Checklist", style = MaterialTheme.typography.titleSmall)
            savedItems.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.done, { controller.act { toggleTaskItem(item.id) } }, Modifier.label("Step ${item.text}"))
                    Text(item.text, Modifier.weight(1f))
                    IconButton(onClick = { removeSaved(item.id, item.text) }) { Icon(Icons.Filled.Close, "Remove ${item.text}") }
                }
            }
            draftItems.forEachIndexed { index, text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f).padding(start = 12.dp))
                    IconButton(onClick = { draftItems.removeAt(index) }) { Icon(Icons.Filled.Close, "Remove $text") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    newItem, { newItem = it }, Modifier.weight(1f), label = { Text("Add a step") }, singleLine = true,
                    keyboardOptions = doneKey, keyboardActions = onDone(::addItem),
                )
                TextButton(onClick = ::addItem, enabled = newItem.isNotBlank()) { Text("Add") }
            }
        }
        // Pinned, so it never hides at the bottom of a long form.
        Button(
            onClick = ::save,
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(if (title.isBlank()) "Type a name first" else if (taskId == null) "Add task" else "Save") }
    }

    if (pickDate) {
        DayPickerDialog(
            min = "2000-01-01",
            max = "2100-12-31",
            onPick = { due = it; pickDate = false },
            onDismiss = { pickDate = false },
        )
    }
}

/** Details of one task with its checklist and quick actions. */
@Composable
fun TaskSheet(controller: AppController, taskId: String, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val task = remember(taskId, controller.version, controller.today) {
        controller.read(null) { getTask(taskId, controller.today) }
    }
    val items = remember(taskId, controller.version) { controller.read(emptyList()) { listTaskItems(taskId) } }
    if (task == null) return
    InfoSheet(task.title.withIcon(task.icon), onDismiss) {
        IconPickerRow(task.icon) { icon -> controller.act { setTaskIcon(task.id, icon) } }
        task.due?.let { InfoRow("Due", "${formatDate(it)} (${dueLabel(it, controller.today)})") }
        InfoRow("Priority", task.priority.label())
        task.tag?.let { InfoRow("Tag", "#$it") }
        task.repeat?.let { InfoRow("Repeats", it.label()) }
        task.goalTitle?.let { InfoRow("Goal", it) }
        if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodyMedium)
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.done, { controller.act { toggleTaskItem(item.id) } }, Modifier.label("Step ${item.text}"))
                Text(item.text)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                controller.toggleTaskWithUndo(task.id, task.title)
                onDismiss()
            }) { Text(if (task.done) "Mark not done" else "Done") }
            OutlinedButton(onClick = onEdit) { Text("Edit") }
        }
        if (task.milestoneId != null) {
            // A milestone's task is removed by removing the milestone, so the two never disagree.
            Text(
                "This is a milestone of \"${task.goalTitle}\". Ticking it counts as a target. To remove it, delete the milestone on the goal's page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Kept apart from Done, and red, so it is not tapped by accident. It can be undone.
            TextButton(onClick = {
                if (controller.act { deleteTask(task.id, controller.today) }) {
                    controller.undo = UndoOffer("Task moved to the trash.") { controller.act { restoreTask(task.id) } }
                    onDismiss()
                }
            }) { Text("Move to trash", color = MaterialTheme.colorScheme.error) }
        }
    }
}
