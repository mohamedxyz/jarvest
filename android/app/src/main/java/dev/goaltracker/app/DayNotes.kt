package dev.goaltracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val moodFaces = listOf("😞", "😕", "😐", "🙂", "😄")
private val moodWords = listOf("Rough", "Meh", "Okay", "Good", "Great")

private const val MAX_NOTE = 2000

/**
 * A private note and mood for one day. It stays on the phone, never shows on widgets or in notifications,
 * and never changes the score, XP, streaks or rewards.
 */
@Composable
fun DayNoteCard(controller: AppController, date: String, modifier: Modifier = Modifier) {
    // A note can't be written for a day that hasn't come yet.
    if (date > controller.today) return
    val note = remember(controller.version, date) { controller.read(null) { dayNote(date) } }
    var editing by remember { mutableStateOf(false) }
    SoftCard(modifier.fillMaxWidth(), onClick = { editing = true }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (note == null) {
                Text(
                    if (date == controller.today) "📝 How is your day going? Add a private note" else "📝 Add a private note for this day",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    note.mood?.let {
                        Text("${moodFaces[it.toInt() - 1]} ${moodWords[it.toInt() - 1]}", style = MaterialTheme.typography.titleSmall)
                    }
                    if (note.body.isNotBlank()) {
                        Text(note.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { editing = true }) { Text("Edit") }
            }
        }
    }
    if (editing) DayNoteDialog(controller, date, onDismiss = { editing = false })
}

@Composable
fun DayNoteDialog(controller: AppController, date: String, onDismiss: () -> Unit) {
    val existing = remember(date) { controller.read(null) { dayNote(date) } }
    var text by remember { mutableStateOf(existing?.body ?: "") }
    var mood by remember { mutableStateOf(existing?.mood?.toInt()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (date == controller.today) "Today" else formatDate(date)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How was it?", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    moodFaces.forEachIndexed { index, face ->
                        FilterChip(
                            selected = mood == index + 1,
                            onClick = { mood = if (mood == index + 1) null else index + 1 },
                            label = { Text(face) },
                            modifier = Modifier.label("Mood ${index + 1} of 5, ${moodWords[index]}"),
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_NOTE) text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note (only you see this)") },
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (controller.act { setDayNote(date, text, mood?.toUByte(), controller.today) }) onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = {
                        if (controller.act { setDayNote(date, "", null, controller.today) }) onDismiss()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
