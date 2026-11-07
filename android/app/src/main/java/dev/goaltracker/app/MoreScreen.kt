package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The More tab: the screens that don't fit in the bottom bar. */
@Composable
fun MoreScreen(controller: AppController, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The hero first: level, XP, and the coins that XP turns into for the shop.
        HeroCard(controller)
        val coins = remember(controller.version) { controller.read(null) { coins() } }
        if (coins != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CoinChip(coins.balance.coerceAtLeast(0L).toInt())
                Text("coins to spend in the shop", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        listOf(
            Triple("history", "🗓️ History", "Every day colored by its level, your streaks and best day."),
            Triple("routines", "🔁 Routines", "Goals you do together, such as a morning routine."),
            Triple("holiday", "🏖️ Holiday mode", "Pause every goal for a few days without losing your streaks."),
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
