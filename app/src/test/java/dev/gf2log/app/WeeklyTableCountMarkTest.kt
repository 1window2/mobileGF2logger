package dev.gf2log.app

import dev.gf2log.app.management.MetricCertainty
import dev.gf2log.app.settings.WeeklyCutlines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyTableCountMarkTest {
    private val cutlines = WeeklyCutlines(weeklyLoginDays = 5, weeklyPatrolDays = 4)

    @Test
    fun `a login count below its cutline carries a warning beyond color`() {
        val mark = WeeklyTable.countMark(3, MetricCertainty.EXACT, cutlines::belowWeeklyLoginDays)

        assertTrue(mark.warn)
        assertEquals("3", mark.symbol)
        assertEquals(R.color.cutline_mark, mark.color)
    }

    @Test
    fun `a confirmed minimum below the cutline warns too`() {
        assertTrue(WeeklyTable.countMark(2, MetricCertainty.LOWER_BOUND, cutlines::belowWeeklyPatrolDays).warn)
    }

    @Test
    fun `counts that meet the cutline or are unknown do not warn`() {
        assertFalse(WeeklyTable.countMark(5, MetricCertainty.EXACT, cutlines::belowWeeklyLoginDays).warn)
        assertFalse(WeeklyTable.countMark(1, MetricCertainty.UNKNOWN, cutlines::belowWeeklyLoginDays).warn)
        assertFalse(WeeklyTable.countMark(null, MetricCertainty.UNKNOWN, cutlines::belowWeeklyLoginDays).warn)
        assertEquals(
            R.color.text_secondary,
            WeeklyTable.countMark(6, MetricCertainty.EXACT, cutlines::belowWeeklyLoginDays).color,
        )
    }
}
