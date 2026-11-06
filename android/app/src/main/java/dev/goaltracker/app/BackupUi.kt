package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.BackupPreview
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.GoalCore
import java.io.File
import java.io.IOException

/** The "Automatic backup" block in Settings: a switch, a folder, and a time. */
@Composable
fun AutoBackupSection(controller: AppController) {
    val context = LocalContext.current.applicationContext
    var enabled by remember { mutableStateOf(AutoBackup.isEnabled(context)) }
    var folder by remember { mutableStateOf(AutoBackup.folder(context)) }
    var minutes by remember { mutableStateOf(AutoBackup.minutes(context)) }
    var error by remember { mutableStateOf(AutoBackup.lastError(context)) }
    var pickingTime by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                AutoBackup.setFolder(context, uri)
                folder = uri
            } catch (_: SecurityException) {
                controller.message = "That folder can't be used. Pick another one."
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Automatic backup")
        Text(
            "Saves a backup file into a folder you choose, once a day, and keeps the newest 7. Nothing is saved if the app is empty.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ui_back_up_every_day), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(enabled, { on ->
                if (on && folder == null) {
                    controller.message = "Choose a folder first."
                } else {
                    enabled = on
                    AutoBackup.setEnabled(context, on)
                }
            })
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { folderPicker.launch(null) }) {
            Text(if (folder == null) "Choose backup folder" else "Change folder")
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { pickingTime = true }) {
            Text("Time: ${Reminders.formatMinutes(minutes)}")
        }
        error?.let {
            Text(
                "The last automatic backup failed: $it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (pickingTime) {
        TimePickerDialog(
            initialMinutes = minutes,
            onPick = {
                minutes = it
                AutoBackup.setMinutes(context, it)
                pickingTime = false
            },
            onDismiss = { pickingTime = false },
        )
    }
}

/** A backup's contents next to what is on the phone now. `password` is set for an encrypted backup. */
class RestorePreview(val uri: Uri, val backup: BackupPreview, val now: BackupPreview, val password: String? = null) {
    /** True when restoring would remove goals, tasks, logs, or treats that exist now. */
    val losesData: Boolean
        get() = backup.goals < now.goals || backup.tasks < now.tasks ||
            backup.entries < now.entries || backup.treats < now.treats
}

/**
 * Result of reading a backup: a preview, a message to show, or `locked` when the file is
 * password protected and a password is needed first.
 */
class PreviewResult(val preview: RestorePreview?, val error: String?, val locked: Uri? = null)

/** Copies the picked file to a private temp file and asks the core what is inside. Run off the main thread. */
fun previewFrom(context: Context, core: GoalCore, uri: Uri, password: String? = null): PreviewResult {
    val temp = File(context.cacheDir, "preview.sqlite")
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no input")
        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
        if (password == null && core.isEncryptedBackup(temp.path)) {
            PreviewResult(null, null, locked = uri)
        } else if (password != null) {
            PreviewResult(RestorePreview(uri, core.previewEncryptedBackup(temp.path, password), core.dataSummary(), password), null)
        } else {
            PreviewResult(RestorePreview(uri, core.previewBackup(temp.path), core.dataSummary()), null)
        }
    } catch (e: CoreException) {
        PreviewResult(null, e.userMessage())
    } catch (_: IOException) {
        PreviewResult(null, "That file couldn't be read.")
    } finally {
        temp.delete()
    }
}

/** Asks for the password of an encrypted backup. */
@Composable
fun BackupUnlockDialog(onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password needed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This backup is password protected. Type the password you chose when you saved it.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = { onUnlock(password) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks whether to protect a new backup with a password. `onChoose(null)` means no password. */
@Composable
fun BackupPasswordDialog(onChoose: (String?) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val empty = password.isEmpty() && again.isEmpty()
    val tooShort = !empty && password.length < 8
    val mismatch = !empty && password != again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Protect with a password?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Leave both boxes empty for a normal backup. With a password, nobody can read the file without it. A lost password cannot be recovered, and neither can the backup.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (at least 8 characters)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = again,
                    onValueChange = { again = it },
                    label = { Text("Type it again") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = mismatch,
                )
                if (tooShort) Text("Use at least 8 characters.", color = MaterialTheme.colorScheme.error)
                else if (mismatch) Text("The two passwords differ.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = empty || (!tooShort && !mismatch), onClick = { onChoose(if (empty) null else password) }) {
                Text(if (empty) "No password" else "Use password")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The restore confirmation: a "Backup vs Now" table, with a red button when data would be lost. */
@Composable
fun RestorePreviewDialog(preview: RestorePreview, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val b = preview.backup
    val n = preview.now
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_replace_all_current_data)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("", Modifier.weight(1.4f))
                    Text(stringResource(R.string.ui_backup), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.ui_now), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                }
                CompareRow("Goals", b.goals, n.goals)
                CompareRow("Tasks", b.tasks, n.tasks)
                CompareRow("Logs", b.entries, n.entries)
                CompareRow("Treats", b.treats, n.treats)
                Text(
                    "Newest log in the backup: ${b.newestEntry?.let { formatDate(it) } ?: "none"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (preview.losesData) "The backup has less than you have now, so the extra items will be gone."
                    else "Everything in the app will be replaced with this backup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (preview.losesData) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "A copy of your current data is kept on the phone until the next restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // Red when data would be lost, plain otherwise.
            TextButton(onClick = onRestore) {
                Text(
                    "Restore",
                    color = if (preview.losesData) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

@Composable
private fun CompareRow(label: String, backup: UInt, now: UInt) {
    val less = backup < now
    val color = if (less) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
        Text("$backup", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color)
        Text("$now", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
