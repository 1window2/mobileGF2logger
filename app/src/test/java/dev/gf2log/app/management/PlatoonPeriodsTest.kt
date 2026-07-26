package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonPeriodsTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun gameDayChangesAtFiveAm() {
        assertEquals(
            LocalDate.of(2026, 7, 19),
            PlatoonPeriods.gameDay(Instant.parse("2026-07-19T19:59:59Z"), seoul),
        )
        assertEquals(
            LocalDate.of(2026, 7, 20),
            PlatoonPeriods.gameDay(Instant.parse("2026-07-19T20:00:00Z"), seoul),
        )
    }

    @Test
    fun meritWeekStartsOnMondayButGunsmokeStartsOnSunday() {
        val sunday = LocalDate.of(2026, 7, 19)
        assertEquals(LocalDate.of(2026, 7, 13), PlatoonPeriods.meritWeekStart(sunday))
        assertEquals(sunday, PlatoonPeriods.gunsmokeWeekStart(sunday))
        assertEquals(LocalDate.of(2026, 7, 20), PlatoonPeriods.meritWeekStart(sunday.plusDays(1)))
    }

    @Test
    fun gunsmokeRepeatsEveryThreeWeeks() {
        assertTrue(PlatoonPeriods.isGunsmokeWeek(LocalDate.of(2026, 2, 22)))
        assertTrue(PlatoonPeriods.isGunsmokeWeek(LocalDate.of(2026, 6, 28)))
        assertTrue(PlatoonPeriods.isGunsmokeWeek(LocalDate.of(2026, 7, 25)))
        assertFalse(PlatoonPeriods.isGunsmokeWeek(LocalDate.of(2026, 7, 26)))
        assertFalse(PlatoonPeriods.isGunsmokeWeek(LocalDate.of(2026, 8, 2)))
        assertTrue(PlatoonPeriods.isGunsmokeWeek(LocalDate.of(2026, 8, 9)))
    }
}

