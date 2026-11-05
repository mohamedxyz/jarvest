package dev.goaltracker.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import dev.goaltracker.core.GoalCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

class GoalTrackerApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The one Rust core shared by the app and widget. */
    val core: GoalCore by lazy {
        val file = getDatabasePath("goals.db")
        file.parentFile?.mkdirs()
        GoalCore.open(file.path)
    }

    val controller: AppController by lazy { AppController(this) }

    private val _widgetDataVersion = MutableStateFlow(0)
    val widgetDataVersion: StateFlow<Int> = _widgetDataVersion

    fun widgetDataChanged() {
        _widgetDataVersion.update { it + 1 }
    }

    fun refreshWidgets() {
        widgetDataChanged()
        scope.launch { TodayWidget().updateAll(this@GoalTrackerApp) }
    }
}

/** The phone's current local date, which is what defines "today" in the core. */
fun todayString(): String = LocalDate.now().toString()
