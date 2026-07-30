package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeeklyReportRangeTest {
    @Test
    fun `returns every Sunday period between earliest and latest evidence`() {
        val starts = WeeklyReportRange.periodStarts(
            listOf(
                LocalDate.of(2026, 7, 29),
                LocalDate.of(2026, 7, 5),
            ),
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 5),
                LocalDate.of(2026, 7, 12),
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 26),
            ),
            starts,
        )
    }

    @Test
    fun `returns no periods without evidence and bounds corrupt ranges`() {
        assertEquals(emptyList<LocalDate>(), WeeklyReportRange.periodStarts(emptyList()))

        assertThrows(IllegalArgumentException::class.java) {
            WeeklyReportRange.periodStarts(
                listOf(LocalDate.of(1900, 1, 1), LocalDate.of(2101, 1, 1)),
            )
        }
    }
}
