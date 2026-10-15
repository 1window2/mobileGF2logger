package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyNotePolicyTest {
    @Test
    fun acceptsOnlyCountsBelowTheHistoryBound() {
        assertTrue(WeeklyNotePolicy.canAdd(0))
        assertTrue(WeeklyNotePolicy.canAdd(127))
        assertFalse(WeeklyNotePolicy.canAdd(128))
        assertFalse(WeeklyNotePolicy.canAdd(-1))
    }

    @Test
    fun acceptsOnlyDaysInsideTheirSundayBasedWeek() {
        val sunday = LocalDate.of(2026, 8, 16)

        assertTrue(WeeklyNotePolicy.isWithinWeek(sunday, sunday))
        assertTrue(WeeklyNotePolicy.isWithinWeek(sunday, sunday.plusDays(6)))
        assertFalse(WeeklyNotePolicy.isWithinWeek(sunday, sunday.minusDays(1)))
        assertFalse(WeeklyNotePolicy.isWithinWeek(sunday, sunday.plusDays(7)))
        assertFalse(WeeklyNotePolicy.isWithinWeek(sunday.plusDays(1), sunday.plusDays(1)))
    }
}
