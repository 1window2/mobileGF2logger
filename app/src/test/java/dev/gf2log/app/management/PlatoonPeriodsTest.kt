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
    fun gameDayFollowsTheLocalFiveAmResetAcrossDaylightSavingTransitions() {
        val newYork = ZoneId.of("America/New_York")
        // 2026-03-08 springs forward at 02:00 and 2026-11-01 falls back at 02:00.
        listOf(
            LocalDate.of(2026, 3, 7),
            LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 3, 9),
            LocalDate.of(2026, 10, 31),
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 2),
        ).forEach { day ->
            val reset = PlatoonPeriods.periodStartInstant(day, newYork)
            assertEquals(day, PlatoonPeriods.gameDay(reset, newYork))
            assertEquals(day.minusDays(1), PlatoonPeriods.gameDay(reset.minusMillis(1), newYork))
        }
        assertEquals(
            LocalDate.of(2026, 3, 8),
            PlatoonPeriods.gameDay(Instant.parse("2026-03-08T09:30:00Z"), newYork),
        )
        assertEquals(
            LocalDate.of(2026, 10, 31),
            PlatoonPeriods.gameDay(Instant.parse("2026-11-01T09:30:00Z"), newYork),
        )
    }

    @Test
    fun everyWeeklyTableRunsFromSundayThroughSaturday() {
        val gunsmokeSunday = LocalDate.of(2026, 7, 19)
        val standardSunday = LocalDate.of(2026, 7, 26)

        assertEquals(gunsmokeSunday, PlatoonPeriods.weekStart(gunsmokeSunday))
        assertEquals(gunsmokeSunday, PlatoonPeriods.weekStart(LocalDate.of(2026, 7, 25)))
        assertEquals(standardSunday, PlatoonPeriods.weekStart(standardSunday))
        assertEquals(standardSunday, PlatoonPeriods.weekStart(LocalDate.of(2026, 7, 27)))
        assertEquals(standardSunday, PlatoonPeriods.weekStart(LocalDate.of(2026, 8, 1)))
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

    @Test
    fun finalGunsmokeScoreClosesAtTwoAmWithoutChangingTheFiveAmGameDay() {
        val saturday = LocalDate.of(2026, 7, 25)

        assertFalse(
            PlatoonPeriods.isFinalGunsmokeScoreCapture(
                saturday,
                Instant.parse("2026-07-25T16:59:59Z"),
                seoul,
            ),
        )
        assertTrue(
            PlatoonPeriods.isFinalGunsmokeScoreCapture(
                saturday,
                Instant.parse("2026-07-25T17:00:00Z"),
                seoul,
            ),
        )
        // The Sunday 05:00 reset capture is the closing Saturday capture of the event week.
        assertTrue(
            PlatoonPeriods.isFinalGunsmokeScoreCapture(
                saturday,
                Instant.parse("2026-07-25T20:00:00Z"),
                seoul,
            ),
        )
        assertFalse(
            PlatoonPeriods.isFinalGunsmokeScoreCapture(
                saturday,
                Instant.parse("2026-07-25T20:00:01Z"),
                seoul,
            ),
        )
        assertEquals(
            saturday,
            PlatoonPeriods.gameDay(Instant.parse("2026-07-25T17:00:00Z"), seoul),
        )
    }

    @Test
    fun aCaptureExactlyAtTheResetRecordsThePreviousGameDay() {
        val saturday = LocalDate.of(2026, 7, 25)
        val reset = PlatoonPeriods.periodStartInstant(saturday.plusDays(1), seoul)

        assertEquals(saturday, PlatoonPeriods.checkpointDay(reset, seoul))
        assertEquals(saturday, PlatoonPeriods.checkpointDay(reset.minusMillis(1), seoul))
        assertEquals(saturday.plusDays(1), PlatoonPeriods.checkpointDay(reset.plusMillis(1), seoul))
        assertEquals(saturday.plusDays(1), PlatoonPeriods.gameDay(reset, seoul))
    }
}
