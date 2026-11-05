package dev.goaltracker.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.goaltracker.core.CoreException

/** A small dialog for logging a numeric goal from the widget without opening the app. */
class QuickLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GoalTrackerApp
        val goal = intent.getStringExtra(EXTRA_GOAL_ID)?.let { id ->
            try {
                app.core.getGoal(id)
            } catch (_: CoreException) {
                null
            }
        }
        if (goal == null) {
            app.refreshWidgets()
            finish()
            return
        }
        setContent {
            GoalTrackerTheme(app.controller.themeMode, app.controller.accent) {
                LogAmountDialog(
                    title = goal.title,
                    unit = goal.unit,
                    onLog = { amount ->
                        try {
                            app.core.logAmount(goal.id, amount, todayString())
                            app.refreshWidgets()
                        } catch (e: CoreException) {
                            Toast.makeText(this, e.userMessage(), Toast.LENGTH_LONG).show()
                        }
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_GOAL_ID = "goal_id"
    }
}
