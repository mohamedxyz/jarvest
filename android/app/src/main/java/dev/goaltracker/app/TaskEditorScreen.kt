package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** Create a task ([taskId] is null) or edit one. */
@Composable
fun TaskEditorScreen(controller: AppController, taskId: String?, onDone: () -> Unit) {
    val existing = remember(taskId) { taskId?.let { id -> controller.read(null) { getTask(id, controller.today) } } }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var due by rememberSaveable { mutableStateOf(if (taskId == null) controller.defaultDueDate() else existing?.due) }
    // New tasks start with the defaults from Settings. Existing ones keep what they have.
    var priority by rememberSaveable { mutableStateOf(existing?.priority ?: controller.defaultPriority) }
    var tag by rememberSaveable { mutableStateOf(existing?.tag ?: "") }
    var repeat by rememberSaveable { mutableStateOf(existing?.repeat) }
    // Tasks are no longer linked to goals from the app. A link made by an older version is kept as it is.
    val goalId = existing?.goalId
    var pickDate by remember { mutableStateOf(false) }
    // Optional start time and length on the due day. The Calendar tab shows them as a block.
    var time by rememberSaveable { mutableStateOf(existing?.dueTime?.toInt()) }
    var duration by rememberSaveable { mutableStateOf(existing?.duration?.toInt() ?: 30) }
    var pickTime by remember { mutableStateOf(false) }
    // The custom part of a repeat: every N days, weeks or months, and for weekly repeats the weekdays.
    var every by rememberSaveable { mutableStateOf((existing?.repeatEvery ?: 1u).toString()) }
    var repeatDays by rememberSaveable { mutableStateOf(existing?.repeatDays?.toInt() ?: 0) }
    var editingSteps by remember { mutableStateOf(false) }
    var savingTemplate by remember { mutableStateOf(false) }
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
        val savedId: String? = if (taskId == null) {
            controller.change {
                createTask(
                    NewTask(title, notes, due, priority, tag.ifBlank { null }, repeat, steps, goalId),
                    controller.today,
                )
            }
        } else if (controller.act { updateTask(taskId, TaskEdit(title, notes, due, priority, tag.ifBlank { null }, repeat, goalId)) }) {
            taskId
        } else {
            null
        }
        if (savedId != null) {
            // The custom repeat rule is saved after the repeat itself, which creates it.
            val n = every.toUIntOrNull() ?: 1u
            if (repeat != null && repeat != Repeat.WEEKDAYS && (n != 1u || repeatDays != 0 || (existing?.repeatEvery ?: 1u) != 1u || existing?.repeatDays != null)) {
                controller.act { setTaskRepeatRule(savedId, n, if (repeat == Repeat.WEEKLY && repeatDays != 0) repeatDays.toUByte() else null) }
            }
            // A start time only exists together with a due day.
            val start = if (due != null) time else null
            if (start != null || existing?.dueTime != null || existing?.duration != null) {
                controller.act { setTaskTime(savedId, start?.toUInt(), if (start != null) duration.toUInt() else null) }
            }
            onDone()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                title, { title = it },
                Modifier.fillMaxWidth().then(if (taskId == null) Modifier.autoFocus() else Modifier),
                label = { Text(stringResource(R.string.ui_task)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            Text(stringResource(R.string.ui_due_date), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate = true }) { Text(due?.let { formatDate(it) } ?: "No date") }
                if (due != null) TextButton(onClick = { due = null; time = null }) { Text(stringResource(R.string.ui_clear)) }
            }
            if (due != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime = true }) {
                        Text(time?.let { "Starts ${Reminders.formatMinutes(it)}" } ?: "Add a start time")
                    }
                    if (time != null) TextButton(onClick = { time = null }) { Text(stringResource(R.string.ui_clear)) }
                }
                if (time != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 90, 120).forEach { m ->
                            FilterChip(selected = duration == m, onClick = { duration = m }, label = { Text("$m min") })
                        }
                    }
                }
            }

            Text(stringResource(R.string.ui_priority), style = MaterialTheme.typography.titleSmall)
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
                Text(stringResource(R.string.ui_repeat), style = MaterialTheme.typography.titleSmall)
                InfoHint(
                    "Repeat",
                    "When you finish a repeating task, the next one is created for you with the next due date. " +
                        "If you tick it by mistake, untick it and the new copy goes away.",
                )
            }
            if (!isMilestone) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = repeat == null, onClick = { repeat = null }, label = { Text(stringResource(R.string.ui_never)) })
                Repeat.entries.forEach {
                    FilterChip(selected = repeat == it, onClick = { repeat = it }, label = { Text(it.label()) })
                }
            }

            if (!isMilestone && repeat != null && repeat != Repeat.WEEKDAYS) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_every))
                    OutlinedTextField(
                        every, { every = it.filter(Char::isDigit).take(3) }, Modifier.width(80.dp), singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    val n = every.toIntOrNull() ?: 1
                    Text(
                        when (repeat) {
                            Repeat.DAILY -> if (n == 1) "day" else "days"
                            Repeat.WEEKLY -> if (n == 1) "week" else "weeks"
                            else -> if (n == 1) "month" else "months"
                        },
                    )
                }
                if (repeat == Repeat.WEEKLY) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, name ->
                            val bit = 1 shl i
                            FilterChip(repeatDays and bit != 0, { repeatDays = repeatDays xor bit }, label = { Text(name) })
                        }
                    }
                    Text(
                        "No days chosen means the same weekday as the due date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                tag, { tag = it.removePrefix("#") }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ui_tag_optional)) }, prefix = { Text(stringResource(R.string.ui_text_2)) }, singleLine = true,
            )
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_notes_optional)) }, minLines = 2)

            Text(stringResource(R.string.ui_checklist), style = MaterialTheme.typography.titleSmall)
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
                    newItem, { newItem = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ui_add_a_step)) }, singleLine = true,
                    keyboardOptions = doneKey, keyboardActions = onDone(::addItem),
                )
                TextButton(onClick = ::addItem, enabled = newItem.isNotBlank()) { Text(stringResource(R.string.ui_add)) }
            }
            if (taskId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (savedItems.isNotEmpty()) TextButton(onClick = { editingSteps = true }) { Text(stringResource(R.string.ui_edit_all_steps_as_text)) }
                    TextButton(onClick = { savingTemplate = true }) { Text(stringResource(R.string.ui_save_as_template)) }
                }
            }
        }
        // Pinned, so it never hides at the bottom of a long form.
        Button(
            onClick = ::save,
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(if (title.isBlank()) "Type a name first" else if (taskId == null) "Add task" else "Save") }
    }

    if (editingSteps && taskId != null) {
        var text by remember { mutableStateOf(savedItems.joinToString("\n") { it.text }) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingSteps = false },
            title = { Text(stringResource(R.string.ui_edit_steps)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_one_step_per_line_reorder_rename), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingSteps = false
                    controller.act { replaceTaskItems(taskId, text.lines().map { it.trim() }.filter { it.isNotEmpty() }) }
                }) { Text(stringResource(R.string.ui_save_steps)) }
            },
            dismissButton = { TextButton(onClick = { editingSteps = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
    if (savingTemplate && taskId != null) {
        var name by remember { mutableStateOf(title) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { savingTemplate = false },
            title = { Text(stringResource(R.string.ui_save_as_template)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.ui_template_name)) }, singleLine = true)
                    Text(stringResource(R.string.ui_keeps_the_title_notes_priority_t), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (controller.act { saveTaskTemplate(taskId, name, controller.today) }) {
                        savingTemplate = false
                        controller.message = "Template saved. Find it under Templates on the Tasks tab."
                    }
                }) { Text(stringResource(R.string.ui_save)) }
            },
            dismissButton = { TextButton(onClick = { savingTemplate = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
    if (pickTime) {
        TimePickerDialog(time ?: (9 * 60), onPick = { time = it; pickTime = false }, onDismiss = { pickTime = false })
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
fun TaskSheet(controller: AppController, taskId: String, onEdit: () -> Unit, onDismiss: () -> Unit, onFocus: (() -> Unit)? = null) {
    val task = remember(taskId, controller.version, controller.today) {
        controller.read(null) { getTask(taskId, controller.today) }
    }
    val items = remember(taskId, controller.version) { controller.read(emptyList()) { listTaskItems(taskId) } }
    if (task == null) return
    InfoSheet(task.shownTitle().withIcon(task.icon), onDismiss) {
        IconPickerRow(task.icon) { icon -> controller.act { setTaskIcon(task.id, icon) } }
        task.due?.let { InfoRow("Due", "${formatDate(it)} (${dueLabel(it, controller.today)})") }
        InfoRow("Priority", task.priority.label())
        task.tag?.let { InfoRow("Tag", "#$it") }
        task.repeat?.let { InfoRow("Repeats", it.label()) }
        // A link from an older version is kept but not shown: goals only have milestones.
        task.goalTitle?.takeIf { task.milestoneId != null }?.let { InfoRow("Milestone of", it) }
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
            OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.ui_edit)) }
            if (onFocus != null && !task.done) OutlinedButton(onClick = onFocus) { Text(stringResource(R.string.ui_focus)) }
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
            }) { Text(stringResource(R.string.ui_move_to_trash), color = MaterialTheme.colorScheme.error) }
        }
    }
}
