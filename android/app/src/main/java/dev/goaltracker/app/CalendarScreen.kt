package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.CalendarBlock
import dev.goaltracker.core.CalendarKind
import dev.goaltracker.core.EventRepeat
import dev.goaltracker.core.NewEvent
import dev.goaltracker.core.TaskEdit
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

internal val DURATIONS = listOf(15, 30, 45, 60, 90, 120)

private fun CalendarBlock.end() = startMinutes!!.toInt() + minutes.toInt()

/** A block with the column it sits in, so blocks at the same time sit side by side. */
internal data class Placed(val block: CalendarBlock, val lane: Int, val lanes: Int)

/** Gives every timed block a lane. Blocks that overlap share the width of the row. */
internal fun place(blocks: List<CalendarBlock>): List<Placed> {
    val timed = blocks.filter { it.startMinutes != null }.sortedBy { it.startMinutes!!.toInt() }
    val out = mutableListOf<Placed>()
    var i = 0
    while (i < timed.size) {
        val cluster = mutableListOf(timed[i])
        var end = timed[i].end()
        var j = i + 1
        while (j < timed.size && timed[j].startMinutes!!.toInt() < end) {
            cluster += timed[j]
            end = maxOf(end, timed[j].end())
            j++
        }
        val laneEnds = mutableListOf<Int>()
        val lanes = IntArray(cluster.size)
        cluster.forEachIndexed { index, block ->
            val start = block.startMinutes!!.toInt()
            var lane = laneEnds.indexOfFirst { it <= start }
            if (lane == -1) {
                laneEnds += 0
                lane = laneEnds.lastIndex
            }
            laneEnds[lane] = block.end()
            lanes[index] = lane
        }
        cluster.forEachIndexed { index, block -> out += Placed(block, lanes[index], laneEnds.size) }
        i = j
    }
    return out
}

@Composable
internal fun blockColor(kind: CalendarKind) = when (kind) {
    CalendarKind.EVENT -> MaterialTheme.colorScheme.tertiaryContainer
    CalendarKind.TASK -> MaterialTheme.colorScheme.primaryContainer
    CalendarKind.GOAL -> MaterialTheme.colorScheme.secondaryContainer
}

internal fun kindName(kind: CalendarKind) = when (kind) {
    CalendarKind.EVENT -> "Event"
    CalendarKind.TASK -> "Task"
    CalendarKind.GOAL -> "Goal or habit"
}

internal fun timeText(block: CalendarBlock): String {
    val start = block.startMinutes ?: return "Anytime · ${block.minutes} min"
    return "${Reminders.formatMinutes(start.toInt())} to ${Reminders.formatMinutes(block.end())}"
}

