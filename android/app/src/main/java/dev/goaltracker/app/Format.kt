package dev.goaltracker.app

import dev.goaltracker.core.Goal
import dev.goaltracker.core.GoalKind
import dev.goaltracker.core.Period
import dev.goaltracker.core.TodayItem
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun formatAmount(value: Double): String =
    BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

fun withUnit(value: Double, unit: String?): String =
    if (unit.isNullOrBlank()) formatAmount(value) else "${formatAmount(value)} $unit"

fun parseAmount(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

fun Period.noun(): String = when (this) {
    Period.DAY -> "day"
    Period.WEEK -> "week"
    Period.MONTH -> "month"
}

fun Period.current(): String = when (this) {
    Period.DAY -> "today"
    Period.WEEK -> "this week"
    Period.MONTH -> "this month"
}

fun formatDate(date: String): String =
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

fun countOf(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

private fun daysUntil(due: String, today: String): Int =
    java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(today), LocalDate.parse(due)).toInt()

/**
 * "Today", "Tomorrow", "3 days left" or "2 days late" for a task's due date. A date more than
 * a week away is shown as a short date instead, because "412 days left" is hard to read.
 */
fun dueLabel(due: String, today: String): String {
    val days = daysUntil(due, today)
    return when {
        days == 0 -> "Today"
        days == 1 -> "Tomorrow"
        days in 2..7 -> "$days days left"
        days > 7 -> LocalDate.parse(due).format(DateTimeFormatter.ofPattern("MMM d"))
        days == -1 -> "1 day late"
        else -> "${-days} days late"
    }
}

/** How urgent a due date is, so the label can be colored. */
enum class DueTone { LATE, SOON, CALM }

/** Late is red, due within three days is amber, anything further away is calm. */
fun dueTone(due: String, today: String): DueTone {
    val days = daysUntil(due, today)
    return when {
        days < 0 -> DueTone.LATE
        days <= 3 -> DueTone.SOON
        else -> DueTone.CALM
    }
}

/** A habit is something you repeat; a goal is something you work toward. The app words them apart. */
fun Goal.noun(): String = if (kind == GoalKind.HABIT) "habit" else "goal"

fun TodayItem.noun(): String = if (kind == GoalKind.HABIT) "habit" else "goal"

fun GoalKind.noun(): String = if (this == GoalKind.HABIT) "habit" else "goal"
