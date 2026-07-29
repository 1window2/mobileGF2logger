package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipBoundaryValueTest {
    @Test
    fun allowsSameDateWhenEitherManualTimeIsUnknown() {
        val joined = boundary("2026-07-29", "2026-07-29T12:00:00Z", timeKnown = true)
        val withdrew = boundary("2026-07-29", "2026-07-29T00:00:00Z", timeKnown = false)

        assertTrue(isValidMembershipRange(joined, withdrew))
    }

    @Test
    fun rejectsKnownWithdrawalTimeBeforeKnownJoinTime() {
        val joined = boundary("2026-07-29", "2026-07-29T12:00:00Z", timeKnown = true)
        val withdrew = boundary("2026-07-29", "2026-07-29T11:59:00Z", timeKnown = true)

        assertFalse(isValidMembershipRange(joined, withdrew))
    }

    @Test
    fun onlyExactGameUpdatesAreImmutableMembershipBoundaries() {
        assertTrue(EvidenceSource.GAME_UPDATES.isImmutableMembershipBoundary())
        assertFalse(EvidenceSource.MANUAL.isImmutableMembershipBoundary())
        assertFalse(EvidenceSource.SNAPSHOT.isImmutableMembershipBoundary())
    }

    @Test
    fun migrationCalendarDateUsesTheUpgradeTimezoneAndEpochMilliseconds() {
        val epochMillis = Instant.parse("2026-07-28T15:30:00Z").toEpochMilli()

        assertEquals(
            LocalDate.parse("2026-07-29"),
            migrationCalendarDate(epochMillis, ZoneId.of("Asia/Seoul")),
        )
        assertEquals(
            LocalDate.parse("2026-07-28"),
            migrationCalendarDate(epochMillis, ZoneId.of("America/Los_Angeles")),
        )
    }

    @Test
    fun historicalWithdrawalDoesNotReplaceALaterRosterConfirmedTenure() {
        assertTrue(
            MembershipChronology.withdrawalPredatesRosterPresence(
                withdrewAt = Instant.parse("2026-07-26T03:00:00Z"),
                latestRosterPresenceAt = Instant.parse("2026-07-28T03:00:00Z"),
            ),
        )
        assertFalse(
            MembershipChronology.withdrawalPredatesRosterPresence(
                withdrewAt = Instant.parse("2026-07-29T03:00:00Z"),
                latestRosterPresenceAt = Instant.parse("2026-07-28T03:00:00Z"),
            ),
        )
    }

    @Test
    fun laterOppositeBoundaryInvalidatesAnOlderRosterConfirmation() {
        val withdrawal = Instant.parse("2026-07-26T03:00:00Z")
        val roster = Instant.parse("2026-07-28T03:00:00Z")

        assertTrue(
            MembershipChronology.hasSupersedingOppositeBoundary(
                boundaryAt = withdrawal,
                observedAt = roster,
                oppositeBoundaryTimes = listOf(Instant.parse("2026-07-27T03:00:00Z")),
            ),
        )
        assertFalse(
            MembershipChronology.hasSupersedingOppositeBoundary(
                boundaryAt = withdrawal,
                observedAt = roster,
                oppositeBoundaryTimes = listOf(Instant.parse("2026-07-29T03:00:00Z")),
            ),
        )
    }

    private fun boundary(date: String, instant: String, timeKnown: Boolean) =
        MembershipBoundaryValue(
            date = LocalDate.parse(date),
            instant = Instant.parse(instant),
            timeKnown = timeKnown,
        )
}
