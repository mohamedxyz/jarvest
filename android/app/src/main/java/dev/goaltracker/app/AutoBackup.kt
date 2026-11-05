package dev.goaltracker.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.goaltracker.core.CoreException
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Automatic daily backup. The user picks a folder once (through Android's folder
 * picker, so the app never needs broad storage permission). Every day at the chosen
 * time a WorkManager job copies a backup file into that folder and keeps the newest 7.
 *
 * These settings stay on the phone (SharedPreferences), not in the database, because
 * the folder permission only works on this phone and a restore must not change it.
 */
object AutoBackup {
    private const val PREFS = "auto_backup"
    private const val WORK_NAME = "auto_backup"
    private const val KEEP = 7
    private const val DEFAULT_MINUTES = 21 * 60
    private val namePattern = Regex("""jarvest-auto-\d{4}-\d{2}-\d{2}\.sqlite""")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun folder(context: Context): Uri? = prefs(context).getString("folder", null)?.let(Uri::parse)
    fun minutes(context: Context) = prefs(context).getInt("minutes", DEFAULT_MINUTES)
    fun lastError(context: Context): String? = prefs(context).getString("last_error", null)

    /** Remembers the folder the user picked and keeps Android's permission to it across restarts. */
    fun setFolder(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs(context).edit().putString("folder", uri.toString()).apply()
    }

    fun setMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt("minutes", minutes).apply()
        if (isEnabled(context)) schedule(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("enabled", on).apply()
        if (on) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Runs once a day, starting at the next chosen time. Calling it again replaces the old schedule. */
    fun schedule(context: Context) {
        val minutes = minutes(context)
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(minutes / 60, minutes % 60)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Makes one backup now. Returns an error message, or null when it worked or was skipped. */
    fun run(context: Context): String? {
        val app = context.applicationContext as GoalTrackerApp
        val tree = folder(context) ?: return "no backup folder is chosen. Pick one in Settings"
        val temp = File(context.cacheDir, "auto-backup.sqlite")
        return try {
            // An empty database isn't worth keeping, and it could push out a good older copy.
            val summary = app.core.dataSummary()
            if (summary.goals + summary.tasks + summary.entries == 0u) return null

            app.core.exportBackup(temp.path)
            val today = todayString()
            val name = "jarvest-auto-$today.sqlite"
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val existing = children(context, tree)
            // Creating a file with a taken name would add " (1)", so the same-day copy goes first.
            existing[name]?.let { DocumentsContract.deleteDocument(resolver, it) }
            val target = DocumentsContract.createDocument(resolver, parent, "application/vnd.sqlite3", name)
                ?: throw IOException("could not create the file")
            val output = resolver.openOutputStream(target, "wt") ?: throw IOException("no output")
            output.use { out -> temp.inputStream().use { it.copyTo(out) } }

            // Keep the newest KEEP files. The names sort by date.
            (existing.keys + name).filter { namePattern.matches(it) }.distinct().sortedDescending()
                .drop(KEEP)
                .forEach { old -> existing[old]?.let { DocumentsContract.deleteDocument(resolver, it) } }

            context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("last_backup", today).apply()
            Reminders.updateStaleBackupAlarm(context, today, today)
            null
        } catch (e: CoreException) {
            e.userMessage()
        } catch (_: SecurityException) {
            "the backup folder is no longer available. Pick it again in Settings"
        } catch (_: IOException) {
            "the backup folder couldn't be written to. Check that it still exists and has space"
        } catch (_: IllegalArgumentException) {
            "the backup folder is no longer available. Pick it again in Settings"
        } finally {
            temp.delete()
        }
    }

    /** File name to document address, for the files directly inside the chosen folder. */
    private fun children(context: Context, tree: Uri): Map<String, Uri> {
        val result = mutableMapOf<String, Uri>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        }
        return result
    }
}

/** The daily job. Any failure becomes a notification and a message in Settings. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val error = AutoBackup.run(applicationContext)
        applicationContext.getSharedPreferences("auto_backup", Context.MODE_PRIVATE)
            .edit().putString("last_error", error).apply()
        if (error != null) Reminders.notifyBackupFailed(applicationContext, error)
        // Always success: the next day's run is already scheduled by the periodic request.
        return Result.success()
    }
}
