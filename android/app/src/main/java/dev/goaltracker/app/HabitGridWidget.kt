package dev.goaltracker.app

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.Goal
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.Period
import java.time.LocalDate
import java.time.format.TextStyle as DayStyle
import java.util.Locale

/** How many days the widget shows, newest on the right. */
private const val WIDGET_DAYS = 5

class HabitGridWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitGridWidget()

    // The periodic update also reloads, so the days roll over after midnight.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as GoalTrackerApp).widgetDataChanged()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

/** One habit's row: its check-ins per day, oldest first. */
private class GridRow(val goal: Goal, val counts: List<Int>, val needed: Int)

/** The Habits tab grid on the home screen. Tap a day to tick it, or untick it. */
class HabitGridWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GoalTrackerApp
        provideContent {
            val dataVersion by app.widgetDataVersion.collectAsState()
            val today = todayString()
            val days = remember(today) {
                val now = LocalDate.parse(today)
                (WIDGET_DAYS - 1 downTo 0).map { now.minusDays(it.toLong()) }
            }
            val rows = remember(dataVersion, today) {
                try {
                    app.core.listGoals(false).filter { it.kind == GoalKind.HABIT }.map { goal ->
                        val logged = app.core.heatmap(goal.id, days.first().toString(), today)
                            .associate { it.date to it.count.toInt() }
                        val needed = if (goal.period == Period.DAY && !goal.avoid) {
                            (goal.periodTarget ?: 1.0).toInt().coerceAtLeast(1)
                        } else 1
                        GridRow(goal, days.map { logged[it.toString()] ?: 0 }, needed)
                    }
                } catch (_: CoreException) {
                    emptyList()
                }
            }
            GlanceTheme { GridContent(rows, days) }
        }
    }
}

private val gridGoalKey = ActionParameters.Key<String>("goal_id")
private val gridDayKey = ActionParameters.Key<String>("day")

@Composable
private fun GridContent(rows: List<GridRow>, days: List<LocalDate>) {
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(10.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Habits",
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
                style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
            )
            days.forEach { day ->
                Text(
                    day.dayOfWeek.getDisplayName(DayStyle.NARROW, Locale.getDefault()),
                    modifier = GlanceModifier.width(32.dp),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
        }
        if (rows.isEmpty()) {
            Text("No habits yet. Add one in the app.", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
        } else {
            LazyColumn {
                items(rows, itemId = { it.goal.id.hashCode().toLong() }) { row -> GridRowView(row, days) }
            }
        }
    }
}

@Composable
private fun GridRowView(row: GridRow, days: List<LocalDate>) {
    Row(GlanceModifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            row.goal.title.withIcon(row.goal.icon),
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = GlanceTheme.colors.onSurface),
            maxLines = 1,
        )
        days.forEachIndexed { index, day ->
            val iso = day.toString()
            val taps = row.counts[index]
            // Days before the habit existed can't be logged.
            val open = iso >= row.goal.createdDate
            val good = if (row.goal.avoid) taps == 0 else taps >= row.needed
            val mark = when {
                !open -> ""
                row.goal.avoid -> if (good) "✓" else "✕"
                good -> "✓"
                taps > 0 -> "$taps/${row.needed}"
                else -> "·"
            }
            Box(
                GlanceModifier.width(32.dp).height(36.dp).then(
                    if (open) {
                        GlanceModifier.clickable(
                            actionRunCallback<GridToggleAction>(actionParametersOf(gridGoalKey to row.goal.id, gridDayKey to iso)),
                        )
                    } else GlanceModifier,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mark,
                    style = TextStyle(
                        color = if (good && open) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                        fontWeight = if (good) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
            }
        }
    }
}

class GridToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val goalId = parameters[gridGoalKey] ?: return
        val day = parameters[gridDayKey] ?: return
        val app = context.applicationContext as GoalTrackerApp
        val today = todayString()
        try {
            // A won treat stays unseen and is revealed the next time the app opens.
            if (day == today) app.core.toggleCheckInAt(goalId, today, nowMinute())
            else app.core.toggleCheckInOn(goalId, day, today)
        } catch (_: CoreException) {
            // The habit was archived or deleted since the widget last drew. Redrawing fixes it.
        }
        app.refreshWidgets()
    }
}
