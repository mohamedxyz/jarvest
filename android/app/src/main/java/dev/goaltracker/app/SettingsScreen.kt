package dev.goaltracker.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.GoalCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
fun SettingsScreen(controller: AppController) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val enabled = remember(controller.version) { controller.read(true) { rewardsEnabled() } }
    var busy by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.sqlite3"),
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val error = withContext(Dispatchers.IO) { exportTo(context, controller.core, uri) }
                controller.message = error ?: "Backup saved"
                busy = false
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Rewards")
                Text(
                    "Draw treats from your jar when you complete targets. Treats you've won are kept when this is off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(enabled, { on -> controller.act { setRewardsEnabled(on) } })
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Backup")
            Text(
                "Your data is stored only on this phone. A backup is one file containing everything. Keep a copy somewhere safe, because uninstalling the app deletes your data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                exporter.launch("goal-tracker-backup-${controller.today}.sqlite")
            }) { Text("Save backup") }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                importer.launch(arrayOf("*/*"))
            }) { Text("Restore from backup") }
        }
    }

    restoreUri?.let { uri ->
        ConfirmDialog(
            title = "Replace all current data?",
            body = "Everything in the app will be replaced with the contents of this backup. Files that aren't valid backups are rejected without changing anything.",
            confirm = "Restore",
            onConfirm = {
                restoreUri = null
                busy = true
                scope.launch {
                    val error = withContext(Dispatchers.IO) { restoreFrom(context, controller.core, uri) }
                    controller.message = error ?: "Backup restored"
                    if (error == null) controller.refresh()
                    busy = false
                }
            },
            onDismiss = { restoreUri = null },
        )
    }
}

/** Returns an error message, or null on success. */
private fun exportTo(context: Context, core: GoalCore, uri: Uri): String? {
    val temp = File(context.cacheDir, "export.sqlite")
    return try {
        core.exportBackup(temp.path)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no output")
        output.use { out -> temp.inputStream().use { it.copyTo(out) } }
        null
    } catch (e: CoreException) {
        e.userMessage()
    } catch (_: IOException) {
        "The backup couldn't be saved to that location."
    } finally {
        temp.delete()
    }
}

/** Returns an error message, or null on success. */
private fun restoreFrom(context: Context, core: GoalCore, uri: Uri): String? {
    val temp = File(context.cacheDir, "import.sqlite")
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no input")
        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
        core.restoreBackup(temp.path)
        null
    } catch (e: CoreException) {
        e.userMessage()
    } catch (_: IOException) {
        "That file couldn't be read."
    } finally {
        temp.delete()
    }
}
