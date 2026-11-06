package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.TrashedGoal

/**
 * A deleted goal in the trash. Restore brings back everything (check-ins, milestones,
 * rewards, reminder). Delete forever removes it now instead of after 30 days.
 */
@Composable
fun GoalTrashRow(goal: TrashedGoal, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Text("Goal: ${goal.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${countOf(goal.daysLeft.toInt(), "day")} left, ${countOf(goal.entries.toInt(), "log")} kept",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = onDeleteForever) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
