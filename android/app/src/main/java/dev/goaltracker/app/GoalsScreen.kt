package dev.goaltracker.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.Goal
import dev.goaltracker.core.GoalKind

private const val DAY_NAMES = "Mon,Tue,Wed,Thu,Fri,Sat,Sun"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalsScreen(controller: AppController, onOpenGoal: (String) -> Unit) {
    var archived by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<GoalKind?>(null) }
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val allGoals = remember(controller.version, archived) {
        controller.read(emptyList()) { listGoals(archived) }
    }
    val categories = remember(allGoals) { allGoals.mapNotNull { it.category }.distinct() }
    val filtering = query.isNotBlank() || kindFilter != null || categoryFilter != null
    val goals = allGoals.filter { goal ->
        (query.isBlank() || goal.title.contains(query.trim(), ignoreCase = true) ||
            goal.category?.contains(query.trim(), ignoreCase = true) == true) &&
            (kindFilter == null || goal.kind == kindFilter) &&
            (categoryFilter == null || goal.category == categoryFilter)
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                label = { Text("Search goals") },
                singleLine = true,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("Active") })
                FilterChip(selected = archived, onClick = { archived = true }, label = { Text("Archived") })
                listOf(GoalKind.HABIT to "Habits", GoalKind.NUMERIC to "Numeric", GoalKind.YES_NO to "Yes/no").forEach { (kind, label) ->
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
        if (goals.isEmpty()) {
            if (filtering) {
                EmptyState("No matching goals", "Try a different search or clear a filter.")
            } else if (archived) {
                EmptyState("Nothing archived", "Archived goals keep their history and appear here.")
            } else {
                EmptyState("No goals yet", "Tap + to create one.")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(goals, key = { it.id }) { goal ->
                // Order is per category, so moving is only offered when the whole list is shown.
                val sameCategory = allGoals.filter { it.category == goal.category }
                val position = sameCategory.indexOfFirst { it.id == goal.id }
                Card(Modifier.fillMaxWidth().clickable { onOpenGoal(goal.id) }) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(goal.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                describe(goal),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!filtering && sameCategory.size > 1) {
                            Column {
                                IconButton(enabled = position > 0, onClick = { controller.act { moveGoal(goal.id, true) } }) {
                                    Icon(Icons.Filled.KeyboardArrowUp, "Move ${goal.title} up")
                                }
                                IconButton(
                                    enabled = position < sameCategory.lastIndex,
                                    onClick = { controller.act { moveGoal(goal.id, false) } },
                                ) { Icon(Icons.Filled.KeyboardArrowDown, "Move ${goal.title} down") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Mon, Wed, Fri" for a weekday bitmask, or null when every day is due. */
fun weekdaysLabel(mask: UByte?): String? {
    if (mask == null) return null
    val names = DAY_NAMES.split(",")
    return names.filterIndexed { index, _ -> mask.toInt() and (1 shl index) != 0 }.joinToString(", ")
}

/** A one-line summary, such as "Health · 3 times per week". */
fun describe(goal: Goal): String {
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
