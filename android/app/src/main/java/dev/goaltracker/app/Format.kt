package dev.goaltracker.app

import dev.goaltracker.core.Period
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
