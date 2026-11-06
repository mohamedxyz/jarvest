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
import androidx.glance.appwidget.lazy.LazyColumn
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
import dev.goaltracker.core.CalendarBlock
import dev.goaltracker.core.CoreException
import dev.goaltracker.core.DailyQuest
import dev.goaltracker.core.DayScore
import dev.goaltracker.core.Hero

class ScoreWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScoreWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as GoalTrackerApp).widgetDataChanged()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

/** Today's score, level, XP and daily quest. It only reads, so it never changes the score. */
class ScoreWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GoalTrackerApp
        provideContent {
            val dataVersion by app.widgetDataVersion.collectAsState()
            val today = todayString()
            val data = remember(dataVersion, today) {
                try {
                    Triple(app.core.dayScore(today), app.core.hero(), app.core.dailyQuest(today))
                } catch (_: CoreException) {
                    null
                }
            }
            GlanceTheme { ScoreContent(data?.first, data?.second, data?.third) }
        }
    }
}

@Composable
private fun ScoreContent(score: DayScore?, hero: Hero?, quest: DailyQuest?) {
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            if (score == null || score.possible == 0u) "Nothing planned today" else "Today ${score.percent}%",
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
        )
        if (hero != null) {
            Text(
                "Level ${hero.level} ${hero.title} · ${hero.xp} XP",
                style = TextStyle(color = GlanceTheme.colors.primary),
            )
        }
        if (quest != null) {
            Text(
                (if (quest.done) "Quest done: " else "Quest: ") + "${quest.text} (${quest.progress}/${quest.target})",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 2,
            )
        }
    }
}

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as GoalTrackerApp).widgetDataChanged()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

/** Today's calendar: timed blocks first, then "anytime". Tapping opens the app. */
class AgendaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GoalTrackerApp
        provideContent {
            val dataVersion by app.widgetDataVersion.collectAsState()
            val today = todayString()
            val blocks = remember(dataVersion, today) {
                try {
                    app.core.calendarDay(today).filter { !it.done }
                } catch (_: CoreException) {
                    emptyList()
                }
            }
            GlanceTheme { AgendaContent(blocks) }
        }
    }
}

@Composable
private fun AgendaContent(blocks: List<CalendarBlock>) {
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            "Agenda",
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
        )
        if (blocks.isEmpty()) {
            Text("Nothing left on the calendar today.", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
        } else {
            LazyColumn {
                items(blocks.size) { i ->
                    val b = blocks[i]
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            b.startMinutes?.let { Reminders.formatMinutes(it.toInt()) } ?: "Anytime",
                            modifier = GlanceModifier.padding(end = 8.dp),
                            style = TextStyle(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary),
                        )
                        Text(b.title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface))
                    }
                }
            }
        }
    }
}