/**
 * The calendar tab: a slim header, a short week strip, a row of things waiting for a time, and the
 * day hour by hour taking the rest of the screen. Events, tasks due that day, and goals and habits
 * planned that day show up as blocks. Tap an empty spot to add; press and hold a block to move it;
 * drag it onto a day above to change the day. It only plans: nothing here changes the day score, XP,
 * streaks or rewards.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(
    controller: AppController,
    onEditEvent: (String) -> Unit,
    onEditTask: (String) -> Unit,
    onOpenGoal: (String) -> Unit,
    onFocus: (String, String) -> Unit,
) {
    val date = controller.calendarDate
    val day = LocalDate.parse(date)
    val weekStart = day.minusDays((day.dayOfWeek.value - 1).toLong())
    val blocks = remember(controller.version, date) { controller.read(emptyList()) { calendarDay(date) } }
    val counts = remember(controller.version, weekStart) {
        (0..6).map { i -> controller.read(0) { calendarDay(weekStart.plusDays(i.toLong()).toString()).size } }
    }
    var selected by remember { mutableStateOf<CalendarBlock?>(null) }
    var showMonth by rememberSaveable { mutableStateOf(false) }
    var showSuggest by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // The spot picked for a new item: its start and length in minutes.
    var quickAdd by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val anytime = blocks.filter { it.startMinutes == null }
    val placed = remember(blocks) { place(blocks) }
    val drag = remember { CalendarDragState() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // What a drop means. Over a day in the strip it changes the day; over the grid it sets the time.
    fun dropOnDay(): String? = drag.dayUnderPointer()?.let { weekStart.plusDays(it.toLong()).toString() }
    fun dropMove(block: CalendarBlock, start: Int) {
        val otherDay = dropOnDay()?.takeIf { it != date }
        when {
            otherDay != null -> commitBlock(controller, block, otherDay, block.startMinutes?.toInt(), block.minutes.toInt())
            drag.overGrid() -> commitBlock(controller, block, null, start, drag.carriedMinutes(block))
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .clickable { showMonth = !showMonth }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .label("${day.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.year}. ${if (showMonth) "Show week" else "Show month"}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${day.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.year}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                IconButton(onClick = { controller.calendarDate = day.minusWeeks(1).toString() }, Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week")
                }
                IconButton(onClick = { controller.calendarDate = day.plusWeeks(1).toString() }, Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week")
                }
                TextButton(onClick = { controller.calendarDate = controller.today }) { Text(stringResource(R.string.ui_today)) }
                Box {
                    IconButton(onClick = { menuOpen = true }, Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_suggest_times)) },
                            onClick = { menuOpen = false; showSuggest = true },
                        )
                    }
                }
            }
            if (showSuggest) SuggestTimesDialog(controller, date) { showSuggest = false }
            if (showMonth) MonthOverview(controller, day) { picked ->
                controller.calendarDate = picked
                showMonth = false
            }

            val hoverDay by remember { derivedStateOf { if (drag.carrying) drag.dayUnderPointer() else null } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..6).forEach { i ->
                    val d = weekStart.plusDays(i.toLong())
                    val isSelected = d == day
                    val isToday = d.toString() == controller.today
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.weight(1f)
                            .onGloballyPositioned { drag.dayRects[i] = it.boundsInRoot() }
                            .clip(shape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow)
                            .then(if (hoverDay == i) Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, shape) else Modifier)
                            .clickable { controller.calendarDate = d.toString() }
                            .padding(vertical = 4.dp)
                            .label("${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${d.dayOfMonth}, ${counts[i]} planned"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = textColor)
                        Text(
                            "${d.dayOfMonth}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isToday && !isSelected) MaterialTheme.colorScheme.primary else textColor,
                        )
                        // A dot shows that something is planned that day.
                        Box(
                            Modifier.size(5.dp).clip(CircleShape)
                                .background(if (counts[i] > 0) textColor else androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
            }

            if (anytime.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_anytime), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(
                        Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(anytime, key = { it.kind.name + it.id }) { block ->
                            BlockChip(block, drag, onClick = { selected = block }, onDrop = { dropChip(controller, block, drag, ::dropOnDay) })
                        }
                    }
                }
            }
            if (blocks.isEmpty()) {
                Text(
                    "Nothing planned. Tap a time to add something.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DayGrid(
                date = date,
                isToday = date == controller.today,
                blocks = blocks,
                placed = placed,
                drag = drag,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onSelect = { selected = it },
                onQuickAdd = { start, minutes -> quickAdd = start to minutes },
                onMoveEnd = ::dropMove,
                onResizeEnd = { block, minutes ->
                    commitBlock(controller, block, null, block.startMinutes?.toInt(), minutes)
                },
                onNudge = { block, startDelta, minutesDelta ->
                    val start = block.startMinutes?.toInt() ?: return@DayGrid
                    val minutes = (block.minutes.toInt() + minutesDelta).coerceAtLeast(MIN_BLOCK_MINUTES)
                    commitBlock(controller, block, null, clampStart(start + startDelta, minutes), minutes)
                },
            )
        }

        // The copy of an Anytime chip that follows the finger.
        val carried = drag.block
        if (drag.mode == DragMode.CHIP && carried != null) {
            val label = if (drag.overGrid() && drag.dayUnderPointer() == null) {
                val s = drag.liveStart()
                "  ${Reminders.formatMinutes(s)}"
            } else ""
            Surface(
                Modifier.offset { IntOffset((drag.pointer.x - origin.x).roundToInt(), (drag.pointer.y - origin.y).roundToInt() - 60) },
                shape = RoundedCornerShape(12.dp),
                color = blockColor(carried.kind),
                shadowElevation = 10.dp,
            ) {
                Text(
                    (carried.icon?.let { "$it " } ?: "") + carried.title + label,
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }

    selected?.let { block ->
        BlockSheet(controller, block, onDismiss = { selected = null }, onEditEvent, onEditTask, onOpenGoal, onFocus)
    }
    quickAdd?.let { (start, minutes) ->
        QuickAddSheet(
            controller, date, start, minutes, anytime,
            onPlace = { block, s, m -> commitBlock(controller, block, null, s, m) },
            onDismiss = { quickAdd = null },
        )
    }
}

/** Ends a chip drag: onto a day in the strip it changes the day, onto the grid it gets that time. */
private fun dropChip(controller: AppController, block: CalendarBlock, drag: CalendarDragState, dayUnder: () -> String?) {
    val otherDay = dayUnder()?.takeIf { it != controller.calendarDate }
    val start = drag.liveStart()
    val length = drag.carriedMinutes(block)
    when {
        otherDay != null -> commitBlock(controller, block, otherDay, null, block.minutes.toInt())
        drag.overGrid() -> commitBlock(controller, block, null, start, length)
    }
}

