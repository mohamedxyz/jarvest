package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TreatsScreen(controller: AppController) {
    val treats = remember(controller.version) { controller.read(emptyList()) { listTreats() } }
    val draws = remember(controller.version) { controller.read(emptyList()) { listDraws() } }
    val enabled = remember(controller.version) { controller.read(true) { rewardsEnabled() } }
    val badges = remember(controller.version, controller.today) { controller.read(emptyList()) { badges(controller.today) } }
    var newTreat by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (enabled) {
                "Fill your jar with things you enjoy. Completing a target has about a 1 in 5 chance of drawing one, and you'll always get one within 10 targets. Getting back on track after a miss doubles your chances."
            } else {
                "Rewards are turned off. You can turn them back on in Settings."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Your jar")
            if (treats.isEmpty()) Text("Your jar is empty.", style = MaterialTheme.typography.bodyMedium)
            treats.forEach { treat ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(treat.title, Modifier.weight(1f))
                    IconButton(onClick = { controller.act { removeTreat(treat.id) } }) {
                        Icon(Icons.Filled.Close, "Remove ${treat.title}")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newTreat, { newTreat = it }, Modifier.weight(1f), label = { Text("New treat, for example a movie night") }, singleLine = true)
                TextButton(enabled = newTreat.isNotBlank(), onClick = {
                    if (controller.act { addTreat(newTreat) }) newTreat = ""
                }) { Text("Add") }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Treats you've won")
            if (draws.isNotEmpty()) {
                Text(
                    "${draws.size} won, ${draws.count { it.redeemedDate != null }} enjoyed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (draws.isEmpty()) Text("None yet. Keep hitting your targets!", style = MaterialTheme.typography.bodyMedium)
            draws.forEach { draw ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = draw.redeemedDate != null,
                        onCheckedChange = { controller.act { toggleRedeemed(draw.id, controller.today) } },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(draw.treatTitle)
                        Text(
                            "${draw.goalTitle} · ${formatDate(draw.localDate)}" + if (draw.comeback) " · Comeback" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (draws.isNotEmpty()) {
                Text("Tick a treat once you've enjoyed it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Badges (${badges.count { it.earned }} of ${badges.size})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                badges.forEach { badge ->
                    AssistChip(
                        onClick = {},
                        label = { Text(if (badge.earned) badge.title else "${badge.title} (locked)") },
                        enabled = badge.earned,
                    )
                }
            }
            Text(
                badges.filter { !it.earned }.take(3).joinToString("\n") { "${it.title}: ${it.description}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
