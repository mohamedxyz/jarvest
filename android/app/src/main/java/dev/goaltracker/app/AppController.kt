package dev.goaltracker.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    /** Runs a logging action, then checks whether it earned a treat. */
    fun log(block: GoalCore.() -> LogResult): Boolean = try {
        val result = core.block()
        if (result.jarEmpty && !jarPromptUsed) {
            jarPromptUsed = true
            showJarPrompt = true
        }
        refresh()
        true
    } catch (e: CoreException) {
        message = e.userMessage()
        false
    }

    /** Called when the app returns to the front, since the widget or date may have changed. */
    fun onResume() {
        today = todayString()
        version++
        loadPendingDraw()
    }

    fun refresh() {
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