/** A thing waiting for a time. Tap to open it; press, hold and drag it onto the day to give it one. */
@Composable
private fun BlockChip(block: CalendarBlock, drag: CalendarDragState, onClick: () -> Unit, onDrop: () -> Unit) {
    val view = LocalView.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = blockColor(block.kind),
        modifier = Modifier
            .onGloballyPositioned { coords = it }
            .pointerInput(block.id, block.kind) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        drag.block = block
                        drag.mode = DragMode.CHIP
                        drag.grabMinutes = drag.carriedMinutes(block) / 2f
                        drag.pointer = (coords?.positionInRoot() ?: Offset.Zero) + o
                        if (Feel.haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        drag.pointer = (coords?.positionInRoot() ?: Offset.Zero) + change.position
                    },
                    onDragEnd = {
                        onDrop()
                        drag.reset()
                    },
                    onDragCancel = { drag.reset() },
                )
            }
            .label("${kindName(block.kind)} ${block.title}, anytime. Press and hold, then drag onto the day to give it a time."),
    ) {
        Text(
            (block.icon?.let { "$it " } ?: "") + block.title,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (block.done) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What a tap on a block offers: finish a task, set its time, or open it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BlockSheet(
    controller: AppController,
    block: CalendarBlock,
    onDismiss: () -> Unit,
    onEditEvent: (String) -> Unit,
    onEditTask: (String) -> Unit,
    onOpenGoal: (String) -> Unit,
    onFocus: (String, String) -> Unit,
) {
    var pickingTime by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text((block.icon?.let { "$it " } ?: "") + block.title, style = MaterialTheme.typography.titleLarge)
            Text("${kindName(block.kind)} · ${timeText(block)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (block.kind) {
                CalendarKind.EVENT -> Button(onClick = { onDismiss(); onEditEvent(block.id) }) { Text(stringResource(R.string.ui_edit_event)) }
                CalendarKind.TASK -> {
                    Button(onClick = {
                        controller.act { toggleTask(block.id, controller.today) }
                        onDismiss()
                    }) { Text(if (block.done) "Mark not done" else "Mark done") }
                    OutlinedButton(onClick = { onDismiss(); onEditTask(block.id) }) { Text(stringResource(R.string.ui_open_task)) }
                }
                CalendarKind.GOAL -> OutlinedButton(onClick = { onDismiss(); onOpenGoal(block.id) }) { Text(stringResource(R.string.ui_open_goal)) }
            }
            if (block.kind != CalendarKind.EVENT) {
                OutlinedButton(onClick = { onDismiss(); onFocus(if (block.kind == CalendarKind.GOAL) "goal" else "task", block.id) }) {
                    Text(stringResource(R.string.ui_start_a_focus_timer))
                }
                Text(stringResource(R.string.ui_time_block), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickingTime = true }) {
                        Text(block.startMinutes?.let { "Starts ${Reminders.formatMinutes(it.toInt())}" } ?: "Set a start time")
                    }
                    if (block.startMinutes != null) {
                        TextButton(onClick = { applyBlock(controller, block, null, null); onDismiss() }) { Text(stringResource(R.string.ui_clear)) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DURATIONS.forEach { m ->
                        FilterChip(
                            selected = block.minutes.toInt() == m,
                            // A length needs a start time, so without one it starts at 9:00.
                            onClick = { applyBlock(controller, block, block.startMinutes?.toInt() ?: (9 * 60), m); onDismiss() },
                            label = { Text("$m min") },
                        )
                    }
                }
            }
        }
    }
    if (pickingTime) {
        TimePickerDialog(
            initialMinutes = block.startMinutes?.toInt() ?: (9 * 60),
            onPick = { start ->
                pickingTime = false
                applyBlock(controller, block, start, block.minutes.toInt())
                onDismiss()
            },
            onDismiss = { pickingTime = false },
        )
    }
}

