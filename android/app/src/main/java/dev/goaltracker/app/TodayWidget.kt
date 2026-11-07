package dev.goaltracker.app

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
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
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.TodayItem

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    // The periodic update also has to reload the list, so it rolls over after midnight.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as GoalTrackerApp).widgetDataChanged()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

/** Today's checklist. Yes/no goals and habits tick in place; numeric goals open quick entry. */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GoalTrackerApp
        provideContent {
            // A running widget session outlives updates, so the list reloads on each change.
            val dataVersion by app.widgetDataVersion.collectAsState()
            val today = todayString()
            val items = remember(dataVersion, today) {
                try {
                    app.core.today(today)
                } catch (_: CoreException) {
                    emptyList()
                }
            }
            val tasksDue = remember(dataVersion, today) {
                try {
                    app.core.dueTasks(today).size
                } catch (_: CoreException) {
                    0
                }
            }
            val percent = remember(dataVersion, today) {
                try {
                    app.core.dayScore(today).takeIf { it.possible > 0u }?.percent?.toInt()
                } catch (_: CoreException) {
                    null
                }
            }
            GlanceTheme { WidgetContent(items, tasksDue, percent) }
        }
    }
}

private val goalIdKey = ActionParameters.Key<String>(QuickLogActivity.EXTRA_GOAL_ID)

@Composable
private fun WidgetContent(items: List<TodayItem>, tasksDue: Int, percent: Int?) {
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp),
    ) {
        Text(
            "Today" + (if (items.isNotEmpty()) ": ${items.count { it.complete }} of ${items.size} done" else "") +
                (if (percent != null) " · $percent%" else ""),
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp).clickable(actionStartActivity<MainActivity>()),
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
        )
        if (tasksDue > 0) {
            Text(
                "${if (tasksDue == 1) "1 task" else "$tasksDue tasks"} due. Tap to open.",
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp).clickable(actionStartActivity<MainActivity>()),
                style = TextStyle(color = GlanceTheme.colors.primary),
            )
        }
        if (items.isEmpty()) {
            Text(
                if (tasksDue > 0) "No goals are due." else "You're all caught up",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            LazyColumn {
                items(items, itemId = { it.goalId.hashCode().toLong() }) { item -> WidgetRow(item) }
            }
        }
    }
}

@Composable
private fun WidgetRow(item: TodayItem) {
    val params = actionParametersOf(goalIdKey to item.goalId)
    if (item.kind == GoalKind.NUMERIC) {
        Row(
            GlanceModifier.fillMaxWidth().padding(vertical = 10.dp).clickable(actionStartActivity<QuickLogActivity>(params)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "+  ${item.title}" + (progressLine(item)?.let { " ($it)" } ?: ""),
                style = TextStyle(color = GlanceTheme.colors.onSurface),
                maxLines = 1,
            )
        }
    } else {
        CheckBox(
            checked = item.loggedToday,
            onCheckedChange = actionRunCallback<ToggleCheckInAction>(params),
            text = item.title,
            modifier = GlanceModifier.fillMaxWidth(),
            maxLines = 1,
        )
    }
}

class ToggleCheckInAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val goalId = parameters[goalIdKey] ?: return
        val app = context.applicationContext as GoalTrackerApp
        try {
            // A won treat stays unseen and is revealed the next time the app opens.
            app.core.toggleCheckInAt(goalId, todayString(), nowMinute())
        } catch (_: CoreException) {
            // The goal was archived or deleted since the widget last drew. Redrawing fixes it.
        }
        app.refreshWidgets()
    }
}
