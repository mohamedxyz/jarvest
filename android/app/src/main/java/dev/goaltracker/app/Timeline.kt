package dev.goaltracker.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

/** Where a date sits on the timeline, in the order the groups are shown. */
enum class Timeline(val label: String) {
    OVERDUE("Overdue"),
    TODAY("Today"),
    TOMORROW("Tomorrow"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LATER("Later"),
    NO_DATE("No date"),
    DONE("Done"),
}

/** Puts a due date or deadline (`YYYY-MM-DD`, or null) into a group. Finished items go last. */
fun timelineOf(date: String?, done: Boolean, today: String): Timeline {
    if (done) return Timeline.DONE
    if (date == null) return Timeline.NO_DATE
    return try {
        val day = LocalDate.parse(date)
        val now = LocalDate.parse(today)
        when {
            day < now -> Timeline.OVERDUE
            day == now -> Timeline.TODAY
            day == now.plusDays(1) -> Timeline.TOMORROW
            day <= now.plusDays(6) -> Timeline.THIS_WEEK
            YearMonth.from(day) == YearMonth.from(now) -> Timeline.THIS_MONTH
            else -> Timeline.LATER
        }
    } catch (_: Exception) {
        Timeline.NO_DATE
    }
}

/** A separator line with the group name and how many items it holds. */
@Composable
fun TimelineHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp)) {
        Text(
            "$title · $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider()
}