/**
 * Saves a new day and/or time for a block and offers Undo. [newDate] is null when the day stays.
 * Goals and repeating things follow their own schedule, so they can't change day here.
 */
internal fun commitBlock(controller: AppController, block: CalendarBlock, newDate: String?, start: Int?, minutes: Int?) {
    val oldStart = block.startMinutes?.toInt()
    val oldMinutes = block.minutes.toInt()
    if (newDate == null && start == oldStart && minutes == oldMinutes) return
    val what = when {
        newDate != null -> "Moved \"${block.title}\" to ${formatDate(newDate)}"
        start == null -> "\"${block.title}\" has no set time now"
        minutes != oldMinutes && start == oldStart -> "\"${block.title}\" is now ${minutes} min"
        else -> "Moved \"${block.title}\" to ${Reminders.formatMinutes(start)}"
    }
    when (block.kind) {
        CalendarKind.EVENT -> {
            val e = controller.read(null) { getEvent(block.id) } ?: return
            if (newDate != null && e.repeat != EventRepeat.NONE) {
                controller.message = "A repeating event can't move to one day. Open it to change it."
                return
            }
            fun write(date: String, s: Int, m: Int) = controller.act {
                updateEvent(e.id, NewEvent(e.title, date, s.toUInt(), m.toUInt(), e.repeat, e.weekdays, e.untilDate, e.remindMinutes))
            }
            if (write(newDate ?: e.date, start ?: oldStart ?: (9 * 60), minutes ?: oldMinutes)) {
                controller.undo = UndoOffer(what) { write(e.date, oldStart ?: (9 * 60), oldMinutes) }
            }
        }
        CalendarKind.TASK -> {
            val t = controller.read(null) { getTask(block.id, controller.today) } ?: return
            if (newDate != null && t.repeat != null) {
                controller.message = "A repeating task can't move to one day. Open it to change it."
                return
            }
            // A task with no start time keeps its length when it only changes day, so the time is
            // written only when it really changed.
            val timeChanged = start != oldStart || (start != null && minutes != oldMinutes)
            fun write(due: String?, s: Int?, m: Int?, changeDay: Boolean, changeTime: Boolean) = controller.act {
                if (changeDay) updateTask(t.id, TaskEdit(t.title, t.notes, due, t.priority, t.tag, t.repeat, t.goalId))
                if (changeTime) setTaskTime(t.id, s?.toUInt(), m?.toUInt())
            }
            val ok = write(newDate ?: t.due, start, minutes, newDate != null, timeChanged)
            if (ok) controller.undo = UndoOffer(what) { write(t.due, oldStart, oldMinutes.takeIf { oldStart != null }, newDate != null, timeChanged) }
        }
        CalendarKind.GOAL -> {
            if (newDate != null) {
                controller.message = "A goal or habit follows its own schedule, so it can't move to another day."
                return
            }
            fun write(s: Int?, m: Int?) = controller.act { setGoalBlock(block.id, s?.toUInt(), m?.toUInt()) }
            if (write(start, minutes)) controller.undo = UndoOffer(what) { write(oldStart, oldMinutes.takeIf { oldStart != null }) }
        }
    }
}

