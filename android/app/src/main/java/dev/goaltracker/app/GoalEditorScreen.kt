package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.GoalEdit
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.NewGoal
import dev.goaltracker.core.Period
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private data class Template(
    val title: String,
    val kind: GoalKind,
    val period: Period?,
    val periodTarget: String,
    val totalTarget: String = "",
    val unit: String = "",
    val category: String = "",
)

private val templates = listOf(
    Template("Read", GoalKind.HABIT, Period.DAY, "1", category = "Learning"),
    Template("Exercise", GoalKind.HABIT, Period.WEEK, "3", category = "Health"),
    Template("Meditate", GoalKind.HABIT, Period.DAY, "1", category = "Health"),
    Template("Drink water", GoalKind.NUMERIC, Period.DAY, "8", unit = "glasses", category = "Health"),
    Template("Study", GoalKind.HABIT, Period.WEEK, "5", category = "Learning"),
    Template("Save money", GoalKind.NUMERIC, null, "", totalTarget = "1000", unit = "$", category = "Money"),
)

private val dayChips = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoalEditorScreen(controller: AppController, goalId: String?, onDone: () -> Unit, startKind: GoalKind = GoalKind.HABIT) {
    val existing = remember(goalId) { goalId?.let { id -> controller.read(null) { getGoal(id) } } }
    val categories = remember { controller.read(emptyList()) { categories() } }

    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "") }
    var unit by rememberSaveable { mutableStateOf(existing?.unit ?: "") }
    var deadline by rememberSaveable { mutableStateOf(existing?.deadline) }
    var kind by rememberSaveable { mutableStateOf(startKind) }
    // A quit habit is a daily habit met by not slipping. It has no targets to set.
    var quit by rememberSaveable { mutableStateOf(false) }
    // Step two of the form: folder, deadline, reminder, notes and milestones.
    var moreOpen by rememberSaveable { mutableStateOf(existing != null) }
    var period by rememberSaveable { mutableStateOf<Period?>(Period.WEEK) }
    var periodTarget by rememberSaveable { mutableStateOf("3") }
    var totalTarget by rememberSaveable { mutableStateOf("") }
    var weekdays by rememberSaveable { mutableStateOf(setOf(0, 1, 2, 3, 4, 5, 6)) }
    val milestones = remember { mutableStateListOf<String>() }
    var milestoneText by rememberSaveable { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }
    // Optional time block for the Calendar tab: a start time and a length.
    val savedBlock = remember(existing?.id) { existing?.let { controller.read(null) { goalBlock(it.id) } } }
    var blockStart by rememberSaveable { mutableStateOf(savedBlock?.startMinutes?.toInt()) }
    var blockMinutes by rememberSaveable { mutableStateOf(savedBlock?.minutes?.toInt() ?: 30) }
    var pickingBlock by remember { mutableStateOf(false) }
    var reminder by rememberSaveable {
        mutableStateOf(existing?.let { controller.read(null) { goalReminder(it.id) } }?.toInt())
    }
    var pickingTime by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationPermissionRequest(controller)

    Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            title, { title = it },
            Modifier.fillMaxWidth().then(if (existing == null) Modifier.autoFocus() else Modifier),
            label = { Text(stringResource(R.string.ui_title)) }, singleLine = true,
        )

        if (existing == null) BundleChips(controller, onDone)

        if (existing == null) {
            SectionTitle("Quick start")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { template ->
                    SuggestionChip(onClick = {
                        title = template.title
                        kind = template.kind
                        quit = false
                        period = template.period
                        periodTarget = template.periodTarget
                        totalTarget = template.totalTarget
                        unit = template.unit
                        category = template.category
                        weekdays = setOf(0, 1, 2, 3, 4, 5, 6)
                    }, label = { Text(template.title) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Type")
                InfoHint(
                    "Goal types",
                    "Habit: something you check in on, up to once a day, with a target per day, week or month.\n\n" +
                        "Numeric: add amounts toward a target, such as 5 km a day or 300 pages in total.\n\n" +
                        "Yes/no: a one-off thing you do once.",
                )
            }
            // The fourth choice is a quit habit: a habit kind with the quit flag on.
            val kinds = listOf(GoalKind.HABIT to "Habit", GoalKind.NUMERIC to "Numeric", GoalKind.YES_NO to "Yes/no", GoalKind.HABIT to "Quit")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = kind == value && quit == (index == 3),
                        onClick = {
                            quit = index == 3
                            kind = value
                            if (quit) period = Period.DAY
                            if (value == GoalKind.HABIT && period == null) period = Period.WEEK
                            if (value == GoalKind.NUMERIC) {
                                periodTarget = ""
                                period = null
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    ) { Text(label) }
                }
            }
            Text(
                when (kind) {
                    GoalKind.HABIT -> if (quit) {
                        "Something to stop, such as sugar. Every day without a slip counts. Tap \"I slipped\" if you do it. Quit habits don't draw treats."
                    } else "Check in up to once a day, for example 3 times a week."
                    GoalKind.NUMERIC -> "Add amounts toward a target, for example 300 pages."
                    GoalKind.YES_NO -> "Something to do once, for example file your taxes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (kind != GoalKind.YES_NO && !quit) {
                SectionTitle(if (kind == GoalKind.HABIT) "Target" else "Repeating target (optional)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind == GoalKind.NUMERIC) {
                        FilterChip(period == null, { period = null }, label = { Text(stringResource(R.string.ui_none)) })
                    }
                    listOf(Period.DAY to "Daily", Period.WEEK to "Weekly", Period.MONTH to "Monthly").forEach { (value, label) ->
                        FilterChip(period == value, {
                            period = value
                            if (kind == GoalKind.HABIT && value == Period.DAY) periodTarget = "1"
                        }, label = { Text(label) })
                    }
                }
                if (period != null && !(kind == GoalKind.HABIT && period == Period.DAY)) {
                    OutlinedTextField(
                        periodTarget,
                        { periodTarget = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(if (kind == GoalKind.HABIT) "Times per ${period?.noun()}" else "Amount per ${period?.noun()}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                if (kind == GoalKind.HABIT && period == Period.DAY) {
                    Text(stringResource(R.string.ui_days), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dayChips.forEachIndexed { index, label ->
                            FilterChip(
                                selected = index in weekdays,
                                onClick = { weekdays = if (index in weekdays) weekdays - index else weekdays + index },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (weekdays.isEmpty()) {
                        Text(
                            "Pick at least one day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (kind == GoalKind.NUMERIC) {
                    OutlinedTextField(
                        totalTarget,
                        { totalTarget = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_total_target_optional)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        } else {
            Text(
                "The type and targets can't change, because that would rewrite this goal's history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if ((existing?.kind ?: kind) == GoalKind.NUMERIC) {
            OutlinedTextField(unit, { unit = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_unit_optional_for_example_km)) }, singleLine = true)
        }

        TextButton(onClick = { moreOpen = !moreOpen }) {
            Text(if (moreOpen) "Fewer options" else "More options: folder, deadline, reminder, notes, milestones")
        }
        if (moreOpen) {
        OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_folder_optional)) }, singleLine = true)
        if (categories.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { name -> SuggestionChip({ category = name }, { Text(name) }) }
            }
        }

        if (!(quit && existing == null)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingDate = true }) {
                Text(deadline?.let { "Deadline: ${formatDate(it)}" } ?: "Add deadline")
            }
            if (deadline != null) {
                IconButton(onClick = { deadline = null }) { Icon(Icons.Filled.Close, "Clear deadline") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingTime = true }) {
                Text(reminder?.let { "Reminder: ${Reminders.formatMinutes(it)}" } ?: "Add reminder")
            }
            if (reminder != null) {
                IconButton(onClick = { reminder = null }) { Icon(Icons.Filled.Close, "Clear reminder") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingBlock = true }) {
                Text(blockStart?.let { "Calendar block: ${Reminders.formatMinutes(it)}" } ?: "Add a calendar time block")
            }
            if (blockStart != null) {
                IconButton(onClick = { blockStart = null }) { Icon(Icons.Filled.Close, "Clear time block") }
            }
        }
        if (blockStart != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    FilterChip(selected = blockMinutes == m, onClick = { blockMinutes = m }, label = { Text("$m min") })
                }
            }
        }

        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_notes_optional)) }, minLines = 2)

        if (existing == null && !quit) {
            SectionTitle("Milestones (optional)")
            milestones.forEachIndexed { index, milestone ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. $milestone", Modifier.weight(1f))
                    IconButton(onClick = { milestones.removeAt(index) }) {
                        Icon(Icons.Filled.Close, "Remove $milestone")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val addMilestone = {
                    if (milestoneText.isNotBlank()) {
                        milestones.add(milestoneText.trim())
                        milestoneText = ""
                    }
                }
                OutlinedTextField(
                    milestoneText, { milestoneText = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.ui_milestone)) }, singleLine = true,
                    keyboardOptions = doneKey, keyboardActions = onDone(addMilestone),
                )
                TextButton(enabled = milestoneText.isNotBlank(), onClick = { addMilestone() }) { Text(stringResource(R.string.ui_add)) }
            }
        }
        }

    }

        // Pinned, so saving never requires scrolling past the whole form.
        Button(
            enabled = title.isNotBlank() && (existing != null || quit || kind != GoalKind.HABIT || period != Period.DAY || weekdays.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            onClick = {
                val saved = if (existing != null) {
                    controller.act {
                        updateGoal(existing.id, GoalEdit(title, notes, category.ifBlank { null }, unit.ifBlank { null }, deadline))
                        setGoalReminder(existing.id, reminder?.toUShort())
                        setGoalBlock(existing.id, blockStart?.toUInt(), blockStart?.let { blockMinutes.toUInt() })
                    }
                } else if (quit) {
                    controller.act {
                        val id = createAvoidGoal(title, notes, category.ifBlank { null }, controller.today)
                        if (reminder != null) setGoalReminder(id, reminder!!.toUShort())
                        if (blockStart != null) setGoalBlock(id, blockStart!!.toUInt(), blockMinutes.toUInt())
                    }
                } else {
                    saveNew(controller, title, notes, kind, category, period, periodTarget, totalTarget, unit, deadline, weekdays, milestones.toList() + listOfNotNull(milestoneText.takeIf { it.isNotBlank() }), reminder, blockStart, blockMinutes)
                }
                if (saved) onDone()
            },
        ) { Text(if (existing == null) "Create goal" else "Save") }
    }

    if (pickingBlock) {
        TimePickerDialog(
            initialMinutes = blockStart ?: (9 * 60),
            onPick = { blockStart = it; pickingBlock = false },
            onDismiss = { pickingBlock = false },
        )
    }
    if (pickingTime) {
        TimePickerDialog(
            initialMinutes = reminder,
            onPick = {
                reminder = it
                pickingTime = false
                askNotifications()
            },
            onDismiss = { pickingTime = false },
        )
    }
    if (pickingDate) {
        val initial = deadline?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        deadline = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    pickingDate = false
                }) { Text(stringResource(R.string.ui_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.ui_cancel)) } },
        ) { DatePicker(state) }
    }
}

