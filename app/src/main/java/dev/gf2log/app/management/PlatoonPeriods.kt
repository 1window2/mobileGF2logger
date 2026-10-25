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

    /**
     * The 05:00-based game day containing [instant]. The reset is a wall-clock time, so the
     * shift is applied to the local date-time; shifting the instant would move the boundary by
     * an hour on daylight-saving transition days and disagree with [periodStartInstant].
     */
    fun gameDay(instant: Instant, zoneId: ZoneId): LocalDate =
        instant.atZone(zoneId).toLocalDateTime().minusHours(RESET_HOUR.toLong()).toLocalDate()

    /**
     * The game day whose counters a capture at [instant] records. A capture exactly at a 05:00
     * reset is the closing capture of the previous game day, since nothing of the new day can
     * have been earned yet; every other capture records a prefix of its own [gameDay].
     */
    fun checkpointDay(instant: Instant, zoneId: ZoneId): LocalDate {
        val day = gameDay(instant, zoneId)
        return if (instant == periodStartInstant(day, zoneId)) day.minusDays(1) else day
    }

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
        // The Sunday 05:00 reset capture still closes the Saturday game day; see checkpointDay.
        return !capturedAt.isBefore(scoreClose) &&
            !capturedAt.isAfter(periodStartInstant(gameDay.plusDays(1), zoneId))
    }

    private const val GUNSMOKE_CYCLE_WEEKS = 3L
}
