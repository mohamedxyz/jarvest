package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The More tab: the screens that don't fit in the bottom bar. */
@Composable
fun MoreScreen(onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            Triple("history", "🗓️ History", "Every day colored by its level, your streaks and best day."),
            Triple("stats", "📊 Stats", "Streaks, trends, the heatmap and your weekly recap."),
            Triple("treats", "🎁 Rewards", "Your treat jar, treats you've won, badges and monsters."),
            Triple("settings", "⚙️ Settings", "Look, sounds, reminders, backups and more."),
        ).forEach { (route, title, body) ->
            SoftCard(Modifier.fillMaxWidth(), onClick = { onOpen(route) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