private fun saveNew(
    controller: AppController,
    title: String,
    notes: String,
    kind: GoalKind,
    category: String,
    period: Period?,
    periodTarget: String,
    totalTarget: String,
    unit: String,
    deadline: String?,
    weekdays: Set<Int>,
    milestones: List<String>,
    reminder: Int?,
    blockStart: Int?,
    blockMinutes: Int,
): Boolean {
    val usesPeriod = kind != GoalKind.YES_NO && period != null
    val parsedPeriodTarget = if (usesPeriod) parseAmount(periodTarget) else null
    if (usesPeriod && parsedPeriodTarget == null) {
        controller.message = "Enter a number for the target"
        return false
    }
    val usesTotal = kind == GoalKind.NUMERIC && totalTarget.isNotBlank()
    val parsedTotal = if (usesTotal) parseAmount(totalTarget) else null
    if (usesTotal && parsedTotal == null) {
        controller.message = "Enter a number for the total target"
        return false
    }
    return controller.act {
        val id = createGoal(
            NewGoal(
                title = title,
                notes = notes,
                kind = kind,
                category = category.ifBlank { null },
                period = if (usesPeriod) period else null,
                periodTarget = parsedPeriodTarget,
                totalTarget = parsedTotal,
                weekdays = weekdays.takeIf { kind == GoalKind.HABIT && period == Period.DAY && it.size < 7 }
                    ?.sumOf { 1 shl it }?.toUByte(),
                unit = if (kind == GoalKind.NUMERIC) unit.ifBlank { null } else null,
                deadline = deadline,
                milestones = milestones,
            ),
            controller.today,
        )
        if (reminder != null) setGoalReminder(id, reminder.toUShort())
        if (blockStart != null) setGoalBlock(id, blockStart.toUInt(), blockMinutes.toUInt())
    }
}
