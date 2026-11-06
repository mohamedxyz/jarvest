package dev.goaltracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.Goal
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** How many day columns the grid shows at once. */
private const val DAYS = 5

private val CELL_WIDTH = 44.dp

/** Habit colors. A habit keeps the same color because it comes from the habit's id. */
private val palette = listOf(
    Color(0xFF1E88E5), Color(0xFFD81B60), Color(0xFFF57C00), Color(0xFF5E35B1),
    Color(0xFF00897B), Color(0xFF7CB342), Color(0xFF6D4C41), Color(0xFF3949AB),
)

private fun colorOf(goal: Goal): Color = palette[abs(goal.id.hashCode()) % palette.size]

/**
 * Habits as a grid: one row per habit, one column per day, newest day first. Tap a cell to
 * log or unlog that day. Swipe sideways, or use the arrows, to see older days.
 */
@Composable
fun HabitGrid(controller: AppController, goals: List<Goal>, onOpenGoal: (String) -> Unit) {
    // 0 shows the newest days. Each step goes back one page of DAYS days.
    var page by rememberSaveable { mutableIntStateOf(0) }
    val today = LocalDate.parse(controller.today)
    val days = remember(controller.today, page) {
        val newest = today.minusDays((page * DAYS).toLong())
        (0 until DAYS).map { newest.minusDays(it.toLong()) }
    }
    val from = days.last().toString()
    val to = days.first().toString()

    // Which days each habit was logged on. For a quit habit a logged day is a slip.
    val logged = remember(controller.version, goals.map { it.id }, from, to) {
        goals.associate { goal ->
            goal.id to controller.read(emptyList()) { heatmap(goal.id, from, to) }
                .filter { it.count > 0u }.map { it.date }.toSet()
        }
    }
    // The ring shows this period's progress. A habit not due today falls back to its record.
    val progressOf = remember(controller.version, controller.today) {
        val items = controller.read(emptyList()) { today(controller.today) }.associateBy { it.goalId }
        val stats = controller.read(null) { statsOverview(controller.today) }?.goals?.associateBy { it.goalId }.orEmpty()
        goals.associate { goal ->
            val item = items[goal.id]
            val target = item?.periodTarget
            val value = when {
                item != null && target != null && target > 0.0 -> (item.periodProgress / target).toFloat()
                else -> stats[goal.id]?.let { if (it.eligible > 0u) it.met.toFloat() / it.eligible.toFloat() else 0f } ?: 0f
            }
            goal.id to value.coerceIn(0f, 1f)
        }
    }

    var drag = 0f
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = {
                    // Dragging right pulls older days in. Dragging left goes back to newer ones.
                    if (drag > 120f) page++ else if (drag < -120f && page > 0) page--
                },
            ) { _, amount -> drag += amount }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Show older days") }
            IconButton(onClick = { if (page > 0) page-- }, enabled = page > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Show newer days")
            }
            Spacer(Modifier.weight(1f))
            days.forEach { day ->
                val isToday = day == today
                Column(Modifier.width(CELL_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(goals, key = { it.id }) { goal ->
                val color = colorOf(goal)
                val marks = logged[goal.id].orEmpty()
                Row(
                    softItem().fillMaxWidth().height(56.dp).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).clickable { onOpenGoal(goal.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProgressRing(progressOf[goal.id] ?: 0f, color)
                        Text(
                            goal.title.withIcon(goal.icon),
                            Modifier.padding(start = 12.dp, end = 4.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = color,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    days.forEach { day ->
                        val iso = day.toString()
                        // Days before the habit existed and days to come can't be logged.
                        val open = iso >= goal.createdDate && day <= today
                        DayCell(goal, iso, open, iso in marks, color) {
                            controller.log { toggleCheckInOn(goal.id, iso, controller.today) }
                        }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun DayCell(goal: Goal, day: String, open: Boolean, marked: Boolean, color: Color, onToggle: () -> Unit) {
    val faded = MaterialTheme.colorScheme.outlineVariant
    // A habit is "done" when logged. A quit habit is "clean" when nothing is logged.
    val good = if (goal.avoid) !marked else marked
    val what = when {
        !open -> ""
        goal.avoid -> if (marked) "Slipped" else "Clean"
        else -> if (marked) "Done" else "Not done"
    }
    Box(
        Modifier.width(CELL_WIDTH).height(56.dp)
            .then(if (open) Modifier.clickable(onClickLabel = "Toggle ${goal.title} on $day", onClick = onToggle) else Modifier)
            .label(if (open) "${goal.title}, $day: $what" else "${goal.title}, $day: not available"),
        contentAlignment = Alignment.Center,
    ) {
        if (open) {
            if (good) {
                Icon(Icons.Filled.Check, null, Modifier.size(24.dp), tint = if (goal.avoid) color.copy(alpha = 0.6f) else color)
            } else {
                Icon(
                    Icons.Filled.Close,
                    null,
                    Modifier.size(22.dp),
                    tint = if (goal.avoid) MaterialTheme.colorScheme.error else faded,
                )
            }
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, color: Color) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(22.dp)) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val box = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(track, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = box, style = stroke)
        if (progress > 0f) {
            drawArc(color, -90f, 360f * progress, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = box, style = stroke)
        }
    }
}
