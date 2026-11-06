package dev.goaltracker.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
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
    val motion = LocalMotion.current
    // The ring and the number fill up from 0 the first time the card shows, then follow changes.
    var started by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { started = true }
    val shown by animateFloatAsState(
        targetValue = if (started) score.percent.toFloat() else 0f,
        animationSpec = motionTween(motion, 900),
        label = "score",
    )
    // The level name pops a little whenever the level changes.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(score.level) {
        if (motion) {
            pop.snapTo(0.75f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    SoftCard(onClick = { help = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)), modifier = Modifier.fillMaxWidth().label("Day score ${score.percent} percent, ${score.level.title()}")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(72.dp)) {
                    val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                    val inset = stroke.width / 2
                    val box = Size(size.width - stroke.width, size.height - stroke.width)
                    drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = box, style = stroke)
                    if (shown > 0f) {
                        drawArc(color, -90f, 360f * shown / 100f, false, topLeft = Offset(inset, inset), size = box, style = stroke)
                    }
                }
                Text("${shown.toInt()}%", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${score.level.icon()} ${score.level.title()} day",
                    Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = TransformOrigin(0f, 0.5f) },
                    style = MaterialTheme.typography.titleLarge,
                    color = color,
                )
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

private fun greeting(): String {
    val hour = java.time.LocalTime.now().hour
    return when {
        hour < 5 -> "Still up?"
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}

/** The top of Today: a soft gradient with a greeting, the date, and the day score. */
@Composable
fun TodayHero(controller: AppController) {
    val top = MaterialTheme.colorScheme.primaryContainer
    val bottom = MaterialTheme.colorScheme.surface
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(top, bottom)), RoundedCornerShape(28.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(greeting(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(formatDate(controller.today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        DayScoreCard(controller)
        HeroCard(controller)
        WeeklyBossCard(controller)
    }
}

/** Lifetime level and XP. XP is every day's points added up, and it never goes down. */
@Composable
fun HeroCard(controller: AppController) {
    val hero = remember(controller.version) { controller.read(null) { hero() } } ?: return
    val motion = LocalMotion.current
    var started by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { started = true }
    val fraction = if (hero.xpForNext == 0u) 0f else hero.xpIntoLevel.toFloat() / hero.xpForNext.toFloat()
    val filled by animateFloatAsState(if (started) fraction else 0f, motionTween(motion, 800), label = "xp")
    SoftCard(Modifier.fillMaxWidth().label("Level ${hero.level} ${hero.title}, ${hero.xp} XP")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛡️ Level ${hero.level} · ${hero.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("${hero.xp} XP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { filled },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                strokeCap = StrokeCap.Round,
            )
            Text(
                "${hero.xpForNext - hero.xpIntoLevel} XP to level ${hero.level + 1u}. Day-score points become XP and are never lost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** This week's boss. Points you earn this week are the damage. Beating it wins a treat. */
@Composable
fun WeeklyBossCard(controller: AppController) {
    val boss = remember(controller.version, controller.today) { controller.read(null) { weeklyBoss(controller.today) } } ?: return
    val motion = LocalMotion.current
    var started by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { started = true }
    val left = if (boss.health == 0u) 0f else 1f - boss.damage.toFloat() / boss.health.toFloat()
    val shown by animateFloatAsState(if (started) left else 1f, motionTween(motion, 800), label = "boss")
    val down = boss.defeatedDate != null
    SoftCard(Modifier.fillMaxWidth().label("Weekly boss ${boss.name}, ${boss.damage} of ${boss.health} damage")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("👹 Weekly boss: ${boss.name}", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = MaterialTheme.colorScheme.error,
                strokeCap = StrokeCap.Round,
            )
            Text(
                if (down) "Beaten on ${formatDate(boss.defeatedDate!!)}. A new boss comes on Monday."
                else "${boss.health - boss.damage} health left · ${boss.daysLeft} ${if (boss.daysLeft == 1u) "day" else "days"} to go. Your points hurt it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
