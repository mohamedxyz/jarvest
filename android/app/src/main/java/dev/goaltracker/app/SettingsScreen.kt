package dev.goaltracker.app

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.goaltracker.core.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(controller: AppController) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val enabled = remember(controller.version) { controller.read(true) { rewardsEnabled() } }
    val daily = remember(controller.version) { controller.read(null) { dailyReminder() } }
    var pickingDaily by remember { mutableStateOf(false) }
    val evening = remember(controller.version) { controller.read(null) { eveningReminder() } }
    var pickingEvening by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationPermissionRequest(controller)
    var busy by remember { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<RestorePreview?>(null) }
    // The automatic worker may have saved a backup while the app was closed.
    LaunchedEffect(Unit) { controller.reloadBackupDate() }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.sqlite3"),
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val error = withContext(Dispatchers.IO) { exportTo(context, controller.core, uri) }
                if (error == null) controller.markBackup()
                controller.message = error ?: "Backup saved"
                busy = false
            }
        }
    }
    val csvExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val error = withContext(Dispatchers.IO) { exportCsvTo(context, controller.core, uri) }
                controller.message = error ?: "Logs exported"
                busy = false
            }
        }
    }
    val tasksCsvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val error = withContext(Dispatchers.IO) { writeExport(context, uri, "tasks.csv") { controller.core.exportTasksCsv(it) } }
                controller.message = error ?: "Tasks exported"
                busy = false
            }
        }
    }
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val error = withContext(Dispatchers.IO) { writeExport(context, uri, "all.json") { controller.core.exportJson(it) } }
                controller.message = error ?: "Data exported"
                busy = false
            }
        }
    }
    val tasksCsvImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                controller.message = withContext(Dispatchers.IO) { importTasksCsv(context, controller.core, uri, controller.today) }
                controller.refresh()
                busy = false
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Read the file first, so the dialog can show what it holds (or why it can't be used).
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { previewFrom(context, controller.core, uri) }
                restorePreview = result.preview
                result.error?.let { controller.message = it }
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsGroup("Look and feel", "look") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Appearance")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        controller.themeMode == mode,
                        { controller.chooseTheme(mode) },
                        label = { Text(mode.label) },
                        leadingIcon = { ThemeSwatch(mode) },
                    )
                }
            }
            TextButton(onClick = {
                controller.resetTips()
                controller.message = "Tips will show again."
            }) { Text(stringResource(R.string.ui_show_tips_again)) }
        }
        FeelSettings()
        }

        SettingsGroup("Tasks and reminders", "tasks") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("New tasks")
            Text(
                "What a new task starts with. Words in a quick-add line, such as tomorrow or !high, still win.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.ui_priority), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.reversed().forEach {
                    FilterChip(controller.defaultPriority == it, { controller.chooseDefaultPriority(it) }, label = { Text(it.label()) })
                }
            }
            Text(stringResource(R.string.ui_due_date), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultDue.entries.forEach {
                    FilterChip(controller.defaultDue == it, { controller.chooseDefaultDue(it) }, label = { Text(it.label) })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Task list")
            @Composable
            fun toggle(label: String, on: Boolean, change: (Boolean) -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(on, change)
                }
            }
            toggle("Show the first line of notes", controller.showTaskNotes) { controller.chooseShowTaskNotes(it) }
            toggle("Show checklist progress", controller.showChecklistProgress) { controller.chooseShowChecklistProgress(it) }
            toggle("Compact rows", controller.compactTaskRows) { controller.chooseCompactTaskRows(it) }
            toggle("Ask before deleting forever", controller.confirmDeleteForever) { controller.chooseConfirmDeleteForever(it) }
            Text(stringResource(R.string.ui_archive_finished_tasks), style = MaterialTheme.typography.labelLarge)
            Text(
                "Archived tasks leave your lists but stay searchable. Open them from the Archive button on the Tasks tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "Never", 1 to "After 1 day", 7 to "After 7 days", 30 to "After 30 days").forEach { (days, label) ->
                    FilterChip(controller.archiveAfterDays == days, { controller.chooseArchiveAfterDays(days) }, label = { Text(label) })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Week starts on")
            Text(
                "Weekly goals, the calendar, and charts begin their week on this day. Changing it never changes your history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val weekStart = remember(controller.version) { controller.weekStart() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Monday", 7 to "Sunday", 6 to "Saturday").forEach { (day, label) ->
                    FilterChip(weekStart == day, { controller.chooseWeekStart(day) }, label = { Text(label) })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Reminders")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Daily summary of what is still due. Set a reminder on a single habit or goal when you edit it. Reminders can arrive a few minutes late unless you allow exact alarms below.",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(daily != null, { on ->
                    if (on) {
                        controller.act { setDailyReminder((20 * 60).toUShort()) }
                        askNotifications()
                    } else {
                        controller.act { setDailyReminder(null) }
                    }
                })
            }
            daily?.let {
                OutlinedButton(onClick = { pickingDaily = true }) { Text("Daily summary at ${Reminders.formatMinutes(it.toInt())}") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Evening check-in: a second reminder that says how many habits, goals and tasks are left today. It stays quiet when nothing is left.",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(evening != null, { on ->
                    if (on) {
                        controller.act { setEveningReminder((21 * 60).toUShort()) }
                        askNotifications()
                    } else {
                        controller.act { setEveningReminder(null) }
                    }
                })
            }
            evening?.let {
                OutlinedButton(onClick = { pickingEvening = true }) { Text("Evening check-in at ${Reminders.formatMinutes(it.toInt())}") }
            }
            var sound by remember { mutableStateOf(Reminders.soundOn(context)) }
            var quiet by remember { mutableStateOf(Reminders.quietOn(context)) }
            var quietStart by remember { mutableStateOf(Reminders.quietStart(context)) }
            var quietEnd by remember { mutableStateOf(Reminders.quietEnd(context)) }
            var pickQuiet by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.ui_reminders_make_a_sound), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(sound, { sound = it; Reminders.setSound(context, it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Quiet hours: reminders still show, but silently.",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(quiet, { quiet = it; Reminders.setQuietOn(context, it) })
            }
            if (quiet) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickQuiet = "start" }) { Text("From ${Reminders.formatMinutes(quietStart)}") }
                    OutlinedButton(onClick = { pickQuiet = "end" }) { Text("Until ${Reminders.formatMinutes(quietEnd)}") }
                }
            }
            pickQuiet?.let { which ->
                TimePickerDialog(
                    initialMinutes = if (which == "start") quietStart else quietEnd,
                    onPick = {
                        if (which == "start") quietStart = it else quietEnd = it
                        Reminders.setQuiet(context, quietStart, quietEnd)
                        pickQuiet = null
                    },
                    onDismiss = { pickQuiet = null },
                )
            }
            OutlinedButton(onClick = { controller.message = Reminders.sendTest(context) }) {
                Text(stringResource(R.string.ui_send_a_test_notification))
            }
            ReminderReliability(controller)
        }

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
        }

        SettingsGroup("About", "about") {
            val version = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (_: Exception) {
                    null
                }
            }
            Text(
                "Jarvest ${version ?: ""}".trim(),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Your data stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsGroup("Backup and data", "data") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Backup")
            Text(
                "Your data is stored only on this phone. A backup is one file containing everything. Keep a copy somewhere safe, because uninstalling the app deletes your data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val last = controller.lastBackup
            Text(
                if (last == null) "Last backup: never"
                else "Last backup: ${formatDate(last)} (${countOf(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(last), java.time.LocalDate.parse(controller.today)).toInt(), "day")} ago)",
                style = MaterialTheme.typography.titleSmall,
                color = if (controller.backupLevel() == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                exporter.launch("jarvest-backup-${controller.today}.sqlite")
            }) { Text(stringResource(R.string.ui_save_backup)) }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                importer.launch(arrayOf("*/*"))
            }) { Text(stringResource(R.string.ui_restore_from_backup)) }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    val error = withContext(Dispatchers.IO) { shareBackup(context, controller.core, controller.today) }
                    if (error != null) controller.message = error
                    busy = false
                }
            }) { Text(stringResource(R.string.ui_share_backup)) }
        }

        AutoBackupSection(controller)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Export logs")
            Text(
                "Save every log as a CSV file that opens in a spreadsheet. This is for reading your data, not for restoring it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                csvExporter.launch("jarvest-logs-${controller.today}.csv")
            }) { Text(stringResource(R.string.ui_export_logs_to_csv)) }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                tasksCsvExporter.launch("jarvest-tasks-${controller.today}.csv")
            }) { Text(stringResource(R.string.ui_export_tasks_to_csv)) }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                jsonExporter.launch("jarvest-data-${controller.today}.json")
            }) { Text(stringResource(R.string.ui_export_everything_to_json)) }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                tasksCsvImporter.launch(arrayOf("text/*", "*/*"))
            }) { Text(stringResource(R.string.ui_import_tasks_from_csv)) }
            Text(
                "A task CSV needs a first row naming the columns. Only title is required; due (YYYY-MM-DD), priority, tag and notes are optional.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }

        HelpGroup(controller)
    }

    if (pickingDaily) {
        TimePickerDialog(
            initialMinutes = daily?.toInt(),
            onPick = {
                controller.act { setDailyReminder(it.toUShort()) }
                pickingDaily = false
            },
            onDismiss = { pickingDaily = false },
        )
    }
    if (pickingEvening) {
        TimePickerDialog(
            initialMinutes = evening?.toInt(),
            onPick = {
                controller.act { setEveningReminder(it.toUShort()) }
                pickingEvening = false
            },
            onDismiss = { pickingEvening = false },
        )
    }
    restorePreview?.let { preview ->
        RestorePreviewDialog(
            preview = preview,
            onRestore = {
                restorePreview = null
                busy = true
                scope.launch {
                    val error = withContext(Dispatchers.IO) { restoreFrom(context, controller.core, preview.uri) }
                    controller.message = error ?: "Backup restored"
                    if (error == null) controller.refresh()
                    busy = false
                }
            },
            onDismiss = { restorePreview = null },
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

/** Returns an error message, or null on success. */
private fun exportCsvTo(context: Context, core: GoalCore, uri: Uri): String? {
    val temp = File(context.cacheDir, "export.csv")
    return try {
        core.exportCsv(temp.path)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no output")
        output.use { out -> temp.inputStream().use { it.copyTo(out) } }
        null
    } catch (e: CoreException) {
        e.userMessage()
    } catch (_: IOException) {
        "The file couldn't be saved to that location."
    } finally {
        temp.delete()
    }
}

/** Makes a file with `make`, then copies it to the place the person picked. Returns an error message or null. */
private fun writeExport(context: Context, uri: Uri, name: String, make: (String) -> Unit): String? {
    val temp = File(context.cacheDir, name)
    return try {
        make(temp.path)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no output")
        output.use { out -> temp.inputStream().use { it.copyTo(out) } }
        null
    } catch (e: CoreException) {
        e.userMessage()
    } catch (_: IOException) {
        "The file couldn't be saved to that location."
    } finally {
        temp.delete()
    }
}

/** Adds the tasks in a CSV file. Returns what to tell the person. */
private fun importTasksCsv(context: Context, core: GoalCore, uri: Uri, today: String): String {
    val temp = File(context.cacheDir, "import.csv")
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no input")
        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
        val added = core.importTasksCsv(temp.path, today)
        if (added == 1u) "Added 1 task" else "Added $added tasks"
    } catch (e: CoreException) {
        e.userMessage()
    } catch (_: IOException) {
        "That file couldn't be read."
    } finally {
        temp.delete()
    }
}

/** Opens the share sheet with a fresh backup file. Old shared copies are deleted first. Returns an error or null. */
private fun shareBackup(context: Context, core: GoalCore, today: String): String? {
    val dir = File(context.cacheDir, "share")
    return try {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "jarvest-backup-$today.sqlite")
        core.exportBackup(file.path)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(send, "Share backup").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    } catch (e: CoreException) {
        e.userMessage()
    } catch (_: IOException) {
        "The backup couldn't be prepared."
    }
}

/** A small two-tone dot: the look's background with its main color inside. */
@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    val s = mode.scheme(isSystemInDarkTheme())
    Box(
        Modifier.size(18.dp).clip(CircleShape).background(s.background)
            .border(1.dp, s.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(s.primary))
    }
}
