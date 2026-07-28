package dev.gf2log.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyCutlinesTest {
    @Test
    fun dailyAndWeeklyCutlinesCanBothBeEnabled() {
        val cutlines = WeeklyCutlines(
            dailyMerit = 90,
            dailyGunsmokeScore = 10_000,
            dailyGunsmokeAttempts = 3,
            weeklyMerit = 600,
        )

        assertTrue(cutlines.belowDailyMerit(50))
        assertTrue(cutlines.belowDailyScore(9_999))
        assertTrue(cutlines.belowDailyAttempts(2))
        assertTrue(cutlines.belowWeeklyMerit(599))
        assertFalse(cutlines.belowWeeklyMerit(600))
    }

    @Test
    fun weeklyCountsUseIndependentCutlines() {
        val cutlines = WeeklyCutlines(
            weeklyMerit = 600,
            weeklyLoginDays = 7,
            weeklyPatrolDays = 5,
        )

        assertTrue(cutlines.belowWeeklyMerit(599))
        assertTrue(cutlines.belowWeeklyLoginDays(6))
        assertFalse(cutlines.belowWeeklyPatrolDays(5))
    }
}
