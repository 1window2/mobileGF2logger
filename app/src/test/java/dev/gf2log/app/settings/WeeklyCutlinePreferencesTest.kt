package dev.gf2log.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyCutlinePreferencesTest {
    @Test
    fun `a failed write keeps the previous cutlines for later reads`() {
        val prefs = FakeSharedPreferences()
        val cutlines = ScopedWeeklyCutlines(prefs, "profile")
        val previous = WeeklyCutlines(dailyMerit = 90, weeklyLoginDays = 5)
        assertTrue(cutlines.write(previous))
        prefs.failingCommits = 1

        assertFalse(cutlines.write(WeeklyCutlines(dailyMerit = 150, dailyGunsmokeAttempts = 3)))

        assertEquals(previous, cutlines.read())
    }

    @Test
    fun `a failed clear keeps the cutlines`() {
        val prefs = FakeSharedPreferences()
        val cutlines = ScopedWeeklyCutlines(prefs, "profile")
        val previous = WeeklyCutlines(weeklyMerit = 600, weeklyPatrolDays = 7)
        cutlines.write(previous)
        prefs.failingCommits = 1

        assertFalse(cutlines.write(WeeklyCutlines()))

        assertEquals(previous, cutlines.read())
    }

    @Test
    fun `a successful write replaces every field`() {
        val prefs = FakeSharedPreferences()
        val cutlines = ScopedWeeklyCutlines(prefs, "profile")
        cutlines.write(WeeklyCutlines(dailyMerit = 90))

        assertTrue(cutlines.write(WeeklyCutlines(weeklyGunsmokeAttempts = 21)))

        assertEquals(WeeklyCutlines(weeklyGunsmokeAttempts = 21), cutlines.read())
        assertEquals(2, prefs.commits)
    }
}
