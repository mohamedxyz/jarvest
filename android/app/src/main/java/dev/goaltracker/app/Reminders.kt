package dev.goaltracker.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.goaltracker.core.CoreException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * Reminder alarms. The times live in the Rust core, so backups keep them. Alarms are exact
 * when the person allows it in Settings and a few minutes late otherwise. They are rebuilt after
 * a reboot, an app update, and a clock or time zone change, so no always-on service is needed.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    private const val KEY_DAILY = "daily"
    private const val KEY_EVENING = "evening"
    private const val PREFS = "reminders"
    private const val SCHEDULED = "scheduled"
    private const val KEY_STALE = "stale_backup"
    private const val STALE_DAYS = 7L

    fun formatMinutes(minutes: Int): String =
        LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    /** Cancels every alarm set earlier, then sets one per current reminder. */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext as GoalTrackerApp
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(SCHEDULED, emptySet()).orEmpty().forEach { cancel(context, it) }
        val keys = mutableSetOf<String>()
        try {
            app.core.dailyReminder()?.let {
                schedule(context, KEY_DAILY, it.toInt())
                keys += KEY_DAILY
            }
            app.core.eveningReminder()?.let {
                schedule(context, KEY_EVENING, it.toInt())
                keys += KEY_EVENING
            }
            app.core.listReminders().forEach {
                schedule(context, it.goalId, it.minutes.toInt())
                keys += it.goalId
            }
        } catch (_: CoreException) {
            // Alarms are rebuilt on the next change or app start.
        }
        prefs.edit().putStringSet(SCHEDULED, keys).apply()
        // The stale-backup alarm isn't in the list above, so it is handled on its own.
        val last = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("last_backup", null)
        updateStaleBackupAlarm(context, last, todayString())
    }

    /**
     * One gentle nudge at 12:00 tomorrow when the last backup is over a week old (or
     * there never was one). A fresh backup cancels it. The alarm is always the same one,
     * so calling this again never stacks extra alarms.
     */
    fun updateStaleBackupAlarm(context: Context, lastBackup: String?, today: String) {
        // Someone who has never made a backup is nudged by the banner in the app instead.
        val days = lastBackup?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.parse(today)) }
        if (days == null || days <= STALE_DAYS) {
            cancel(context, KEY_STALE)
            return
        }
        val noonTomorrow = LocalDate.parse(today).plusDays(1).atTime(12, 0)
        val millis = noonTomorrow.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent(context, KEY_STALE))
    }

    /** Called by the stale-backup alarm. Checks the date again, because a backup may have happened since. */
    private fun fireStaleBackup(context: Context) {
        val last = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("last_backup", null)
        val days = last?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.now()) }
        if (days == null || days <= STALE_DAYS) return
        val text = "Your last backup was $days days ago. Save a new one in Settings."
        if (canNotify(context)) notify(context, KEY_STALE, text)
        // Ask again in a week if nothing changes, so the nudge stays gentle.
        val again = LocalDate.now().plusDays(STALE_DAYS).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, again, pendingIntent(context, KEY_STALE))
    }

    /** Shows a notification that the automatic backup failed. */
    fun notifyBackupFailed(context: Context, reason: String) {
        if (canNotify(context)) notify(context, "backup_failed", "Automatic backup failed: $reason")
    }

    private fun pendingIntent(context: Context, key: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            // The address makes each reminder its own alarm. Extras don't count for that.
            .setData(Uri.parse("jarvest://reminder/$key"))
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancel(context: Context, key: String) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pendingIntent(context, key))
    }

    fun schedule(context: Context, key: String, minutes: Int) {
        val now = LocalDateTime.now()
        var at = LocalDate.now().atTime(minutes / 60, minutes % 60)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Exact when Android allows it (see the Settings switch), otherwise a few minutes late at worst.
        if (canScheduleExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent(context, key))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent(context, key))
        }
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Opens Android's own prompt that lets Jarvest run in the background without being put to sleep. */
    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens the page where the person allows exact alarms (Android 12 and newer). */
    fun requestExactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Called by the alarm. Posts a notification if something is due, then sets tomorrow's alarm. */
    fun fire(context: Context, key: String) {
        if (key == KEY_STALE) return fireStaleBackup(context)
        val app = context.applicationContext as GoalTrackerApp
        try {
            val goalId = key.takeIf { it != KEY_DAILY && it != KEY_EVENING }
            val minutes = when (key) {
                KEY_DAILY -> app.core.dailyReminder()
                KEY_EVENING -> app.core.eveningReminder()
                else -> app.core.goalReminder(goalId!!)
            }
            // The reminder was removed since this alarm was set.
            if (minutes == null) return
            schedule(context, key, minutes.toInt())
            // The evening check-in only speaks when something is still left today.
            val text = if (key == KEY_EVENING) app.core.eveningText(todayString())?.let { it + scoreHint(app) }
            else app.core.reminderText(goalId, todayString())
            if (text != null && canNotify(context)) notify(context, key, text)
        } catch (_: CoreException) {
            // Nothing to show. The next change or app start rebuilds the alarms.
        }
    }

    /** A short note about the day score for the evening check-in, such as " Score 40%: 30 points to Blaze." */
    private fun scoreHint(app: GoalTrackerApp): String = try {
        val score = app.core.dayScore(todayString())
        when {
            score.possible == 0u -> ""
            score.level == dev.goaltracker.core.DayLevel.TITAN -> ""
            else -> {
                val next = if (score.level == dev.goaltracker.core.DayLevel.BLAZE) "Titan" else "Blaze"
                ". Score ${score.percent}%: ${score.pointsToNext} points to $next."
            }
        }
    } catch (_: CoreException) {
        ""
    }

    /**
     * Posts a test notification now. Returns what to tell the user: either that it was
     * sent, or why nothing can show (so a denied permission is explained, not silent).
     */
    fun sendTest(context: Context): String {
        if (!canNotify(context)) {
            return "Notifications are off for Jarvest. Turn them on in the phone's Settings, under Apps, Jarvest, Notifications."
        }
        notify(context, "test", "This is a test. Reminders will look like this.")
        return "Test notification sent."
    }

    private fun notify(context: Context, key: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvest")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // The permission was checked in canNotify.
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.data?.lastPathSegment ?: return
        Reminders.fire(context, key)
    }
}

