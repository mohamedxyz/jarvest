package dev.goaltracker.app

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoalEditorScreen(controller: AppController, goalId: String?, onDone: () -> Unit) {
    val existing = remember(goalId) { goalId?.let { id -> controller.read(null) { getGoal(id) } } }
    val categories = remember { controller.read(emptyList()) { categories() } }

    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "") }
    var unit by rememberSaveable { mutableStateOf(existing?.unit ?: "") }
    var deadline by rememberSaveable { mutableStateOf(existing?.deadline) }
    var kind by rememberSaveable { mutableStateOf(GoalKind.HABIT) }
    var period by rememberSaveable { mutableStateOf<Period?>(Period.WEEK) }
    var periodTarget by rememberSaveable { mutableStateOf("3") }
    var totalTarget by rememberSaveable { mutableStateOf("") }
    val milestones = remember { mutableStateListOf<String>() }
    var milestoneText by rememberSaveable { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)

        if (existing == null) {
            SectionTitle("Type")
            val kinds = listOf(GoalKind.HABIT to "Habit", GoalKind.NUMERIC to "Numeric", GoalKind.YES_NO to "Yes/no")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = kind == value,
                        onClick = {
                            kind = value
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
                    GoalKind.HABIT -> "Check in up to once a day, for example 3 times a week."
                    GoalKind.NUMERIC -> "Add amounts toward a target, for example 300 pages."
                    GoalKind.YES_NO -> "Something to do once, for example file your taxes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (kind != GoalKind.YES_NO) {
                SectionTitle(if (kind == GoalKind.HABIT) "Target" else "Repeating target (optional)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind == GoalKind.NUMERIC) {
                        FilterChip(period == null, { period = null }, label = { Text("None") })
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
                if (kind == GoalKind.NUMERIC) {
                    OutlinedTextField(
                        totalTarget,
                        { totalTarget = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Total target (optional)") },
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
            OutlinedTextField(unit, { unit = it }, Modifier.fillMaxWidth(), label = { Text("Unit (optional), for example km") }, singleLine = true)
        }

        OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("Category (optional)") }, singleLine = true)
        if (categories.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { name -> SuggestionChip({ category = name }, { Text(name) }) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingDate = true }) {
                Text(deadline?.let { "Deadline: ${formatDate(it)}" } ?: "Add deadline")
            }
            if (deadline != null) {
                IconButton(onClick = { deadline = null }) { Icon(Icons.Filled.Close, "Clear deadline") }
            }
        }

        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes (optional)") }, minLines = 2)

        if (existing == null) {
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
                OutlinedTextField(milestoneText, { milestoneText = it }, Modifier.weight(1f), label = { Text("Milestone") }, singleLine = true)
                TextButton(enabled = milestoneText.isNotBlank(), onClick = {
                    milestones.add(milestoneText.trim())
                    milestoneText = ""
                }) { Text("Add") }
            }
        }

    }

        // Pinned, so saving never requires scrolling past the whole form.
        Button(
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            onClick = {
                val saved = if (existing != null) {
                    controller.act {
                        updateGoal(existing.id, GoalEdit(title, notes, category.ifBlank { null }, unit.ifBlank { null }, deadline))
                    }
                } else {
                    saveNew(controller, title, notes, kind, category, period, periodTarget, totalTarget, unit, deadline, milestones.toList() + listOfNotNull(milestoneText.takeIf { it.isNotBlank() }))
                }
                if (saved) onDone()
            },
        ) { Text(if (existing == null) "Create goal" else "Save") }
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
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
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
    milestones: List<String>,
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
        createGoal(
            NewGoal(
                title = title,
                notes = notes,
                kind = kind,
                category = category.ifBlank { null },
                period = if (usesPeriod) period else null,
                periodTarget = parsedPeriodTarget,
                totalTarget = parsedTotal,
                unit = if (kind == GoalKind.NUMERIC) unit.ifBlank { null } else null,
                deadline = deadline,
                milestones = milestones,
            ),
            controller.today,
        )
    }
}
