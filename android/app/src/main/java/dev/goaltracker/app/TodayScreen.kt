package dev.goaltracker.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.TodayItem

@Composable
fun TodayScreen(controller: AppController, onOpenGoal: (String) -> Unit) {
    val items = remember(controller.version, controller.today) {
        controller.read(emptyList()) { today(controller.today) }
    }
    val hasGoals = remember(controller.version) {
        controller.read(emptyList()) { listGoals(false) }.isNotEmpty()
    }
    var logging by remember { mutableStateOf<TodayItem?>(null) }

    if (items.isEmpty()) {
        if (hasGoals) {
            EmptyState("You're all caught up", "Nothing else is due today.")
        } else {
            EmptyState("Add your first goal", "Tap + to create a goal or habit to track.")
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.goalId }) { item ->
                TodayCard(
                    item,
                    onOpen = { onOpenGoal(item.goalId) },
                    onCheck = { controller.log { toggleCheckIn(item.goalId, controller.today) } },
                    onLog = { logging = item },
                )
            }
        }
    }
    logging?.let { item ->
        LogAmountDialog(
            title = item.title,
            unit = item.unit,
            onLog = { amount ->
                if (controller.log { logAmount(item.goalId, amount, controller.today) }) logging = null
            },
            onDismiss = { logging = null },
        )
    }
}

@Composable
private fun TodayCard(item: TodayItem, onOpen: () -> Unit, onCheck: () -> Unit, onLog: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                progressLine(item)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                progressFraction(item)?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) }
                item.nextMilestone?.let {
                    Text("Next: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val period = item.period
                if (item.currentStreak > 0u && period != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "${item.currentStreak}-${period.noun()} streak",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val deadline = item.deadline
                if (item.overdue && deadline != null) {
                    OverdueLabel(deadline)
                } else if (deadline != null) {
                    Text("Due ${formatDate(deadline)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.kind == GoalKind.NUMERIC) {
                FilledTonalButton(onClick = onLog) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text("Log")
                }
            } else {
                Checkbox(checked = item.loggedToday || (item.kind == GoalKind.YES_NO && item.complete), onCheckedChange = { onCheck() })
            }
        }
    }
}

private fun progressLine(item: TodayItem): String? {
    val period = item.period
    val periodTarget = item.periodTarget
    val totalTarget = item.totalTarget
    return when {
        period != null && periodTarget != null ->
            "${formatAmount(item.periodProgress)} of ${withUnit(periodTarget, item.unit)} ${period.current()}"
        totalTarget != null -> "${formatAmount(item.totalProgress)} of ${withUnit(totalTarget, item.unit)}"
        item.kind == GoalKind.NUMERIC -> "${withUnit(item.totalProgress, item.unit)} so far"
        else -> null
    }
}

private fun progressFraction(item: TodayItem): Float? {
    val periodTarget = item.periodTarget
    val totalTarget = item.totalTarget
    return when {
        periodTarget != null -> (item.periodProgress / periodTarget).toFloat().coerceIn(0f, 1f)
        totalTarget != null -> (item.totalProgress / totalTarget).toFloat().coerceIn(0f, 1f)
        else -> null
    }
}
