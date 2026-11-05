package dev.goaltracker.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.Goal
import dev.goaltracker.core.GoalKind

private const val DAY_NAMES = "Mon,Tue,Wed,Thu,Fri,Sat,Sun"

/** Goals with no category sit in this folder. It is always last and can't be renamed. */
private const val NO_FOLDER = "No folder"

/** How far a handle must be dragged, in pixels per 1 dp of card height, to move one place. */
private const val DRAG_STEP_DP = 72

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalsScreen(controller: AppController, habits: Boolean, onOpenGoal: (String) -> Unit) {
    var archived by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<GoalKind?>(null) }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // The filters hide behind one row, so the list gets the screen.
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    // Folder names the user has collapsed. A list, because it must survive rotation.
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var renaming by remember { mutableStateOf<String?>(null) }
    // Goals can be grouped by folder or by deadline. Habits only use folders.
    var byDeadline by rememberSaveable { mutableStateOf(false) }
    val hideDone = if (habits) controller.hideDoneHabits else controller.hideDoneGoals

    val allGoals = remember(controller.version, archived) {
        controller.read(emptyList()) { listGoals(archived) }.filter { (it.kind == GoalKind.HABIT) == habits }
    }
    // Habits have no "finished" of their own, so they count as finished once ticked today.
    val doneToday = remember(controller.version, controller.today) {
        controller.read(emptyList()) { today(controller.today) }.filter { it.complete }.map { it.goalId }.toSet()
    }
    val folderOrder = remember(controller.version) { controller.read(emptyList()) { folderOrder() } }
    val categories = remember(allGoals) { allGoals.mapNotNull { it.category }.distinct() }
    // While reordering, filters are ignored so every move is among the real neighbours.
    val filtering = !reordering && (query.isNotBlank() || kindFilter != null || categoryFilter != null)
    val goals = if (reordering) allGoals else allGoals.filter { goal ->
        (query.isBlank() || goal.title.contains(query.trim(), ignoreCase = true) ||
            goal.category?.contains(query.trim(), ignoreCase = true) == true) &&
            (kindFilter == null || goal.kind == kindFilter) &&
            (categoryFilter == null || goal.category == categoryFilter) &&
            !(hideDone && !archived && (if (habits) goal.id in doneToday else goal.done))
    }
    // Folders in the user's order first, then the rest A to Z, and goals without a folder last.
    val folders = remember(allGoals, folderOrder) {
        val named = allGoals.mapNotNull { it.category }.distinct()
        val ordered = folderOrder.filter { it in named } +
            named.filter { it !in folderOrder }.sortedBy { it.lowercase() }
        ordered + if (allGoals.any { it.category == null }) listOf(NO_FOLDER) else emptyList()
    }
    val activeFilters = listOfNotNull(kindFilter?.let { "type" }, categoryFilter?.let { "folder" }).size +
        (if (archived) 1 else 0) + (if (hideDone) 1 else 0) + (if (byDeadline && !habits) 1 else 0)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                label = { Text(if (habits) "Search habits" else "Search goals") },
                singleLine = true,
                enabled = !reordering,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !reordering, onClick = { filterOpen = !filterOpen }) {
                    Text(
                        (if (filterOpen) "Hide filter" else "Filter") +
                            if (activeFilters > 0) " ($activeFilters on)" else "",
                    )
                }
                Row(Modifier.weight(1f)) {}
                if (allGoals.size > 1) {
                    OutlinedButton(onClick = { reordering = !reordering }) { Text(if (reordering) "Done" else "Reorder") }
                }
            }
            if (filterOpen && !reordering) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("Active") })
                    FilterChip(selected = archived, onClick = { archived = true }, label = { Text("Archived") })
                    FilterChip(
                        selected = hideDone,
                        onClick = { if (habits) controller.chooseHideDoneHabits(!hideDone) else controller.chooseHideDoneGoals(!hideDone) },
                        label = { Text(if (habits) "Hide done today" else "Hide finished") },
                    )
                    if (!habits) {
                        FilterChip(
                            selected = byDeadline,
                            onClick = { byDeadline = !byDeadline },
                            label = { Text("Group by deadline") },
                        )
                    }
                    (if (habits) emptyList() else listOf(GoalKind.NUMERIC to "Numeric", GoalKind.YES_NO to "Yes/no")).forEach { (kind, label) ->
                        FilterChip(
                            selected = kindFilter == kind,
                            onClick = { kindFilter = if (kindFilter == kind) null else kind },
                            label = { Text(label) },
                        )
                    }
                    categories.forEach { name ->
                        FilterChip(
                            selected = categoryFilter == name,
                            onClick = { categoryFilter = if (categoryFilter == name) null else name },
                            label = { Text(name) },
                        )
                    }
                }
            }
            if (reordering) {
                Text(
                    "Drag the handle to move a goal within its folder. Use the arrows beside a folder name to move the folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (goals.isEmpty()) {
            if (filtering) {
                EmptyState("No matching goals", "Try a different search or clear a filter.")
            } else if (archived) {
                EmptyState("Nothing archived", "Archived goals keep their history and appear here.")
            } else {
                if (habits) EmptyState("No habits yet", "Tap New habit to create one.") else EmptyState("No goals yet", "Tap New goal to create one.")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (byDeadline && !habits && !reordering) {
                val groups = goals.groupBy { timelineOf(it.deadline, it.done, controller.today) }.toSortedMap()
                groups.forEach { (bucket, list) ->
                    item(key = "timeline:${bucket.name}") { TimelineHeader(if (bucket == Timeline.NO_DATE) "No deadline" else bucket.label, list.size) }
                    items(list.size, key = { "timeline:${bucket.name}:${list[it].id}" }) { index ->
                        val goal = list[index]
                        GoalCard(
                            goal = goal,
                            reordering = false,
                            canMoveUp = false,
                            canMoveDown = false,
                            onOpen = { onOpenGoal(goal.id) },
                            onMove = {},
                        )
                    }
                }
                return@LazyColumn
            }
            folders.forEach { folder ->
                val inFolder = goals.filter { (it.category ?: NO_FOLDER) == folder }
                if (inFolder.isEmpty()) return@forEach
                val isOpen = reordering || folder !in collapsed
                val doneCount = inFolder.count { it.done }
                item(key = "folder:$folder") {
                    FolderHeader(
                        name = folder,
                        open = isOpen,
                        total = inFolder.size,
                        done = doneCount,
                        reordering = reordering,
                        canMoveUp = folders.indexOf(folder) > 0 && folder != NO_FOLDER,
                        canMoveDown = folder != NO_FOLDER && folders.indexOf(folder) < folders.filter { it != NO_FOLDER }.lastIndex,
                        onToggle = { collapsed = if (folder in collapsed) collapsed - folder else collapsed + folder },
                        onRename = { renaming = folder },
                        onMove = { earlier ->
                            val named = folders.filter { it != NO_FOLDER }.toMutableList()
                            val at = named.indexOf(folder)
                            val to = if (earlier) at - 1 else at + 1
                            if (to in named.indices) {
                                named[at] = named[to].also { named[to] = named[at] }
                                controller.act { setFolderOrder(named) }
                            }
                        },
                    )
                }
                if (isOpen) {
                    items(inFolder.size, key = { inFolder[it].id }) { index ->
                        val goal = inFolder[index]
                        GoalCard(
                            goal = goal,
                            reordering = reordering,
                            canMoveUp = index > 0,
                            canMoveDown = index < inFolder.lastIndex,
                            onOpen = { onOpenGoal(goal.id) },
                            onMove = { earlier -> controller.act { moveGoal(goal.id, earlier) } },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { old ->
        RenameFolderDialog(
            old = old,
            onRename = { new ->
                // Goals with no folder get one this way, so an empty old name is fine to skip.
                val ok = if (old == NO_FOLDER) true else controller.act { renameCategory(old, new) }
                if (ok) renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun FolderHeader(
    name: String,
    open: Boolean,
    total: Int,
    done: Int,
    reordering: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onMove: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !reordering, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, if (open) "Collapse $name" else "Expand $name")
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (done == total) "All done" else "$done of $total done",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (reordering && name != NO_FOLDER) {
            IconButton(enabled = canMoveUp, onClick = { onMove(true) }) { Icon(Icons.Filled.KeyboardArrowUp, "Move folder $name up") }
            IconButton(enabled = canMoveDown, onClick = { onMove(false) }) { Icon(Icons.Filled.KeyboardArrowDown, "Move folder $name down") }
        } else if (name != NO_FOLDER) {
            IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, "Rename folder $name") }
        }
    }
}

@Composable
private fun GoalCard(
    goal: Goal,
    reordering: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onMove: (Boolean) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val stepPx = with(density) { DRAG_STEP_DP.dp.toPx() }
    Card(Modifier.fillMaxWidth().clickable(enabled = !reordering, onClick = onOpen)) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(goal.title.withIcon(goal.icon), style = MaterialTheme.typography.titleMedium)
                Text(
                    describe(goal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (reordering) {
                // Dragging up or down moves the goal one place for every step of the finger.
                // The list reloads after each move, and the handle keeps tracking the drag.
                var dragged by remember { mutableStateOf(0f) }
                Icon(
                    Icons.Filled.DragHandle,
                    "Reorder ${goal.title}",
                    Modifier
                        .padding(12.dp)
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Move up") { if (canMoveUp) onMove(true); canMoveUp },
                                CustomAccessibilityAction("Move down") { if (canMoveDown) onMove(false); canMoveDown },
                            )
                        }
                        .pointerInput(goal.id, canMoveUp, canMoveDown) {
                            detectDragGestures(
                                onDragEnd = { dragged = 0f },
                                onDragCancel = { dragged = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragged += amount.y
                                if (dragged <= -stepPx && canMoveUp) {
                                    dragged = 0f
                                    onMove(true)
                                } else if (dragged >= stepPx && canMoveDown) {
                                    dragged = 0f
                                    onMove(false)
                                }
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun RenameFolderDialog(old: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(old) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename folder") },
        text = {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Folder name") }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onRename(text) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Mon, Wed, Fri" for a weekday bitmask, or null when every day is due. */
fun weekdaysLabel(mask: UByte?): String? {
    if (mask == null) return null
    val names = DAY_NAMES.split(",")
    return names.filterIndexed { index, _ -> mask.toInt() and (1 shl index) != 0 }.joinToString(", ")
}

/** A one-line summary, such as "Health · 3 times per week". */
fun describe(goal: Goal): String {
    if (goal.avoid) return listOfNotNull(goal.category, "Quit habit").joinToString(" · ")
    val period = goal.period
    val periodTarget = goal.periodTarget
    val totalTarget = goal.totalTarget
    val summary = when (goal.kind) {
        GoalKind.YES_NO -> if (goal.done) "Done" else "Not done yet"
        GoalKind.HABIT -> {
            val count = periodTarget?.toInt() ?: 1
            "${countOf(count, "time")} per ${period?.noun()}" +
                (weekdaysLabel(goal.weekdays)?.let { " ($it)" } ?: "")
        }
        GoalKind.NUMERIC -> listOfNotNull(
            if (period != null && periodTarget != null) "${withUnit(periodTarget, goal.unit)} per ${period.noun()}" else null,
            if (totalTarget != null) "${formatAmount(goal.totalLogged)} of ${withUnit(totalTarget, goal.unit)}" else null,
            if (period == null && totalTarget == null) "${withUnit(goal.totalLogged, goal.unit)} logged" else null,
        ).joinToString(" · ")
    }
    val milestones = if (goal.milestonesTotal > 0u) "${goal.milestonesDone} of ${goal.milestonesTotal} milestones" else null
    return listOfNotNull(goal.category, summary, milestones).joinToString(" · ")
}
