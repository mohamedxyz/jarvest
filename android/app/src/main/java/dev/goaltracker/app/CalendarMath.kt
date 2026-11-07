package dev.goaltracker.app

import kotlin.math.roundToInt

/**
 * Plain arithmetic for the calendar grid, kept apart from the screen so it can be tested without a
 * phone. Times are minutes after midnight. Positions are pixels inside the scrolling grid, where
 * zero is the top of the first hour label's row.
 */
internal const val DAY_MINUTES = 24 * 60
internal const val MIN_BLOCK_MINUTES = 15
internal const val MIN_HOUR_DP = 40f
internal const val MAX_HOUR_DP = 160f

/** The nearest multiple of [step]. */
internal fun snapTo(minutes: Float, step: Int): Int = (minutes / step).roundToInt() * step

/** The multiple of [step] at or below [minutes]. */
internal fun floorTo(minutes: Int, step: Int): Int = minutes / step * step

/** The time at a vertical position in the grid. [topPadPx] is the gap above the first hour line. */
internal fun minuteAt(yPx: Float, hourPx: Float, topPadPx: Float): Float =
    ((yPx - topPadPx) / hourPx * 60f).coerceIn(0f, (DAY_MINUTES - 1).toFloat())

/** Keeps a block of [minutes] inside the day when it starts at [start]. */
internal fun clampStart(start: Int, minutes: Int): Int = start.coerceIn(0, maxOf(0, DAY_MINUTES - minutes))

/** The new length after dragging the bottom edge by [deltaMinutes]: in steps of 15, at least 15, never past midnight. */
internal fun resizedMinutes(start: Int, minutes: Int, deltaMinutes: Float): Int {
    val wanted = snapTo(minutes + deltaMinutes, MIN_BLOCK_MINUTES)
    return wanted.coerceIn(MIN_BLOCK_MINUTES, maxOf(MIN_BLOCK_MINUTES, DAY_MINUTES - start))
}

/** The hour height after a pinch: the old height times the zoom, kept within sensible limits. */
internal fun zoomedHourDp(current: Float, zoom: Float): Float = (current * zoom).coerceIn(MIN_HOUR_DP, MAX_HOUR_DP)

/**
 * Where a ghost range ends up after pressing at [anchor] and dragging to [now] (both in minutes):
 * the start is the earlier of the two snapped to 15, and the length is at least 15.
 */
internal fun rangeBetween(anchor: Float, now: Float): Pair<Int, Int> {
    val a = snapTo(minOf(anchor, now), MIN_BLOCK_MINUTES).coerceAtMost(DAY_MINUTES - MIN_BLOCK_MINUTES)
    val b = snapTo(maxOf(anchor, now), MIN_BLOCK_MINUTES)
    return a to (b - a).coerceIn(MIN_BLOCK_MINUTES, DAY_MINUTES - a)
}
