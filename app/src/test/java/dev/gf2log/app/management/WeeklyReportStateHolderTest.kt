package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyReportStateHolderTest {
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
