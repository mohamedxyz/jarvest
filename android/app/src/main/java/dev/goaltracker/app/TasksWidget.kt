package dev.goaltracker.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.Task

private val quickAddKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_QUICK_ADD)

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()

    // The periodic update reloads the list, so it rolls over after midnight.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as GoalTrackerApp).widgetDataChanged()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

/** Open tasks due today or earlier. Ticking one here finishes it, with no need to open the app. */
class TasksWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GoalTrackerApp
        provideContent {
            val dataVersion by app.widgetDataVersion.collectAsState()
            val today = todayString()
            val tasks = remember(dataVersion, today) {
                try {
                    app.core.dueTasks(today)
                } catch (_: CoreException) {
                    emptyList()
                }
            }
            GlanceTheme { TasksContent(tasks) }
        }
    }
}

private val taskIdKey = ActionParameters.Key<String>("task_id")

@Composable
private fun TasksContent(tasks: List<Task>) {
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp),
    ) {
        Text(
            "Tasks" + if (tasks.isNotEmpty()) ": ${tasks.size} due" else "",
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp).clickable(actionStartActivity<MainActivity>()),
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
        )
        if (tasks.isEmpty()) {
            Text("Nothing due. Tap to open.", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
        } else {
            LazyColumn {
                items(tasks, itemId = { it.id.hashCode().toLong() }) { task ->
                    CheckBox(
                        checked = false,
                        onCheckedChange = actionRunCallback<ToggleTaskAction>(actionParametersOf(taskIdKey to task.id)),
                        text = task.title + if (task.overdue) " (overdue)" else "",
                        modifier = GlanceModifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdKey] ?: return
        val app = context.applicationContext as GoalTrackerApp
        try {
            // A task that mirrors a milestone can win a treat. It stays unseen until the app opens.
            app.core.toggleTask(taskId, todayString())
        } catch (_: CoreException) {
            // The task was deleted since the widget last drew. Redrawing fixes it.
        }
        app.refreshWidgets()
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}

/** A small button that opens the app on the new task screen. */
class QuickAddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Box(
                    GlanceModifier.fillMaxSize().background(GlanceTheme.colors.primaryContainer).cornerRadius(16.dp)
                        .padding(8.dp).clickable(actionStartActivity<MainActivity>(actionParametersOf(quickAddKey to true))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+ Add task",
                        style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimaryContainer),
                    )
                }
            }
        }
    }
}
