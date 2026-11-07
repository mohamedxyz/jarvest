package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.CalendarBlock
import dev.goaltracker.core.EventRepeat
import dev.goaltracker.core.NewEvent
import dev.goaltracker.core.NewTask

private val QUICK_LENGTHS = listOf(15, 30, 45, 60, 90, 120)

/**
 * The small sheet that opens when an empty spot on the day is tapped. It makes an event or a task at
 * that time, or gives a time to something already planned for the day without one. Like the rest of
 * the calendar it only plans: nothing here touches score, XP, streaks or rewards.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    controller: AppController,
    date: String,
    initialStart: Int,
    initialMinutes: Int,
    anytime: List<CalendarBlock>,
    onPlace: (CalendarBlock, start: Int, minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var start by rememberSaveable { mutableStateOf(initialStart) }
    var minutes by rememberSaveable { mutableStateOf(initialMinutes) }
    var title by rememberSaveable { mutableStateOf("") }
    var choice by rememberSaveable { mutableStateOf(0) }
    var pickingTime by remember { mutableStateOf(false) }
    val options = listOf("Event", "Task", "Schedule existing")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(formatDate(date), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { pickingTime = true }) {
                Text("${Reminders.formatMinutes(start)} to ${Reminders.formatMinutes(minOf(start + minutes, DAY_MINUTES))}")
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, text ->
                    SegmentedButton(
                        selected = choice == i,
                        onClick = { choice = i },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(text, maxLines = 1) }
                }
            }
            if (choice == 2) {
                if (anytime.isEmpty()) {
                    Text("Nothing is waiting for a time today.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        anytime.forEach { block ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onPlace(block, start, minOf(maxOf(block.minutes.toInt(), MIN_BLOCK_MINUTES), DAY_MINUTES - start))
                                    onDismiss()
                                },
                                label = { Text((block.icon?.let { "$it " } ?: "") + block.title, maxLines = 1) },
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    title, { title = it.take(120) },
                    Modifier.fillMaxWidth().autoFocus(),
                    label = { Text(if (choice == 0) "Event name" else "Task name") },
                    singleLine = true,
                )
                Text("Length", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_LENGTHS.forEach { m ->
                        FilterChip(minutes == m, { minutes = m }, label = { Text("$m min") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = title.isNotBlank(),
                        onClick = {
                            val length = minOf(minutes, DAY_MINUTES - start).coerceAtLeast(MIN_BLOCK_MINUTES)
                            val ok = if (choice == 0) {
                                controller.act {
                                    createEvent(NewEvent(title.trim(), date, start.toUInt(), length.toUInt(), EventRepeat.NONE, null, null, null))
                                }
                            } else {
                                val id = controller.change {
                                    createTask(NewTask(title.trim(), "", date, controller.defaultPriority, null, null, emptyList(), null), controller.today)
                                }
                                id != null && controller.act { setTaskTime(id, start.toUInt(), length.toUInt()) }
                            }
                            if (ok) onDismiss()
                        },
                    ) { Text(if (choice == 0) "Add event" else "Add task") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
    if (pickingTime) {
        TimePickerDialog(
            initialMinutes = start,
            onPick = { start = it.coerceAtMost(DAY_MINUTES - MIN_BLOCK_MINUTES); pickingTime = false },
            onDismiss = { pickingTime = false },
        )
    }
}