/** Alarms disappear on reboot and app update, so this puts them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.rescheduleAll(context)
    }
}

/**
 * Returns a function that asks for notification permission when it's needed (Android 13
 * and newer) and explains what to do if it was refused.
 */
@Composable
fun rememberNotificationPermissionRequest(controller: AppController): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            controller.message = "Reminders can't show until you allow notifications for Jarvest in the phone's settings."
        }
    }
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Settings block that keeps reminders reliable: battery exemption and exact alarms. */
@Composable
fun ReminderReliability(controller: AppController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // The person comes back from Android's settings, which bumps the version, so this re-reads.
    val battery = remember(controller.version) { Reminders.ignoresBatteryOptimizations(context) }
    val exact = remember(controller.version) { Reminders.canScheduleExact(context) }
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Text(
            "Reliable reminders: some phones put apps to sleep to save battery, which delays or drops reminders. " +
                "Allowing both items below fixes that without any permanent notification, and uses almost no battery.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.OutlinedButton(enabled = !battery, onClick = { Reminders.requestIgnoreBatteryOptimizations(context) }) {
            androidx.compose.material3.Text(if (battery) "Battery: not restricted" else "Don't restrict battery for Jarvest")
        }
        androidx.compose.material3.OutlinedButton(enabled = !exact, onClick = { Reminders.requestExactAlarms(context) }) {
            androidx.compose.material3.Text(if (exact) "Exact alarms: allowed" else "Allow exact alarms")
        }
    }
}
