package dev.goaltracker.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.NewTask
import dev.goaltracker.core.Priority
import dev.goaltracker.core.Repeat
import dev.goaltracker.core.TaskEdit

/** Create a task ([taskId] is null) or edit one. */
@Composable
fun TaskEditorScreen(controller: AppController, taskId: String?, onDone: () -> Unit) {
    val existing = remember(taskId) { taskId?.let { id -> controller.read(null) { getTask(id, controller.today) } } }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var due by rememberSaveable { mutableStateOf(existing?.due) }
    var priority by rememberSaveable { mutableStateOf(existing?.priority ?: Priority.MED) }
    var tag by rememberSaveable { mutableStateOf(existing?.tag ?: "") }
    var repeat by rememberSaveable { mutableStateOf(existing?.repeat) }
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Task") },
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Repeat", style = MaterialTheme.typography.titleSmall)
            InfoHint(
                "Repeat",
                "When you finish a repeating task, the next one is created for you with the next due date. " +
                    "If you tick it by mistake, untick it and the new copy goes away.",
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Checkbox(item.done, { controller.act { toggleTaskItem(item.id) } })
                Text(item.text, Modifier.weight(1f))
                IconButton(onClick = { controller.act { deleteTaskItem(item.id) } }) { Icon(Icons.Filled.Close, "Remove ${item.text}") }
            }
        }
        draftItems.forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f).padding(start = 12.dp))
                IconButton(onClick = { draftItems.removeAt(index) }) { Icon(Icons.Filled.Close, "Remove $text") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newItem, { newItem = it }, Modifier.weight(1f), label = { Text("Add a step") }, singleLine = true)
            TextButton(onClick = ::addItem, enabled = newItem.isNotBlank()) { Text("Add") }
        }

        Button(
            onClick = {
                val ok = if (taskId == null) {
                    controller.act {
                        createTask(
                            NewTask(title, notes, due, priority, tag.ifBlank { null }, repeat, draftItems.toList()),
                            controller.today,
                        )
                    }
                } else {
                    controller.act { updateTask(taskId, TaskEdit(title, notes, due, priority, tag.ifBlank { null }, repeat)) }
                }
                if (ok) onDone()
            },
            Modifier.fillMaxWidth(),
        ) { Text(if (taskId == null) "Add task" else "Save") }
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
    InfoSheet(task.title, onDismiss) {
        task.due?.let { InfoRow("Due", "${formatDate(it)} (${dueLabel(it, controller.today)})") }
        InfoRow("Priority", task.priority.label())
        task.tag?.let { InfoRow("Tag", "#$it") }
        task.repeat?.let { InfoRow("Repeats", it.label()) }
        if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodyMedium)
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.done, { controller.act { toggleTaskItem(item.id) } })
                Text(item.text)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val result = controller.change { toggleTask(task.id, controller.today) }
                result?.nextDue?.let { controller.message = "Next \"${task.title}\" is due ${formatDate(it)}." }
                onDismiss()
            }) { Text(if (task.done) "Mark not done" else "Done") }
            OutlinedButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = {
                if (controller.act { deleteTask(task.id, controller.today) }) {
                    controller.undo = UndoOffer("Task moved to the trash.") { controller.act { restoreTask(task.id) } }
                    onDismiss()
                }
            }) { Text("Delete") }
        }
    }
}
