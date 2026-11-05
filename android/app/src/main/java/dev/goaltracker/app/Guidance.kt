package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A short lesson shown once. The user closes it with "Got it" and it stays closed. */
@Composable
fun TipCard(controller: AppController, id: String, text: String, modifier: Modifier = Modifier) {
    if (controller.isTipDismissed(id)) return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = { controller.dismissTip(id) }) { Text("Got it") }
        }
    }
}

/** A bottom sheet for details and quick actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** A small "?" button that explains a term in plain words. */
@Composable
fun InfoHint(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, "What is $title?", Modifier.size(20.dp))
    }
    if (open) {
        InfoSheet(title, onDismiss = { open = false }) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Warns, for as long as it is true, that meeting targets earns nothing because the jar is empty. */
@Composable
fun EmptyJarBanner(controller: AppController, onAddTreat: (() -> Unit)?, modifier: Modifier = Modifier) {
    val progress = remember(controller.version) { controller.read(null) { rewardProgress() } } ?: return
    if (!progress.enabled || progress.treatsInJar > 0u) return
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Your treat jar is empty, so targets you meet now win nothing. Add a treat to start winning.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (onAddTreat != null) TextButton(onClick = onAddTreat) { Text("Add") }
        }
    }
}

/** One label and value line inside an [InfoSheet]. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
