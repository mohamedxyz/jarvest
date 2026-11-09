package dev.goaltracker.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** What the list shows, top to bottom. */
private sealed interface Line {
    val key: String
}

private class TaskLine(val item: Item, val isSub: Boolean, val subs: Int, val subsDone: Int) : Line {
    override val key = "t${item.id}"
}

/** The "Add subtask" row under a main task that is being edited. */
private class AddSubLine(val parentId: Long, val lastSubId: Long?) : Line {
    override val key = "a$parentId"
}

private class CompletedLine(val count: Int, val open: Boolean) : Line {
    override val key = "completed"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListScreen(store: Store, onSettings: () -> Unit) {
    val p = LocalPalette.current
    val items = store.items
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    var editingId by remember { mutableStateOf<Long?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(store.saveFailed) {
        if (store.saveFailed) snackbar.showSnackbar("Couldn't save. Is the phone's storage full?")
    }

    fun stopSelecting() {
        selecting = false
        selected = emptySet()
    }

    fun select(id: Long) {
        focus.clearFocus()
        editingId = null
        if (!selecting) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        selecting = true
        selected = if (id in selected) selected - id else selected + id
    }

    fun deleteSelected() {
        val before = store.items
        store.delete(selected)
        val removed = before.size - store.items.size
        stopSelecting()
        scope.launch {
            val result = snackbar.showSnackbar(
                if (removed == 1) "Deleted 1 task" else "Deleted $removed tasks",
                actionLabel = "Undo",
            )
            if (result == SnackbarResult.ActionPerformed) store.replaceAll(before)
        }
    }

    /** Called when a title field loses focus: saves what was typed, or removes an empty new row. */
    fun finishEditing(id: Long, text: String) {
        val current = store.items.firstOrNull { it.id == id }
        if (current != null) {
            val clean = text.trim()
            when {
                clean.isEmpty() && current.title.isEmpty() -> store.rename(id, "")
                clean.isNotEmpty() && clean != current.title -> store.rename(id, clean)
            }
        }
        if (editingId == id) editingId = null
    }

    /** The keyboard's Done key: saves this row and opens a new one next to it, like Apple's Reminders. */
    fun enter(id: Long, text: String) {
        val current = store.items.firstOrNull { it.id == id } ?: return
        if (text.isBlank()) {
            focus.clearFocus()
            return
        }
        finishEditing(id, text)
        editingId = store.add("", current.parentId, id)
    }

    BackHandler(selecting) { stopSelecting() }

    val lines = remember(items, editingId, showCompleted) {
        buildList<Line> {
            val subsOf = items.filter { it.parentId != null }.groupBy { it.parentId }
            val mains = items.filter { it.parentId == null }
            fun addGroup(main: Item) {
                val subs = subsOf[main.id].orEmpty()
                add(TaskLine(main, false, subs.size, subs.count { it.done }))
                subs.forEach { add(TaskLine(it, true, 0, 0)) }
                val editingHere = editingId != null && (editingId == main.id || subs.any { it.id == editingId })
                if (!main.done && editingHere) add(AddSubLine(main.id, subs.lastOrNull()?.id))
            }
            mains.filter { !it.done }.forEach { addGroup(it) }
            val finished = mains.filter { it.done }
            if (finished.isNotEmpty()) {
                add(CompletedLine(finished.size, showCompleted))
                if (showCompleted) finished.forEach { addGroup(it) }
            }
        }
    }

    // Keep the row being typed in on screen.
    LaunchedEffect(editingId, lines.size) {
        val at = lines.indexOfFirst { it.key == "t$editingId" }
        if (at >= 0) listState.animateScrollToItem(at)
    }

    Scaffold(
        modifier = Modifier.systemBarsPadding().imePadding(),
        containerColor = p.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { stopSelecting() }) { Text("Cancel", color = p.blue, fontSize = 17.sp) }
                    Text(
                        if (selected.isEmpty()) "Select tasks" else "${selected.size} Selected",
                        Modifier.weight(1f),
                        color = p.label,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TextButton(onClick = { deleteSelected() }, enabled = selected.isNotEmpty()) {
                        Text("Delete", color = if (selected.isEmpty()) p.secondary else p.danger, fontSize = 17.sp)
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Tasks", Modifier.weight(1f), color = p.blue, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = p.blue) }
                }
            }
        },
        bottomBar = {
            if (!selecting) {
                Row(
                    Modifier.fillMaxWidth().background(p.background).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .clickable {
                                editingId = store.add("", null, null)
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(p.blue), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = Color.White)
                        }
                        Text("New Task", color = p.blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No tasks", color = p.secondary, fontSize = 20.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
                items(lines, key = { it.key }) { line ->
                    when (line) {
                        is TaskLine -> TaskRow(
                            line = line,
                            editing = editingId == line.item.id && !selecting,
                            selecting = selecting,
                            selected = line.item.id in selected,
                            onToggle = { store.toggle(line.item.id) },
                            onStartEdit = { editingId = line.item.id },
                            onSelect = { select(line.item.id) },
                            onFocusLost = { finishEditing(line.item.id, it) },
                            onEnter = { enter(line.item.id, it) },
                        )
                        is AddSubLine -> AddSubRow {
                            editingId = store.add("", line.parentId, line.lastSubId)
                        }
                        is CompletedLine -> CompletedRow(line) { showCompleted = !showCompleted }
                    }
                }
            }
        }
    }
}

/** The ring you tick. Filled blue with a check when done. */
@Composable
private fun Ring(on: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(if (on) Modifier.background(p.blue) else Modifier.border(1.5.dp, p.ring, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.White)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    line: TaskLine,
    editing: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onStartEdit: () -> Unit,
    onSelect: () -> Unit,
    onFocusLost: (String) -> Unit,
    onEnter: (String) -> Unit,
) {
    val p = LocalPalette.current
    val item = line.item
    val startPad = if (line.isSub) 40.dp else 8.dp
    val textStart = startPad + 44.dp + 4.dp
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (selecting) Modifier.combinedClickable(onClick = onSelect, onLongClick = onSelect) else Modifier)
                .heightIn(min = 48.dp)
                .padding(start = startPad, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .then(
                        if (selecting) Modifier else Modifier.combinedClickable(
                            role = Role.Checkbox,
                            onClickLabel = if (item.done) "Mark not done" else "Mark done",
                            onClick = onToggle,
                            onLongClick = onSelect,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) { Ring(if (selecting) selected else item.done) }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                    .then(
                        if (selecting || editing) Modifier
                        else Modifier.combinedClickable(onClick = onStartEdit, onLongClick = onSelect),
                    ),
            ) {
                if (editing) {
                    TitleField(
                        initial = item.title,
                        placeholder = if (line.isSub) "New subtask" else "New task",
                        onFocusLost = onFocusLost,
                        onEnter = onEnter,
                    )
                } else {
                    Text(
                        item.title,
                        color = if (item.done) p.secondary else p.label,
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                    )
                }
                if (line.subs > 0) {
                    Text("${line.subsDone} of ${line.subs}", color = p.secondary, fontSize = 13.sp)
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = textStart), thickness = 0.5.dp, color = p.separator)
    }
}

@Composable
private fun TitleField(
    initial: String,
    placeholder: String,
    onFocusLost: (String) -> Unit,
    onEnter: (String) -> Unit,
) {
    val p = LocalPalette.current
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focusRequester = remember { FocusRequester() }
    var wasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { if (it.text.length <= Checklist.MAX_TITLE) value = it },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    wasFocused = true
                } else if (wasFocused) {
                    wasFocused = false
                    onFocusLost(value.text)
                }
            },
        textStyle = TextStyle(color = p.label, fontSize = 17.sp, lineHeight = 22.sp),
        cursorBrush = SolidColor(p.blue),
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEnter(value.text) }),
        decorationBox = { field ->
            Box {
                if (value.text.isEmpty()) Text(placeholder, color = p.secondary, fontSize = 17.sp)
                field()
            }
        },
    )
}

@Composable
private fun AddSubRow(onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Add subtask",
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(start = 40.dp + 44.dp + 4.dp, end = 16.dp)
                .padding(vertical = 13.dp),
            color = p.blue,
            fontSize = 17.sp,
        )
        HorizontalDivider(Modifier.padding(start = 40.dp + 44.dp + 4.dp), thickness = 0.5.dp, color = p.separator)
    }
}

@Composable
private fun CompletedRow(line: CompletedLine, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Completed · ${line.count}",
            Modifier.weight(1f),
            color = p.secondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            if (line.open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            if (line.open) "Hide completed" else "Show completed",
            tint = p.secondary,
        )
    }
}
