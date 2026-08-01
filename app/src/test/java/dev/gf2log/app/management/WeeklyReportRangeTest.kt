package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyReportRangeTest {
    @Test
    fun `returns only distinct evidence backed Sunday periods`() {
        val starts = WeeklyReportRange.periodStarts(
            listOf(
                LocalDate.of(2026, 7, 29),
                LocalDate.of(2026, 7, 5),
                LocalDate.of(2026, 7, 31),
            ),
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 5),
                LocalDate.of(2026, 7, 26),
            ),
            starts,
        )
    }

    @Test
    fun `returns no periods without evidence`() {
        assertEquals(emptyList<LocalDate>(), WeeklyReportRange.periodStarts(emptyList()))
    }
}
