package dev.goaltracker.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.GoalCore
import dev.goaltracker.core.LogResult
import dev.goaltracker.core.RewardDraw

/**
 * The bridge between screens and the Rust core. Screens read through [read] and
 * change data through [act] or [log]. Each change bumps [version], and screens
 * reload whatever depends on it.
 */
class UndoOffer(val text: String, val action: () -> Unit)

class AppController(private val app: GoalTrackerApp) {
    val core: GoalCore get() = app.core

    var version by mutableIntStateOf(0)
        private set
    var today by mutableStateOf(todayString())
        private set
    var message by mutableStateOf<String?>(null)
    var pendingDraw by mutableStateOf<RewardDraw?>(null)
        private set
    var showJarPrompt by mutableStateOf(false)

    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(readEnum("theme", ThemeMode.SYSTEM))
        private set
    var accent by mutableStateOf(readEnum("accent", Accent.BLUE))
        private set

    fun chooseTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun chooseAccent(choice: Accent) {
        accent = choice
        prefs.edit().putString("accent", choice.name).apply()
    }

    /** Tip cards the user has closed. Kept on the phone only, so backups don't carry it. */
    var dismissedTips by mutableStateOf(prefs.getStringSet("dismissed_tips", emptySet()) ?: emptySet())
        private set

    fun isTipDismissed(id: String) = id in dismissedTips

    fun dismissTip(id: String) {
        dismissedTips = dismissedTips + id
        prefs.edit().putStringSet("dismissed_tips", dismissedTips).apply()
    }

    fun resetTips() {
        dismissedTips = emptySet()
        prefs.edit().remove("dismissed_tips").apply()
    }

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == prefs.getString(key, null) } ?: default

    /** Counts typed in on numeric goals but not submitted yet, by goal ID. */
    val counters = mutableStateMapOf<String, Int>()

    /** Logs a goal's counter as one entry, then resets it. Returns whether it worked. */
    fun submitCounter(goalId: String): Boolean {
        val count = counters[goalId] ?: 0
        if (count <= 0) return false
        val ok = log { logAmount(goalId, count.toDouble(), todayString()) }
        if (ok) counters[goalId] = 0
        return ok
    }

    // One prompt per app session is enough of a nudge.
    private var jarPromptUsed = false

    fun <T> read(fallback: T, block: GoalCore.() -> T): T = try {
        core.block()
    } catch (e: CoreException) {
        // Reads run during composition, so the message is shown on the next frame.
        Handler(Looper.getMainLooper()).post { message = e.userMessage() }
        fallback
    }

    /** Runs a change and returns whether it succeeded. */
    fun act(block: GoalCore.() -> Unit): Boolean = try {
        core.block()
        refresh()
        true
    } catch (e: CoreException) {
        message = e.userMessage()
        false
    }

    /** Like [act], for a change that returns a value. Returns null if it failed. */
    fun <T> change(block: GoalCore.() -> T): T? = try {
        val result = core.block()
        refresh()
        result
    } catch (e: CoreException) {
        message = e.userMessage()
        null
    }

    /** A message with an Undo button, such as after moving a task to the trash. */
    var undo by mutableStateOf<UndoOffer?>(null)

    /**
     * Ticks or unticks a task and always offers Undo, so a stray tap is easy to fix.
     * Finishing a repeating task also says when the next one is due.
     */
    fun toggleTaskWithUndo(taskId: String, title: String) {
        val result = change { toggleTask(taskId, today) } ?: return
        // A task that mirrors a milestone can win or lose a treat, like the milestone itself.
        reportReward(result.reward)
        val text = when {
            !result.done -> "\"$title\" is open again."
            result.nextDue != null -> "\"$title\" done. Next one is due ${formatDate(result.nextDue!!)}."
            else -> "\"$title\" done."
        }
        undo = UndoOffer(text) { change { toggleTask(taskId, today) } }
    }

    /** Runs a logging action, then checks whether it earned a treat. */
    fun log(block: GoalCore.() -> LogResult): Boolean = try {
        val result = core.block()
        reportReward(result)
        refresh()
        true
    } catch (e: CoreException) {
        message = e.userMessage()
        false
    }

    /** Prompts for a treat, or says what happened to the reward, so a missing treat never looks like a bug. */
    private fun reportReward(result: LogResult) {
        if (result.jarEmpty && !jarPromptUsed) {
            jarPromptUsed = true
            showJarPrompt = true
        }
        if (result.treatsRemoved > 0u) {
            message = "Unticked. The treat that target won was removed too."
        } else if (result.noTreat > 0u) {
            val left = core.rewardProgress().guaranteedIn.toInt()
            message = "Target met. No treat this time. " +
                if (left <= 1) "Your next target is guaranteed to win one."
                else "A treat is guaranteed within $left more targets."
        }
    }

    /** Called when the app returns to the front, since the widget or date may have changed. */
    fun onResume() {
        today = todayString()
        version++
        loadPendingDraw()
    }

    fun refresh() {
        // Edits can add, move, or remove reminders, so the alarms are rebuilt each time.
        Reminders.rescheduleAll(app)
        version++
        loadPendingDraw()
        app.refreshWidgets()
    }

    fun dismissDraw() {
        val draw = pendingDraw ?: return
        pendingDraw = null
        read(Unit) { markDrawSeen(draw.id) }
        version++
        loadPendingDraw()
    }

    private fun loadPendingDraw() {
        pendingDraw = read(emptyList()) { unseenDraws() }.firstOrNull()
    }
}

fun CoreException.userMessage(): String = when (this) {
    is CoreException.Invalid -> reason
    is CoreException.InvalidBackup -> "That file can't be restored: $reason."
    is CoreException.Database -> {
        Log.e("GoalTracker", detail)
        "Something went wrong with your data. Please try again."
    }
}
