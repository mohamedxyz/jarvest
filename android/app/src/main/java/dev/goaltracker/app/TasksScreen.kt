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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.NewTask
import dev.goaltracker.core.Priority
import dev.goaltracker.core.Repeat
import dev.goaltracker.core.Task
import dev.goaltracker.core.TaskFilter
import dev.goaltracker.core.TaskSort
import dev.goaltracker.core.TrashedGoal

private fun TaskFilter.label() = when (this) {
    TaskFilter.ALL -> "All"
    TaskFilter.TODAY -> "Today"
    TaskFilter.OVERDUE -> "Overdue"
    TaskFilter.DONE -> "Done"
}

private fun TaskSort.label() = when (this) {
    TaskSort.DUE -> "Due date"
    TaskSort.PRIORITY -> "Priority"
    TaskSort.NEWEST -> "Newest"
    TaskSort.ALPHABETICAL -> "A to Z"
}

fun Priority.label() = when (this) {
    Priority.LOW -> "Low"
    Priority.MED -> "Medium"
    Priority.HIGH -> "High"
}

fun Repeat.label() = when (this) {
    Repeat.DAILY -> "Every day"
    Repeat.WEEKDAYS -> "Every weekday"
    Repeat.WEEKLY -> "Every week"
    Repeat.MONTHLY -> "Every month"
}

@Composable
fun priorityColor(priority: Priority): Color = when (priority) {
    Priority.HIGH -> MaterialTheme.colorScheme.error
    Priority.MED -> MaterialTheme.colorScheme.primary
    Priority.LOW -> MaterialTheme.colorScheme.outline
}

