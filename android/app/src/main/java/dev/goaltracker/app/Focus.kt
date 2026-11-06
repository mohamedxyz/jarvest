package dev.goaltracker.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.FocusKind
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * The focus timer. The running timer is only a few values in SharedPreferences, so it survives the
 * app being closed. An alarm ends it, and an ongoing notification counts down. There is deliberately
 * no foreground service. Only the finished or stopped session is saved to the database.
 */
object FocusTimer {
    const val ALARM_KEY = "focus_end"
    private const val PREFS = "focus"
    private const val CHANNEL = "focus"
    private const val NOTIFICATION_ID = 7001

    data class Running(val kind: FocusKind, val itemId: String, val title: String, val startMillis: Long, val plannedMinutes: Int) {
        val endMillis get() = startMillis + plannedMinutes * 60_000L
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun running(context: Context): Running? {
        val p = prefs(context)
        val id = p.getString("id", null) ?: return null
        return Running(
            if (p.getString("kind", "task") == "goal") FocusKind.GOAL else FocusKind.TASK,
            id, p.getString("title", "") ?: "", p.getLong("start", 0), p.getInt("minutes", 25),
        )
    }

    fun start(context: Context, kind: FocusKind, itemId: String, title: String, minutes: Int) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString("kind", if (kind == FocusKind.GOAL) "goal" else "task")
            .putString("id", itemId).putString("title", title)
            .putLong("start", now).putInt("minutes", minutes).apply()
        Reminders.scheduleAt(context, ALARM_KEY, now + minutes * 60_000L)
        showOngoing(context, title, now + minutes * 60_000L)
    }

    private fun showOngoing(context: Context, title: String, endMillis: Long) {
        if (!Reminders.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Focus timer", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Focusing: $title")
            .setContentText("Tap to open the timer")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The system draws the countdown itself, so the app does not need to run.
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(endMillis)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Saves the session (as many whole minutes as passed, at most the planned time) and clears the timer. */
    fun finish(context: Context, save: Boolean): Int {
        val r = running(context) ?: return 0
        val elapsed = ((System.currentTimeMillis() - r.startMillis) / 60_000L).toInt().coerceIn(0, r.plannedMinutes)
        clear(context)
        if (!save || elapsed < 1) return 0
        val app = context.applicationContext as GoalTrackerApp
        val start = Instant.ofEpochMilli(r.startMillis).atZone(ZoneId.systemDefault())
        return try {
            app.core.recordFocus(r.kind, r.itemId, r.title, start.toLocalDate().toString(), (start.hour * 60 + start.minute).toUInt(), elapsed.toUInt())
            elapsed
        } catch (_: CoreException) {
            0
        }
    }

    private fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        Reminders.cancel(context, ALARM_KEY)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** The alarm: the planned time is up. Saves the whole session and says so. */
    fun onAlarm(context: Context) {
        val r = running(context) ?: return
        val minutes = finish(context, save = true)
        if (Reminders.canNotify(context)) {
            Reminders.notify(context, "focus_done", "Focus session done: $minutes min on ${r.title}")
        }
    }
}

private fun clock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/** The focus screen: pick a length and start, watch the countdown, stop and save early. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(controller: AppController, kind: FocusKind, itemId: String, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val title = remember(itemId) {
        controller.read("") {
            if (kind == FocusKind.GOAL) getGoal(itemId).title else getTask(itemId, controller.today).title
        }
    }
    val total = remember(itemId, controller.version) { controller.read(0u) { focusTotal(itemId) } }
    var minutes by rememberSaveable { mutableStateOf(25) }
    var running by remember { mutableStateOf(FocusTimer.running(context)?.takeIf { it.itemId == itemId }) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks once a second while the screen is open. The timer itself does not depend on this.
    LaunchedEffect(running) {
        while (running != null) {
            now = System.currentTimeMillis()
            if (now >= running!!.endMillis) {
                // The alarm may be late, so the screen finishes the session itself if it is open.
                val saved = FocusTimer.finish(context, save = true)
                running = null
                controller.message = "Focus session done: $saved min"
                controller.refresh()
                break
            }
            delay(1000)
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        val r = running
        if (r == null) {
            Text(clock(minutes * 60L), fontSize = 64.sp, style = MaterialTheme.typography.displayMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60, 90).forEach { m ->
                    FilterChip(minutes == m, { minutes = m }, label = { Text("$m min") })
                }
            }
            Button(onClick = {
                if (FocusTimer.running(context) != null) {
                    controller.message = "Another focus timer is already running. Stop it first."
                } else {
                    FocusTimer.start(context, kind, itemId, title, minutes)
                    running = FocusTimer.running(context)
                }
            }, Modifier.fillMaxWidth()) { Text("Start focus") }
        } else {
            Text(clock((r.endMillis - now) / 1000), fontSize = 64.sp, style = MaterialTheme.typography.displayMedium)
            Text("${r.plannedMinutes} min session", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                val saved = FocusTimer.finish(context, save = true)
                running = null
                controller.message = if (saved > 0) "Saved $saved min of focus" else "Under a minute, so nothing was saved"
                controller.refresh()
            }, Modifier.fillMaxWidth()) { Text("Stop and save") }
            OutlinedButton(onClick = { FocusTimer.finish(context, save = false); running = null }, Modifier.fillMaxWidth()) {
                Text("Cancel without saving")
            }
        }
        if (total > 0u) {
            Text("${total} min focused here in total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Focus time is only recorded. It never changes your score, XP or treats.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onDone) { Text("Back") }
    }
}
