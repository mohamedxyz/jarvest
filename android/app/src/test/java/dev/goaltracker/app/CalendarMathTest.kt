package dev.goaltracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarMathTest {
    @Test
    fun snapsToTheNearestStep() {
        assertEquals(540, snapTo(547f, 15))
        assertEquals(555, snapTo(548f, 15))
        assertEquals(0, snapTo(6f, 15))
    }

    @Test
    fun floorsToTheStepBelow() {
        assertEquals(540, floorTo(569, 30))
        assertEquals(570, floorTo(570, 30))
    }

    @Test
    fun aTapMapsToATimeEvenWhenZoomed() {
        // 56 px per hour and an 8 px gap on top: 8 + 56 * 9.5 = 540 is half past nine.
        assertEquals(570f, minuteAt(8f + 56f * 9.5f, 56f, 8f), 0.01f)
        // Twice as tall hours: the same minute sits twice as far down.
        assertEquals(570f, minuteAt(8f + 112f * 9.5f, 112f, 8f), 0.01f)
    }

    @Test
    fun aTapAboveOrBelowTheGridStaysInsideTheDay() {
        assertEquals(0f, minuteAt(-50f, 56f, 8f), 0.01f)
        assertEquals(1439f, minuteAt(100000f, 56f, 8f), 0.01f)
    }

    @Test
    fun aMovedBlockStaysInsideTheDay() {
        assertEquals(0, clampStart(-30, 60))
        assertEquals(23 * 60, clampStart(23 * 60 + 30, 60))
        assertEquals(600, clampStart(600, 60))
    }

    @Test
    fun resizingSnapsAndRespectsTheLimits() {
        assertEquals(75, resizedMinutes(540, 60, 14f))
        assertEquals(15, resizedMinutes(540, 60, -200f))
        // From 23:00 the block can grow to midnight and no further.
        assertEquals(60, resizedMinutes(23 * 60, 30, 500f))
    }

    @Test
    fun zoomStaysWithinLimits() {
        assertEquals(160f, zoomedHourDp(150f, 2f), 0.001f)
        assertEquals(40f, zoomedHourDp(50f, 0.2f), 0.001f)
        assertEquals(84f, zoomedHourDp(56f, 1.5f), 0.001f)
    }

    @Test
    fun aDraggedRangeWorksInBothDirections() {
        assertEquals(540 to 60, rangeBetween(540f, 600f))
        assertEquals(540 to 60, rangeBetween(600f, 540f))
        // A tiny drag still gives the smallest block.
        assertEquals(540 to 15, rangeBetween(540f, 541f))
    }
}
