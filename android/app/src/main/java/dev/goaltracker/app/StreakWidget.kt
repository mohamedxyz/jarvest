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
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.GoalStats

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as GoalTrackerApp).widgetDataChanged()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

/** The longest running streaks among active goals. Tapping opens the app. */
class StreakWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GoalTrackerApp
        provideContent {
            val dataVersion by app.widgetDataVersion.collectAsState()
            val today = todayString()
            val streaks = remember(dataVersion, today) {
                try {
                    app.core.statsOverview(today).goals
                        .filter { it.currentStreak > 0u }
                        .sortedByDescending { it.currentStreak }
                        .take(5)
                } catch (_: CoreException) {
                    emptyList()
                }
            }
            val momentum = remember(dataVersion, today) {
                try {
                    app.core.momentum(today)
                } catch (_: CoreException) {
                    null
                }
            }
            GlanceTheme(colors = WidgetColors) { StreakContent(streaks, momentum?.let { momentumShort(it) }) }
        }
    }
}

@Composable
private fun StreakContent(streaks: List<GoalStats>, status: String?) {
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            "Streaks",
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
        )
        // The same verdict as the Stats screen, in words, so it never depends on color.
        status?.let {
            Text(
                it,
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
                style = TextStyle(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary),
            )
        }
        if (streaks.isEmpty()) {
            Text("No streaks yet. Hit a target to start one.", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
        } else {
            streaks.forEach { stats ->
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        stats.title,
                        modifier = GlanceModifier.defaultWeight(),
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurface),
                    )
                    Text(
                        "${stats.currentStreak} ${stats.period.noun()}${if (stats.currentStreak == 1u) "" else "s"}",
                        style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary),
                    )
                }
            }
        }
    }
}
