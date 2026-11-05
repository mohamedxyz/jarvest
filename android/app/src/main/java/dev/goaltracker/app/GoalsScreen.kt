package dev.goaltracker.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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

@Composable
fun GoalsScreen(controller: AppController, onOpenGoal: (String) -> Unit) {
    var archived by rememberSaveable { mutableStateOf(false) }
    val goals = remember(controller.version, archived) {
        controller.read(emptyList()) { listGoals(archived) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("Active") })
            FilterChip(selected = archived, onClick = { archived = true }, label = { Text("Archived") })
        }
        if (goals.isEmpty()) {
            if (archived) {
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
                Card(Modifier.fillMaxWidth().clickable { onOpenGoal(goal.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(goal.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            describe(goal),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
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
            "${countOf(count, "time")} per ${period?.noun()}"
        }
        GoalKind.NUMERIC -> listOfNotNull(
            if (period != null && periodTarget != null) "${withUnit(periodTarget, goal.unit)} per ${period.noun()}" else null,
            if (totalTarget != null) "${formatAmount(goal.totalLogged)} of ${withUnit(totalTarget, goal.unit)}" else null,
            if (period == null && totalTarget == null) "${withUnit(goal.totalLogged, goal.unit)} logged" else null,
        ).joinToString(" · ")
    }
    return listOfNotNull(goal.category, summary).joinToString(" · ")
}
