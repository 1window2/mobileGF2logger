package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
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

    private fun boundary(date: String, instant: String, timeKnown: Boolean) =
        MembershipBoundaryValue(
            date = LocalDate.parse(date),
            instant = Instant.parse(instant),
            timeKnown = timeKnown,
        )
}
