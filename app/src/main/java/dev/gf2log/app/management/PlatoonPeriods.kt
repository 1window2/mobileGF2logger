package dev.gf2log.app.management

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object PlatoonPeriods {
    const val RESET_HOUR = 5
    const val GUNSMOKE_FINAL_SCORE_HOUR = 2
    val GUNSMOKE_ANCHOR: LocalDate = LocalDate.of(2026, 7, 19)

    fun gameDay(instant: Instant, zoneId: ZoneId): LocalDate =
        instant.atZone(zoneId).minusHours(RESET_HOUR.toLong()).toLocalDate()

    fun weekStart(gameDay: LocalDate): LocalDate =
        gameDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    fun isGunsmokeWeek(gameDay: LocalDate): Boolean {
        val sunday = weekStart(gameDay)
        val weeks = ChronoUnit.WEEKS.between(GUNSMOKE_ANCHOR, sunday)
        return Math.floorMod(weeks, GUNSMOKE_CYCLE_WEEKS) == 0L
    }

    fun periodStartInstant(date: LocalDate, zoneId: ZoneId): Instant =
        ZonedDateTime.of(date, LocalTime.of(RESET_HOUR, 0), zoneId).toInstant()

    fun isFinalGunsmokeScoreCapture(
        gameDay: LocalDate,
        capturedAt: Instant,
        zoneId: ZoneId,
    ): Boolean {
        if (!isGunsmokeWeek(gameDay) || gameDay.dayOfWeek != DayOfWeek.SATURDAY) return false
        val scoreClose = ZonedDateTime.of(
            gameDay.plusDays(1),
            LocalTime.of(GUNSMOKE_FINAL_SCORE_HOUR, 0),
            zoneId,
        ).toInstant()
        return !capturedAt.isBefore(scoreClose) &&
            capturedAt.isBefore(periodStartInstant(gameDay.plusDays(1), zoneId))
    }

    private const val GUNSMOKE_CYCLE_WEEKS = 3L
}
