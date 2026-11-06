package dev.goaltracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.goaltracker.core.DayLevel
import dev.goaltracker.core.DayRow
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private fun DayLevel.fill(): Color = when (this) {
    DayLevel.NONE -> Color(0xFF90A4AE)
    DayLevel.SPARK -> Color(0xFFF9A825)
    DayLevel.BLAZE -> Color(0xFFEF6C00)
    DayLevel.TITAN -> Color(0xFF2E7D32)
}

private fun DayLevel.word(): String = when (this) {
    DayLevel.NONE -> "No points"
    DayLevel.SPARK -> "Spark"
    DayLevel.BLAZE -> "Blaze"
    DayLevel.TITAN -> "Titan"
}

/**
 * History: a month where every day is colored by the level it ended with, a tap on a day for what
 * happened, and your records. Days from before this was recorded (version 0.8.0) stay empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(controller: AppController) {
    var monthStart by rememberSaveable { mutableStateOf(LocalDate.parse(controller.today).withDayOfMonth(1).toString()) }
    val first = LocalDate.parse(monthStart)
    val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val rows = remember(controller.version, monthStart) {
        controller.read(emptyList()) { dayRows(gridStart.toString(), 42u) }
    }.associateBy { it.date }
    val records = remember(controller.version, controller.today) { controller.read(null) { dayRecords(controller.today) } }
    var picked by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        records?.let { r ->
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your records", style = MaterialTheme.typography.titleMedium)
                    if (r.daysRecorded == 0u) {
                        Text("Nothing recorded yet. Finish something today and it starts here.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("🔥 Blaze streak: ${countOf(r.blazeStreak.toInt(), "day")} (best ${r.longestBlazeStreak})", style = MaterialTheme.typography.bodyMedium)
                        Text("⚔️ Titan days: ${r.titanDays}", style = MaterialTheme.typography.bodyMedium)
                        r.bestDayDate?.let { Text("⭐ Best day: ${r.bestDayPoints} points on ${formatDate(it)}", style = MaterialTheme.typography.bodyMedium) }
                        Text("${r.daysRecorded} days recorded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthStart = first.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
            Text(
                "${first.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${first.year}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { monthStart = first.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
        }
        Row(Modifier.fillMaxWidth()) {
            (1..7).forEach { n ->
                Text(
                    java.time.DayOfWeek.of(n).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        (0 until 6).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0 until 7).forEach { col ->
                    val day = gridStart.plusDays((week * 7 + col).toLong())
                    DayCell(day, first, rows[day.toString()], day.toString() == controller.today, Modifier.weight(1f)) { picked = day.toString() }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(DayLevel.SPARK, DayLevel.BLAZE, DayLevel.TITAN).forEach { level ->
                Box(Modifier.size(12.dp).clip(CircleShape).background(level.fill()))
                Text(level.word(), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            "Days before version 0.8.0 were not recorded, so they stay empty.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    picked?.let { date -> DaySheet(controller, date) { picked = null } }
}

@Composable
private fun DayCell(day: LocalDate, month: LocalDate, row: DayRow?, isToday: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val inMonth = day.month == month.month
    val color = row?.takeIf { it.level != DayLevel.NONE }?.level?.fill()
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            .background(color?.copy(alpha = if (inMonth) 0.85f else 0.35f) ?: MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .label("${formatDate(day.toString())}, ${row?.let { "${it.level.word()}, ${it.earned} of ${it.possible} points" } ?: "nothing recorded"}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${day.dayOfMonth}",
            style = if (isToday) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = when {
                color != null -> Color.White
                inMonth -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.outline
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySheet(controller: AppController, date: String, onDismiss: () -> Unit) {
    val detail = remember(date, controller.version) { controller.read(null) { dayDetail(date) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatDate(date), style = MaterialTheme.typography.titleLarge)
            val row = detail?.row
            if (row == null) {
                Text("Nothing was recorded for this day.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${row.level.word()} · ${row.earned} of ${row.possible} points", style = MaterialTheme.typography.titleMedium, color = row.level.fill())
            }
            detail?.let {
                if (it.goalsLogged.isNotEmpty()) {
                    Text("Goals and habits logged", style = MaterialTheme.typography.labelLarge)
                    it.goalsLogged.forEach { title -> Text("• $title", style = MaterialTheme.typography.bodyMedium) }
                }
                if (it.tasksDone.isNotEmpty()) {
                    Text("Tasks finished", style = MaterialTheme.typography.labelLarge)
                    it.tasksDone.forEach { title -> Text("• $title", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