@Composable
fun TasksScreen(controller: AppController, onEdit: (String) -> Unit) {
    // Remembered across launches, so the tab opens the way it was left.
    val filter = controller.taskFilter
    val sort = controller.taskSort
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var quick by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Task?>(null) }

    // Tasks deleted a month ago or more are removed for good.
    LaunchedEffect(Unit) {
        controller.read(0u) { purgeTrash(controller.today) }
        controller.read(0u) { purgeGoalTrash(controller.today) }
    }
    val trashedGoals = remember(controller.version, controller.today, showTrash) {
        if (showTrash) controller.read(emptyList()) { listGoalTrash(controller.today) } else emptyList()
    }
    var goalToDelete by remember { mutableStateOf<TrashedGoal?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    val tasks = remember(controller.version, controller.today, filter, sort, showTrash) {
        if (showTrash) controller.read(emptyList()) { listTrash(controller.today) }
        else controller.read(emptyList()) { listTasks(filter, sort, controller.today) }
    }
    val hint = remember(quick, controller.today) {
        if (quick.isBlank()) null else controller.read(null) { parseQuick(quick, controller.today) }
    }
    val overdueCount = remember(controller.version, controller.today) {
        controller.read(0) { listTasks(TaskFilter.OVERDUE, TaskSort.DUE, controller.today).size }
    }
    val submit = {
        if (quick.isNotBlank()) {
            // Words in the line win. What it leaves out comes from the defaults in Settings.
            val parsed = controller.read(null) { parseQuick(quick, controller.today) }
            if (parsed != null) {
                val task = NewTask(
                    parsed.title, "", parsed.due ?: controller.defaultDueDate(),
                    parsed.priority ?: controller.defaultPriority, parsed.tag, null, emptyList(), null,
                )
                if (controller.act { createTask(task, controller.today) }) quick = ""
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!showTrash) {
            OutlinedTextField(
                value = quick,
                onValueChange = { quick = it },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                placeholder = { Text("Add a task, e.g. Essay tomorrow !high #uni") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = submit, enabled = quick.isNotBlank()) {
                        Icon(Icons.Filled.Add, "Add task")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            hint?.let { parsed ->
                val bits = listOfNotNull(
                    parsed.due?.let { "Due ${formatDate(it)}" },
                    parsed.priority?.let { "${it.label()} priority" },
                    parsed.tag?.let { "#$it" },
                )
                if (bits.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Adds:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        bits.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
                // A word like !hgh isn't understood and would stay in the title, so say so.
                val unknown = parsed.title.split(" ").firstOrNull { it.startsWith("!") && it.length > 1 }
                if (unknown != null) {
                    Text(
                        "\"$unknown\" isn't a priority. Use !high, !med or !low.",
                        Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TaskFilter.entries.forEach {
                        FilterChip(
                            selected = filter == it,
                            onClick = { controller.chooseTaskFilter(it) },
                            label = { Text(if (it == TaskFilter.OVERDUE && overdueCount > 0) "Overdue · $overdueCount" else it.label()) },
                        )
                    }
                }
                // Outside the scrolling row, so it can never scroll out of reach.
                Column {
                    TextButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
                        Text(" ${sort.label()}")
                    }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        TaskSort.entries.forEach {
                            DropdownMenuItem(
                                text = { Text(it.label()) },
                                onClick = { controller.chooseTaskSort(it); sortMenu = false },
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (showTrash) "Trash: tasks are removed for good after 30 days" else "${tasks.size} ${if (tasks.size == 1) "task" else "tasks"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showTrash = !showTrash }) { Text(if (showTrash) "Back to tasks" else "Trash") }
        }

        if (showTrash && trashedGoals.isNotEmpty()) {
            TextButton(onClick = { confirmEmpty = true }, Modifier.padding(horizontal = 8.dp)) {
                Text("Empty goal trash", color = MaterialTheme.colorScheme.error)
            }
        }

        if (tasks.isEmpty() && trashedGoals.isEmpty()) {
            when {
                showTrash -> EmptyState("The trash is empty", "Deleted tasks wait here for 30 days.")
                filter == TaskFilter.ALL -> EmptyState("No tasks yet", "Type one above. Words like tomorrow, !high and #tag fill in the date, priority and tag for you.")
                else -> EmptyState("Nothing here", "No ${filter.label().lowercase()} tasks.")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trashedGoals, key = { "goal-" + it.id }) { goal ->
                    GoalTrashRow(
                        goal,
                        onRestore = { controller.act { restoreGoal(goal.id) } },
                        onDeleteForever = { goalToDelete = goal },
                    )
                }
                item { TipCard(controller, "tasks_quick_add", "Tip: write a task in one line. \"Essay tomorrow !high #uni\" sets the due date, the priority and the tag.") }
                items(tasks, key = { it.id }) { task ->
                    if (showTrash) {
                        TrashRow(task) { controller.act { restoreTask(task.id) } }
                    } else {
                        TaskRow(
                            task,
                            controller.today,
                            onOpen = { opened = task },
                            onCheck = { controller.toggleTaskWithUndo(task.id, task.title) },
                        )
                    }
                }
            }
        }
    }

    goalToDelete?.let { goal ->
        ConfirmDialog(
            title = "Delete this goal forever?",
            body = "\"${goal.title}\" and its ${countOf(goal.entries.toInt(), "log")}, milestones and rewards will be gone for good. This can't be undone.",
            confirm = "Delete forever",
            onConfirm = {
                goalToDelete = null
                controller.act { deleteGoal(goal.id) }
            },
            onDismiss = { goalToDelete = null },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty the goal trash?",
            body = "Every goal in the trash, with its history and rewards, will be deleted for good. Tasks in the trash are not affected.",
            confirm = "Delete forever",
            onConfirm = {
                confirmEmpty = false
                controller.act { emptyGoalTrash() }
            },
            onDismiss = { confirmEmpty = false },
        )
    }

    opened?.let { task ->
        TaskSheet(
            controller,
            taskId = task.id,
            onEdit = { opened = null; onEdit(task.id) },
            onDismiss = { opened = null },
        )
    }
}

@Composable
private fun TaskRow(task: Task, today: String, onOpen: () -> Unit, onCheck: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title.withIcon(task.icon),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.priority != Priority.MED) {
                        // A signal-bars icon, so priority never looks like the Goals tab's flag.
                        Icon(Icons.Filled.SignalCellularAlt, null, Modifier.size(14.dp), tint = priorityColor(task.priority))
                        Text(
                            task.priority.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = priorityColor(task.priority),
                        )
                    }
                    task.due?.let {
                        Text(
                            dueLabel(it, today),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else dueColor(it, today),
                        )
                    }
                    task.goalTitle?.let { Text(if (task.milestoneId != null) "Milestone of $it" else "→ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    task.tag?.let { Text("#$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (task.repeat != null) {
                        Icon(Icons.Outlined.Repeat, "Repeats", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (task.itemsTotal > 0u) {
                        Text("${task.itemsDone}/${task.itemsTotal}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TickBox(
                checked = task.done,
                onCheckedChange = { onCheck() },
                modifier = Modifier.label(if (task.done) "Mark ${task.title} not done" else "Mark ${task.title} done"),
            )
        }
    }
}

@Composable
private fun TrashRow(task: Task, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(task.title, Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}