private fun applyBlock(controller: AppController, block: CalendarBlock, start: Int?, minutes: Int?) {
    controller.act {
        when (block.kind) {
            CalendarKind.TASK -> setTaskTime(block.id, start?.toUInt(), minutes?.toUInt())
            CalendarKind.GOAL -> setGoalBlock(block.id, start?.toUInt(), minutes?.toUInt())
            CalendarKind.EVENT -> Unit
        }
    }
}

private val WEEKDAY_LETTERS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Make or change an event: a title, a day, a start time, a length and an optional repeat. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditorScreen(controller: AppController, eventId: String?, onDone: () -> Unit) {
    val existing = remember(eventId) { eventId?.let { controller.read(null) { getEvent(it) } } }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: controller.calendarDate) }
    var start by rememberSaveable { mutableStateOf(existing?.startMinutes?.toInt() ?: (9 * 60)) }
    var minutes by rememberSaveable { mutableStateOf(existing?.minutes?.toInt() ?: 60) }
    var repeat by rememberSaveable { mutableStateOf((existing?.repeat ?: EventRepeat.NONE).name) }
    var mask by rememberSaveable {
        mutableStateOf(existing?.weekdays?.toInt() ?: (1 shl (LocalDate.parse(date).dayOfWeek.value - 1)))
    }
    var until by rememberSaveable { mutableStateOf(existing?.untilDate) }
    var remind by rememberSaveable { mutableStateOf(existing?.remindMinutes?.toInt()) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingUntil by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            title, { title = it.take(120) }, Modifier.fillMaxWidth().then(if (existing == null) Modifier.autoFocus() else Modifier),
            label = { Text(stringResource(R.string.ui_event_name)) }, singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingDate = true }) { Text(formatDate(date)) }
            OutlinedButton(onClick = { pickingTime = true }) { Text("Starts ${Reminders.formatMinutes(start)}") }
        }
        Text(stringResource(R.string.ui_length), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (DURATIONS + listOf(180, 240)).forEach { m ->
                FilterChip(minutes == m, { minutes = m }, label = { Text(if (m >= 60 && m % 60 == 0) "${m / 60} h" else "$m min") })
            }
        }
        Text(stringResource(R.string.ui_repeat), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("NONE" to "Does not repeat", "DAILY" to "Every day", "WEEKLY" to "Weekly").forEach { (value, text) ->
                FilterChip(repeat == value, { repeat = value }, label = { Text(text) })
            }
        }
        if (repeat == "WEEKLY") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WEEKDAY_LETTERS.forEachIndexed { i, name ->
                    val bit = 1 shl i
                    FilterChip(mask and bit != 0, { mask = mask xor bit }, label = { Text(name) })
                }
            }
        }
        if (repeat != "NONE") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickingUntil = true }) {
                    Text(until?.let { "Ends ${formatDate(it)}" } ?: "Repeats forever")
                }
                if (until != null) TextButton(onClick = { until = null }) { Text(stringResource(R.string.ui_clear)) }
            }
        }
        Text(stringResource(R.string.ui_remind_me), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Int?>(null, 0, 5, 10, 30, 60).forEach { m ->
                FilterChip(
                    remind == m, { remind = m },
                    label = { Text(when (m) { null -> "No reminder"; 0 -> "At the start"; 60 -> "1 h before"; else -> "$m min before" }) },
                )
            }
        }
        Text(
            "Events are only for planning your time. They never change your score, XP, streaks or treats.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = title.isNotBlank(),
            onClick = {
                val event = NewEvent(
                    title, date, start.toUInt(), minutes.toUInt(), EventRepeat.valueOf(repeat),
                    if (repeat == "WEEKLY") mask.toUByte() else null,
                    if (repeat == "NONE") null else until,
                    remind?.toUInt(),
                )
                val ok = if (eventId == null) controller.act { createEvent(event) } else controller.act { updateEvent(eventId, event) }
                if (ok) {
                    controller.calendarDate = date
                    onDone()
                }
            },
        ) { Text(if (eventId == null) "Add event" else "Save changes") }
        if (eventId != null && repeat != "NONE") {
            TextButton(onClick = { confirmSkip = true }) { Text("Skip ${formatDate(controller.calendarDate)} only") }
        }
        if (eventId != null) {
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.ui_delete_event), color = MaterialTheme.colorScheme.error) }
        }
    }

    if (pickingDate) {
        DayPickerDialog("2000-01-01", "2100-12-31", onPick = { date = it; pickingDate = false }, onDismiss = { pickingDate = false })
    }
    if (pickingUntil) {
        DayPickerDialog(date, "2100-12-31", onPick = { until = it; pickingUntil = false }, onDismiss = { pickingUntil = false })
    }
    if (confirmSkip && eventId != null) {
        ConfirmDialog(
            title = "Skip this day?",
            body = "\"$title\" will not happen on ${formatDate(controller.calendarDate)}. Other days stay.",
            confirm = "Skip this day",
            onConfirm = { confirmSkip = false; if (controller.act { skipEventDay(eventId, controller.calendarDate) }) onDone() },
            onDismiss = { confirmSkip = false },
        )
    }
    if (pickingTime) {
        TimePickerDialog(start, onPick = { start = it; pickingTime = false }, onDismiss = { pickingTime = false })
    }
    if (confirmDelete && eventId != null) {
        ConfirmDialog(
            title = "Delete this event?",
            body = if (repeat == "NONE") "\"$title\" is removed from your calendar." else "\"$title\" and every repeat of it are removed.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; if (controller.act { deleteEvent(eventId) }) onDone() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A month grid. A dot marks a day with something planned. Tap a day to open it. */
@Composable
private fun MonthOverview(controller: AppController, day: LocalDate, onPick: (String) -> Unit) {
    val first = day.withDayOfMonth(1)
    val lead = first.dayOfWeek.value - 1
    val gridStart = first.minusDays(lead.toLong())
    val marks = remember(controller.version, first) {
        controller.read(emptyList()) { calendarMonth(gridStart.toString(), 42u) }
    }.associate { it.date to it.count.toInt() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        (0 until 6).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { col ->
                    val d = gridStart.plusDays((row * 7 + col).toLong())
                    val inMonth = d.month == first.month
                    val isSelected = d == day
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onPick(d.toString()) }
                            .padding(vertical = 6.dp)
                            .label("${d.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${d.dayOfMonth}, ${marks[d.toString()] ?: 0} planned"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            inMonth -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.outline
                        }
                        Text("${d.dayOfMonth}", style = MaterialTheme.typography.bodyMedium, color = color)
                        Box(
                            Modifier.padding(top = 2.dp).size(5.dp).clip(CircleShape)
                                .background(if ((marks[d.toString()] ?: 0) > 0) color else androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}
