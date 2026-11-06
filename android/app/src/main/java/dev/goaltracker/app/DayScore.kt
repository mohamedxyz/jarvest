package dev.goaltracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.DayLevel
import dev.goaltracker.core.DayScore

private fun DayLevel.title() = when (this) {
    DayLevel.NONE -> "Ready"
    DayLevel.SPARK -> "Spark"
    DayLevel.BLAZE -> "Blaze"
    DayLevel.TITAN -> "Titan"
}

private fun DayLevel.icon() = when (this) {
    DayLevel.NONE -> "🌅"
    DayLevel.SPARK -> "⚡"
    DayLevel.BLAZE -> "🔥"
    DayLevel.TITAN -> "⚔️"
}

private fun DayLevel.color() = when (this) {
    DayLevel.NONE -> Color(0xFF78909C)
    DayLevel.SPARK -> Color(0xFFF9A825)
    DayLevel.BLAZE -> Color(0xFFEF6C00)
    DayLevel.TITAN -> Color(0xFF2E7D32)
}

/** What to say under the level: the next step, or a note that the day is done. */
private fun nextStep(score: DayScore): String = when {
    score.possible == 0u -> "Nothing is planned yet. Add a task or tick a goal."
    score.level == DayLevel.TITAN -> "Everything planned is done. Enjoy the rest of your day."
    score.level == DayLevel.NONE -> "Do one thing to light the first spark."
    else -> {
        val next = if (score.level == DayLevel.SPARK) "Blaze" else "Titan"
        "${score.pointsToNext} points to $next"
    }
}

/** How the day is going: a ring with the percent, the level, and what is done so far. */
@Composable
fun DayScoreCard(controller: AppController) {
    var help by remember { mutableStateOf(false) }
    val score = remember(controller.version, controller.today) {
        controller.read(null) { dayScore(controller.today) }
    } ?: return
    val color = score.level.color()
    val track = MaterialTheme.colorScheme.outlineVariant
    Card(onClick = { help = true }, modifier = Modifier.fillMaxWidth().label("Day score ${score.percent} percent, ${score.level.title()}")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(72.dp)) {
                    val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                    val inset = stroke.width / 2
                    val box = Size(size.width - stroke.width, size.height - stroke.width)
                    drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = box, style = stroke)
                    if (score.percent > 0u) {
                        drawArc(color, -90f, 360f * score.percent.toFloat() / 100f, false, topLeft = Offset(inset, inset), size = box, style = stroke)
                    }
                }
                Text("${score.percent}%", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${score.level.icon()} ${score.level.title()} day", style = MaterialTheme.typography.titleLarge, color = color)
                Text(
                    buildList {
                        if (score.goalsTotal > 0u) add("${score.goalsDone} of ${score.goalsTotal} goals")
                        if (score.tasksTotal > 0u) add("${score.tasksDone} of ${score.tasksTotal} tasks")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(nextStep(score), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (help) {
        InfoSheet("How the day score works", onDismiss = { help = false }) {
            InfoRow("Points", "Each goal or habit on today's list is worth 10. A task is worth 5, 10 or 15 for low, medium or high priority.")
            InfoRow("Planned", "Goals due today, tasks due today or overdue, and tasks you finished today. Milestone tasks count on their goal.")
            InfoRow("Levels", "Spark from 1%, Blaze from 50%, Titan when everything planned is done.")
            InfoRow("Safe", "The score only describes your day. It never changes your goals, streaks or treats.")
        }
    }
}
