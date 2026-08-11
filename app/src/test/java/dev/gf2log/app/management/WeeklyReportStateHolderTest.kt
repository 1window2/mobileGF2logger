package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportStateHolderTest {
    @Test
    fun onlyLatestResumedRequestCanApply() {
        val state = WeeklyReportStateHolder(LocalDate.of(2026, 8, 10))

        state.onResume()
        val first = state.newRenderRequest()
        val second = state.newRenderRequest()

        assertFalse(state.canApply(first.generation))
        assertTrue(state.canApply(second.generation))

        state.onPause()
        assertFalse(state.canApply(second.generation))
    }

    @Test
    fun navigationUsesAdjacentReportBoundaries() {
        val state = WeeklyReportStateHolder(LocalDate.of(2026, 8, 10))

        state.showPreviousWeek(LocalDate.of(2026, 8, 10))
        assertEquals(LocalDate.of(2026, 8, 9), state.referenceDay)

        state.showNextWeek(LocalDate.of(2026, 8, 16))
        assertEquals(LocalDate.of(2026, 8, 17), state.referenceDay)

        state.selectDate(LocalDate.of(2026, 7, 19))
        assertEquals(LocalDate.of(2026, 7, 19), state.referenceDay)
    }
}
