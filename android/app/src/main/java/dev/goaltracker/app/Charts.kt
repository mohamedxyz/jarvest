package dev.goaltracker.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.Period
import dev.goaltracker.core.PeriodPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Total logged per period as a line, with a dashed line at the target when there is one.
 * A text summary is attached for screen readers, since a canvas has no readable content.
 */
@Composable
fun PeriodChart(points: List<PeriodPoint>, period: Period, unit: String?, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        Text(
            "The chart appears once there are two ${period.noun()}s of history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val line = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val target = points.firstNotNullOfOrNull { it.target }
    val top = maxOf(points.maxOf { it.total }, target ?: 0.0, 1.0)
    val summary = "Line chart of ${period.noun()}ly totals. " + points.joinToString("; ") {
        "${formatDate(it.start)}: ${withUnit(it.total, unit)}"
    } + (target?.let { ". Target ${withUnit(it, unit)} per ${period.noun()}" } ?: "")

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Peak ${withUnit(top, unit)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = summary }) {
            val stepX = size.width / (points.size - 1)
            fun y(value: Double) = size.height - (value / top).toFloat() * size.height
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            target?.let {
                drawLine(
                    guide, Offset(0f, y(it)), Offset(size.width, y(it)), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                )
            }
            points.zipWithNext().forEachIndexed { index, (a, b) ->
                drawLine(
                    line, Offset(index * stepX, y(a.total)), Offset((index + 1) * stepX, y(b.total)),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }
            points.forEachIndexed { index, p ->
                // Hollow dots mark periods that were excused, filled dots everything else.
                drawCircle(
                    line, 4.dp.toPx(), Offset(index * stepX, y(p.total)),
                    style = if (p.excused) Stroke(2.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val fmt = DateTimeFormatter.ofPattern(if (period == Period.MONTH) "MMM yyyy" else "MMM d")
            Text(LocalDate.parse(points.first().start).format(fmt), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(LocalDate.parse(points.last().start).format(fmt), style = MaterialTheme.typography.labelSmall)
        }
        if (target != null) {
            Text(
                "Dashed line: target of ${withUnit(target, unit)} per ${period.noun()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
