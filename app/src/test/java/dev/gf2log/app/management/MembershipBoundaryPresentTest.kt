package dev.gf2log.app.management

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipBoundaryPresentTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-11-06T03:00:00Z") // 12:00 on 2026-11-06 in Seoul

    @Test
    fun `today and the past are accepted`() {
        assertTrue(within(dateOnly(LocalDate.of(2026, 11, 6))))
        assertTrue(within(timed(now)))
        assertTrue(within(dateOnly(LocalDate.of(2026, 1, 1))))
    }

    @Test
    fun `a future withdrawal date is refused`() {
        assertFalse(within(dateOnly(LocalDate.of(2026, 11, 7))))
        assertFalse(within(dateOnly(LocalDate.of(2062, 10, 5))))
        assertFalse(within(timed(now.plus(Duration.ofDays(30)))))
    }

    @Test
    fun `a known time may run ahead only by the clock skew`() {
        assertTrue(within(timed(now.plus(SnapshotClockPolicy.MAX_CLOCK_SKEW))))
        assertFalse(within(timed(now.plus(SnapshotClockPolicy.MAX_CLOCK_SKEW).plusMillis(1))))
    }

    @Test
    fun `the latest date follows the game zone calendar`() {
        // 23:30 in Seoul: the skew already reaches the next calendar day there.
        val lateEvening = Instant.parse("2026-11-06T14:30:00Z")
        assertEquals(LocalDate.of(2026, 11, 7), latestMembershipBoundaryDate(zone, lateEvening))
        assertEquals(LocalDate.of(2026, 11, 6), latestMembershipBoundaryDate(zone, now))
    }

    private fun within(boundary: MembershipBoundaryValue) =
        isMembershipBoundaryWithinPresent(boundary, zone, now)

    private fun dateOnly(date: LocalDate) =
        MembershipBoundaryValue(date, date.atStartOfDay(zone).toInstant(), timeKnown = false)

    private fun timed(instant: Instant) =
        MembershipBoundaryValue(instant.atZone(zone).toLocalDate(), instant, timeKnown = true)
}
