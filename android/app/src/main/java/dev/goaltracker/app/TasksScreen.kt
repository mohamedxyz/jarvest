package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import dev.goaltracker.core.BulkEdit
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
fun TasksScreen(controller: AppController, onEdit: (String) -> Unit, onFocus: (String) -> Unit = {}) {
    // Remembered across launches, so the tab opens the way it was left.
    val filter = controller.taskFilter
    val sort = controller.taskSort
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var quick by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Task?>(null) }
    var showArchive by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // Tasks picked with a long press. While any are picked, a tap picks or drops one.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmEmptyTasks by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var dueMenu by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    var pickBulkDate by remember { mutableStateOf(false) }

    // Tasks deleted a month ago or more are removed for good.
    LaunchedEffect(Unit) {
        controller.read(0u) { purgeTrash(controller.today) }
        controller.runAutoArchive()
        controller.read(0u) { purgeGoalTrash(controller.today) }
    }
    val trashedGoals = remember(controller.version, controller.today, showTrash) {
        if (showTrash) controller.read(emptyList()) { listGoalTrash(controller.today) } else emptyList()
    }
    var goalToDelete by remember { mutableStateOf<TrashedGoal?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var taskToDelete by remember { mutableStateOf<Task?>(null) }

    val tasks = remember(controller.version, controller.today, filter, sort, showTrash, showArchive, searching, searchText, tagFilter) {
        val base = when {
            showTrash -> controller.read(emptyList()) { listTrash(controller.today) }
            showArchive -> controller.read(emptyList()) { listArchivedTasks(controller.today) }
            searching && searchText.isNotBlank() -> controller.read(emptyList()) { search(searchText, controller.today).tasks }
            else -> controller.read(emptyList()) { listTasks(filter, sort, controller.today) }
        }
        val tag = tagFilter
        val tagged = if (tag != null && !showTrash) base.filter { it.tag == tag } else base
        // "Hide done" only applies to the plain lists, never to Done, Trash, Archive or search.
        val plain = !showTrash && !showArchive && !(searching && searchText.isNotBlank())
        if (plain && controller.hideDoneTasks && filter != TaskFilter.DONE) tagged.filter { !it.done } else tagged
    }
    // Separators by due date only make sense when the list is sorted by due date.
    val plainList = !showTrash && !showArchive && !(searching && searchText.isNotBlank())
    val timeline = plainList && ((sort == TaskSort.DUE && filter != TaskFilter.DONE) || controller.groupTasksByTag)
    // Sections: one per tag when grouping by tag (untagged last), otherwise one per stretch of time.
    val groups: List<Pair<String, List<Task>>> = if (!timeline) emptyList() else if (controller.groupTasksByTag) {
        tasks.groupBy { it.tag ?: "" }.toSortedMap(compareBy({ it.isEmpty() }, { it }))
            .map { (tag, list) -> (if (tag.isEmpty()) "No tag" else "#$tag") to list }
    } else {
        tasks.groupBy { timelineOf(it.due, it.done, controller.today) }.toSortedMap().map { (bucket, list) -> bucket.label to list }
    }
    val tags = remember(controller.version) { controller.read(emptyList()) { listTags() } }
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
                val id = controller.change { createTask(task, controller.today) }
                if (id != null) {
                    // "dentist friday 3pm 45m": the time and length are saved as a calendar block.
                    if (parsed.time != null) {
                        controller.act { setTaskTime(id, parsed.time, parsed.minutes) }
                    }
                    quick = ""
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp).autoFocus(),
                placeholder = { Text(stringResource(R.string.ui_search_tasks_including_archived)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { searching = false; searchText = "" }) { Icon(Icons.Filled.Close, "Close search") }
                },
            )
        }
        if (selected.isNotEmpty()) {
            val picked = tasks.filter { it.id in selected }
            val finish = { selected = emptySet() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = finish) { Icon(Icons.Filled.Close, "Clear selection") }
                    Text("${selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = {
                        selected = if (picked.size == tasks.size) emptySet() else tasks.map { it.id }.toSet()
                    }) { Text(if (picked.size == tasks.size) "Select none" else "Select all") }
                }
                // Like Todoist: the same few actions for every task picked, each applied in one step.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(onClick = { controller.bulkTasks(picked, bulk(done = true), "Finished"); finish() }, label = { Text(stringResource(R.string.ui_done)) })
                    Box {
                        AssistChip(
                            onClick = { dueMenu = true },
                            label = { Text("Due date") },
                            leadingIcon = { Icon(Icons.Filled.Event, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(dueMenu, { dueMenu = false }) {
                            bulkDueChoices(controller.today).forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.label) },
                                    onClick = {
                                        dueMenu = false
                                        controller.bulkTasks(picked, bulk(due = choice.date, changeDue = true), choice.verb)
                                        finish()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Pick a date…") },
                                onClick = { dueMenu = false; pickBulkDate = true },
                            )
                        }
                    }
                    Box {
                        AssistChip(
                            onClick = { priorityMenu = true },
                            label = { Text(stringResource(R.string.ui_priority)) },
                            leadingIcon = { Icon(Icons.Filled.SignalCellularAlt, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(priorityMenu, { priorityMenu = false }) {
                            Priority.entries.reversed().forEach { level ->
                                DropdownMenuItem(
                                    text = { Text(level.label(), color = priorityColor(level)) },
                                    leadingIcon = { Icon(Icons.Filled.SignalCellularAlt, null, Modifier.size(18.dp), tint = priorityColor(level)) },
                                    onClick = {
                                        priorityMenu = false
                                        controller.bulkTasks(picked, bulk(priority = level), "Set ${level.label().lowercase()} priority on {n}")
                                        finish()
                                    },
                                )
                            }
                        }
                    }
                    AssistChip(onClick = { controller.bulkTasks(picked, bulk(archive = true), "Archived"); finish() }, label = { Text(stringResource(R.string.ui_archive)) })
                    AssistChip(
                        onClick = { controller.bulkTasks(picked, bulk(trash = true), "Deleted"); finish() },
                        label = { Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
        if (!showTrash && !showArchive && !searching) {
            OutlinedTextField(
                value = quick,
                onValueChange = { quick = it },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                placeholder = { Text(stringResource(R.string.ui_add_a_task_e_g_essay_tomorrow_hi)) },
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
                        Text(stringResource(R.string.ui_adds), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    FilterChip(
                        selected = controller.hideDoneTasks,
                        onClick = { controller.chooseHideDoneTasks(!controller.hideDoneTasks) },
                        label = { Text(stringResource(R.string.ui_hide_done)) },
                    )
                    FilterChip(
                        selected = controller.groupTasksByTag,
                        onClick = { controller.chooseGroupTasksByTag(!controller.groupTasksByTag) },
                        label = { Text(stringResource(R.string.ui_group_by_tag)) },
                    )
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
            if (tags.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { t ->
                        FilterChip(
                            selected = tagFilter == t.tag,
                            onClick = { tagFilter = if (tagFilter == t.tag) null else t.tag },
                            label = { Text("#${t.tag} · ${t.open}") },
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                when {
                    showTrash -> "Trash: tasks are removed for good after 30 days"
                    showArchive -> "Archive: hidden from your lists, kept for good"
                    else -> "${tasks.size} ${if (tasks.size == 1) "task" else "tasks"}"
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!showTrash && !showArchive) {
                IconButton(onClick = { searching = !searching; if (!searching) searchText = "" }) {
                    Icon(Icons.Filled.Search, "Search tasks")
                }
            }
            if (!showTrash) {
                TextButton(onClick = { showArchive = !showArchive; selected = emptySet() }) {
                    Text(if (showArchive) "Back to tasks" else "Archive")
                }
            }
            if (!showArchive && !showTrash) {
                TextButton(onClick = { showTemplates = true }) { Text(stringResource(R.string.ui_templates)) }
            }
            if (!showArchive) {
                TextButton(onClick = { showTrash = !showTrash; selected = emptySet() }) { Text(if (showTrash) "Back to tasks" else "Trash") }
            }
        }
        if (showTrash && tasks.isNotEmpty()) {
            TextButton(onClick = { confirmEmptyTasks = true }, Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.ui_empty_task_trash), color = MaterialTheme.colorScheme.error)
            }
        }

        if (showTrash && trashedGoals.isNotEmpty()) {
            TextButton(onClick = { confirmEmpty = true }, Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.ui_empty_goal_trash), color = MaterialTheme.colorScheme.error)
            }
        }

        if (tasks.isEmpty() && trashedGoals.isEmpty()) {
            when {
                showTrash -> EmptyState("The trash is empty", "Deleted tasks wait here for 30 days.")
                showArchive -> EmptyState("Nothing archived", "Archive a finished task to hide it without deleting it.")
                searching && searchText.isNotBlank() -> EmptyState("No matches", "Nothing found for \"$searchText\".")
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
                if (timeline) {
                    groups.forEach { (heading, list) ->
                        item(key = "h:$heading") { TimelineHeader(heading, list.size) }
                        items(list, key = { it.id }) { task ->
                            TaskRow(
                                controller,
                                task,
                                controller.today,
                                selected = task.id in selected,
                                onOpen = {
                                    if (selected.isNotEmpty()) {
                                        selected = if (task.id in selected) selected - task.id else selected + task.id
                                    } else opened = task
                                },
                                onLongPress = { selected = selected + task.id },
                                onCheck = { controller.toggleTaskWithUndo(task.id, task.title) },
                                modifier = softItem(),
                            )
                        }
                    }
                }
                items(if (timeline) emptyList() else tasks, key = { it.id }) { task ->
                    when {
                        showTrash -> TrashRow(
                            task,
                            controller.today,
                            onRestore = { controller.act { restoreTask(task.id) } },
                            onDeleteForever = {
                                if (controller.confirmDeleteForever) taskToDelete = task
                                else if (controller.act { deleteTaskForever(task.id) }) controller.message = "Task deleted for good"
                            },
                        )
                        showArchive -> ArchiveRow(task) { controller.act { unarchiveTask(task.id) } }
                        else -> TaskRow(
                            controller,
                            task,
                            controller.today,
                            selected = task.id in selected,
                            onOpen = {
                                if (selected.isNotEmpty()) {
                                    selected = if (task.id in selected) selected - task.id else selected + task.id
                                } else opened = task
                            },
                            onLongPress = { selected = selected + task.id },
                            onCheck = { controller.toggleTaskWithUndo(task.id, task.title) },
                            modifier = softItem(),
                        )
                    }
                }
            }
        }
    }

    if (pickBulkDate) {
        val today = LocalDate.parse(controller.today)
        DayPickerDialog(
            min = today.minusYears(1).toString(),
            max = today.plusYears(5).toString(),
            onPick = { date ->
                pickBulkDate = false
                val picked = tasks.filter { it.id in selected }
                controller.bulkTasks(picked, bulk(due = date, changeDue = true), "Moved {n}")
                selected = emptySet()
            },
            onDismiss = { pickBulkDate = false },
        )
    }
    goalToDelete?.let { goal ->
        ConfirmDialog(
            title = "Delete this goal forever?",
            body = "\"${goal.title}\" and its ${countOf(goal.entries.toInt(), "log")}, milestones and rewards will be gone for good. This can't be undone.",
            confirm = "Delete forever",
            onConfirm = {
                goalToDelete = null
                if (controller.act { deleteGoal(goal.id) }) controller.message = "Goal deleted for good"
            },
            onDismiss = { goalToDelete = null },
        )
    }
    taskToDelete?.let { task ->
        ConfirmDialog(
            title = "Delete this task forever?",
            body = "\"${task.title}\" will be gone for good. This can't be undone.",
            confirm = "Delete forever",
            onConfirm = {
                taskToDelete = null
                if (controller.act { deleteTaskForever(task.id) }) controller.message = "Task deleted for good"
            },
            onDismiss = { taskToDelete = null },
        )
    }
    if (confirmEmptyTasks) {
        ConfirmDialog(
            title = "Empty the task trash?",
            body = "Every task in the trash will be deleted for good.",
            confirm = "Delete forever",
            onConfirm = {
                confirmEmptyTasks = false
                val removed = controller.change { emptyTaskTrash() }
                if (removed != null) controller.message = if (removed == 0u) "The task trash was already empty" else "Deleted ${countOf(removed.toInt(), "task")} for good"
            },
            onDismiss = { confirmEmptyTasks = false },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty the goal trash?",
            body = "Every goal in the trash, with its history and rewards, will be deleted for good. Tasks in the trash are not affected.",
            confirm = "Delete forever",
            onConfirm = {
                confirmEmpty = false
                val removed = controller.change { emptyGoalTrash() }
                if (removed != null) controller.message = if (removed == 0u) "The goal trash was already empty" else "Deleted ${countOf(removed.toInt(), "goal")} for good"
            },
            onDismiss = { confirmEmpty = false },
        )
    }

    if (showTemplates) {
        val templates = remember(controller.version) { controller.read(emptyList()) { listTaskTemplates() } }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTemplates = false },
            title = { Text(stringResource(R.string.ui_templates)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (templates.isEmpty()) {
                        Text(stringResource(R.string.ui_no_templates_yet_open_a_task_tap), style = MaterialTheme.typography.bodyMedium)
                    }
                    templates.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name)
                                Text(
                                    buildList {
                                        add(t.title)
                                        if (t.steps > 0u) add(countOf(t.steps.toInt(), "step"))
                                        t.minutes?.let { add("$it min") }
                                    }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                if (controller.act { taskFromTemplate(t.id, controller.defaultDueDate(), controller.today) }) {
                                    showTemplates = false
                                    controller.message = "Task added from \"${t.name}\""
                                }
                            }) { Text(stringResource(R.string.ui_use)) }
                            IconButton(onClick = { controller.act { deleteTaskTemplate(t.id) } }) {
                                Icon(Icons.Filled.Close, "Delete template ${t.name}")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTemplates = false }) { Text(stringResource(R.string.ui_done)) } },
        )
    }

    opened?.let { task ->
        TaskSheet(
            controller,
            taskId = task.id,
            onEdit = { opened = null; onEdit(task.id) },
            onDismiss = { opened = null },
            onFocus = { opened = null; onFocus(task.id) },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TaskRow(controller: AppController, task: Task, today: String, selected: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit, onCheck: () -> Unit, modifier: Modifier = Modifier) {
    SoftCard(
        modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = if (controller.compactTaskRows) 2.dp else 8.dp, bottom = if (controller.compactTaskRows) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fade by androidx.compose.animation.core.animateFloatAsState(
                if (task.done) 0.6f else 1f, motionTween(LocalMotion.current, 250), label = "done",
            )
            Column(Modifier.weight(1f).graphicsLayer { alpha = fade }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.title.withIcon(task.icon),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (controller.showTaskNotes && task.notes.isNotBlank()) {
                    Text(
                        task.notes.lineSequence().first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                            dueLabel(it, today) + (task.dueTime?.let { t -> " · ${Reminders.formatMinutes(t.toInt())}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else dueColor(it, today),
                        )
                    }
                    task.goalTitle?.let { Text(if (task.milestoneId != null) "Milestone of $it" else "→ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    task.tag?.let { Text("#$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (task.repeat != null) {
                        Icon(Icons.Outlined.Repeat, "Repeats", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (controller.showChecklistProgress && task.itemsTotal > 0u) {
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
private fun TrashRow(task: Task, today: String, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val left = task.deletedDate?.let {
        val gone = LocalDate.parse(it).plusDays(30)
        ChronoUnit.DAYS.between(LocalDate.parse(today), gone).coerceAtLeast(0)
    }
    SoftCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (left != null) {
                    Text(
                        if (left == 1L) "1 day left" else "$left days left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onRestore) { Text(stringResource(R.string.ui_restore)) }
            TextButton(onClick = onDeleteForever) { Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ArchiveRow(task: Task, onUnarchive: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Archive, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                task.title,
                Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
            )
            TextButton(onClick = onUnarchive) { Text(stringResource(R.string.ui_unarchive)) }
        }
    }
}

/** A bulk change that does nothing unless you switch one thing on. */
private fun bulk(
    done: Boolean = false,
    archive: Boolean = false,
    trash: Boolean = false,
    changeDue: Boolean = false,
    due: String? = null,
    priority: Priority? = null,
) = BulkEdit(done, archive, trash, changeDue, due, priority, false, null, false, null)

/** One quick choice in the bulk "Due date" menu. A null [date] clears the due date. */
internal data class DueChoice(val label: String, val date: String?, val verb: String)

/** Today, Tomorrow, the coming weekend, next week and No date, worked out from [today]. */
internal fun bulkDueChoices(today: String): List<DueChoice> {
    val now = LocalDate.parse(today)
    val saturday = now.plusDays(((java.time.DayOfWeek.SATURDAY.value - now.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }.toLong())
    val monday = now.plusDays(((java.time.DayOfWeek.MONDAY.value - now.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }.toLong())
    return listOf(
        DueChoice("Today", now.toString(), "Moved {n} to today"),
        DueChoice("Tomorrow", now.plusDays(1).toString(), "Moved {n} to tomorrow"),
        DueChoice("This weekend", saturday.toString(), "Moved {n} to the weekend"),
        DueChoice("Next week", monday.toString(), "Moved {n} to next week"),
        DueChoice("No date", null, "Cleared the date of {n}"),
    )
}
