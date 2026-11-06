package dev.goaltracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.CalendarBlock
import dev.goaltracker.core.CalendarKind
import dev.goaltracker.core.EventRepeat
import dev.goaltracker.core.NewEvent
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private val HOUR_HEIGHT = 56.dp
private val LABEL_WIDTH = 76.dp
private val DURATIONS = listOf(15, 30, 45, 60, 90, 120)

private fun CalendarBlock.end() = startMinutes!!.toInt() + minutes.toInt()

/** A block with the column it sits in, so blocks at the same time sit side by side. */
private data class Placed(val block: CalendarBlock, val lane: Int, val lanes: Int)

/** Gives every timed block a lane. Blocks that overlap share the width of the row. */
private fun place(blocks: List<CalendarBlock>): List<Placed> {
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
private fun blockColor(kind: CalendarKind) = when (kind) {
    CalendarKind.EVENT -> MaterialTheme.colorScheme.tertiaryContainer
    CalendarKind.TASK -> MaterialTheme.colorScheme.primaryContainer
    CalendarKind.GOAL -> MaterialTheme.colorScheme.secondaryContainer
}

private fun kindName(kind: CalendarKind) = when (kind) {
    CalendarKind.EVENT -> "Event"
    CalendarKind.TASK -> "Task"
    CalendarKind.GOAL -> "Goal or habit"
}

private fun timeText(block: CalendarBlock): String {
    val start = block.startMinutes ?: return "Anytime · ${block.minutes} min"
    return "${Reminders.formatMinutes(start.toInt())} to ${Reminders.formatMinutes(block.end())}"
}

/**
 * The calendar tab: a week strip to pick a day, and that day hour by hour. Events, tasks that are
 * due that day, and goals and habits planned that day all show up as blocks. It only plans: nothing
 * here changes the day score, XP, streaks or rewards.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(
    controller: AppController,
    onEditEvent: (String) -> Unit,
    onEditTask: (String) -> Unit,
    onOpenGoal: (String) -> Unit,
) {
    val date = controller.calendarDate
    val day = LocalDate.parse(date)
    val weekStart = day.minusDays((day.dayOfWeek.value - 1).toLong())
    val blocks = remember(controller.version, date) { controller.read(emptyList()) { calendarDay(date) } }
    val counts = remember(controller.version, weekStart) {
        (0..6).map { i -> controller.read(0) { calendarDay(weekStart.plusDays(i.toLong()).toString()).size } }
    }
    var selected by remember { mutableStateOf<CalendarBlock?>(null) }
    val anytime = blocks.filter { it.startMinutes == null }
    val placed = remember(blocks) { place(blocks) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.calendarDate = day.minusWeeks(1).toString() }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week")
            }
            Text(
                "${day.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.year}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { controller.calendarDate = day.plusWeeks(1).toString() }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week")
            }
            TextButton(onClick = { controller.calendarDate = controller.today }) { Text("Today") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (0..6).forEach { i ->
                val d = weekStart.plusDays(i.toLong())
                val isSelected = d == day
                val isToday = d.toString() == controller.today
                Column(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { controller.calendarDate = d.toString() }
                        .padding(vertical = 8.dp)
                        .label("${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${d.dayOfMonth}, ${counts[i]} planned"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = textColor)
                    Text(
                        "${d.dayOfMonth}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isToday && !isSelected) MaterialTheme.colorScheme.primary else textColor,
                    )
                    // A dot shows that something is planned that day.
                    Box(
                        Modifier.padding(top = 2.dp).size(6.dp).clip(CircleShape)
                            .background(if (counts[i] > 0) textColor else androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }

        if (anytime.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Anytime", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    anytime.forEach { block -> BlockChip(block) { selected = block } }
                }
            }
        }
        if (blocks.isEmpty()) {
            Text(
                "Nothing planned. Tap New event, or give a task a due day.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Opens near 7:00, or near the first block of the day.
        val firstHour = (blocks.mapNotNull { it.startMinutes?.toInt() }.minOrNull()?.div(60) ?: 7).coerceAtLeast(0)
        val startPx = with(LocalDensity.current) { (HOUR_HEIGHT * maxOf(firstHour - 1, 0)).roundToPx() }
        val scroll = rememberScrollState(startPx)
        Box(Modifier.weight(1f).verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24)) {
                Column {
                    (0..23).forEach { hour ->
                        Row(Modifier.height(HOUR_HEIGHT)) {
                            Text(
                                Reminders.formatMinutes(hour * 60),
                                Modifier.width(LABEL_WIDTH).padding(start = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(Modifier.weight(1f).padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                BoxWithConstraints(Modifier.padding(start = LABEL_WIDTH, end = 8.dp).fillMaxSize()) {
                    val full: Dp = maxWidth
                    placed.forEach { p ->
                        val start = p.block.startMinutes!!.toInt()
                        val laneWidth = full / p.lanes
                        TimedBlock(
                            p.block,
                            Modifier
                                .offset(x = laneWidth * p.lane, y = HOUR_HEIGHT * (start / 60f) + 8.dp)
                                .width(laneWidth - 2.dp)
                                .height(maxOf(HOUR_HEIGHT * (p.block.minutes.toInt() / 60f), 28.dp) - 2.dp),
                        ) { selected = p.block }
                    }
                    if (date == controller.today) {
                        val now = LocalTime.now()
                        Box(
                            Modifier.offset(y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / 60f) + 8.dp)
                                .fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.error),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(72.dp))
    }

    selected?.let { block ->
        BlockSheet(controller, block, onDismiss = { selected = null }, onEditEvent, onEditTask, onOpenGoal)
    }
}

@Composable
private fun BlockChip(block: CalendarBlock, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = blockColor(block.kind)) {
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

@Composable
private fun TimedBlock(block: CalendarBlock, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.label("${kindName(block.kind)} ${block.title}, ${timeText(block)}"),
        shape = RoundedCornerShape(10.dp),
        color = blockColor(block.kind).copy(alpha = if (block.done) 0.6f else 1f),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                (block.icon?.let { "$it " } ?: "") + block.title,
                style = MaterialTheme.typography.labelLarge,
                textDecoration = if (block.done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (block.minutes >= 45u) {
                Text(timeText(block), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
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
) {
    var pickingTime by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text((block.icon?.let { "$it " } ?: "") + block.title, style = MaterialTheme.typography.titleLarge)
            Text("${kindName(block.kind)} · ${timeText(block)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (block.kind) {
                CalendarKind.EVENT -> Button(onClick = { onDismiss(); onEditEvent(block.id) }) { Text("Edit event") }
                CalendarKind.TASK -> {
                    Button(onClick = {
                        controller.act { toggleTask(block.id, controller.today) }
                        onDismiss()
                    }) { Text(if (block.done) "Mark not done" else "Mark done") }
                    OutlinedButton(onClick = { onDismiss(); onEditTask(block.id) }) { Text("Open task") }
                }
                CalendarKind.GOAL -> OutlinedButton(onClick = { onDismiss(); onOpenGoal(block.id) }) { Text("Open goal") }
            }
            if (block.kind != CalendarKind.EVENT) {
                Text("Time block", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickingTime = true }) {
                        Text(block.startMinutes?.let { "Starts ${Reminders.formatMinutes(it.toInt())}" } ?: "Set a start time")
                    }
                    if (block.startMinutes != null) {
                        TextButton(onClick = { applyBlock(controller, block, null, null); onDismiss() }) { Text("Clear") }
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
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            title, { title = it.take(120) }, Modifier.fillMaxWidth().then(if (existing == null) Modifier.autoFocus() else Modifier),
            label = { Text("Event name") }, singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingDate = true }) { Text(formatDate(date)) }
            OutlinedButton(onClick = { pickingTime = true }) { Text("Starts ${Reminders.formatMinutes(start)}") }
        }
        Text("Length", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (DURATIONS + listOf(180, 240)).forEach { m ->
                FilterChip(minutes == m, { minutes = m }, label = { Text(if (m >= 60 && m % 60 == 0) "${m / 60} h" else "$m min") })
            }
        }
        Text("Repeat", style = MaterialTheme.typography.labelLarge)
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
                )
                val ok = if (eventId == null) controller.act { createEvent(event) } else controller.act { updateEvent(eventId, event) }
                if (ok) {
                    controller.calendarDate = date
                    onDone()
                }
            },
        ) { Text(if (eventId == null) "Add event" else "Save changes") }
        if (eventId != null) {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete event", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (pickingDate) {
        DayPickerDialog("2000-01-01", "2100-12-31", onPick = { date = it; pickingDate = false }, onDismiss = { pickingDate = false })
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
