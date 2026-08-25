package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipConsistencyPolicyTest {
    @Test
    fun adjacentPeriodsAreValidAndTheOpenTailDefinesCurrentActivity() {
        val periods = listOf(
            interval(1, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
            interval(2, "2026-02-01T00:00:00Z", null),
        )

        assertNull(MembershipConsistencyPolicy.violation(periods))
        assertTrue(MembershipConsistencyPolicy.isActive(periods))
    }

    @Test
    fun overlappingAndMultipleOpenPeriodsAreRejected() {
        assertNotNull(
            MembershipConsistencyPolicy.violation(
                listOf(
                    interval(1, "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z"),
                    interval(2, "2026-02-01T00:00:00Z", "2026-04-01T00:00:00Z"),
                ),
            ),
        )
        assertNotNull(
            MembershipConsistencyPolicy.violation(
                listOf(
                    interval(1, "2026-01-01T00:00:00Z", null),
                    interval(2, "2026-02-01T00:00:00Z", null),
                ),
            ),
        )
    }

    @Test
    fun closedTimelineIsInactive() {
        val periods = listOf(interval(1, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"))

        assertNull(MembershipConsistencyPolicy.violation(periods))
        assertFalse(MembershipConsistencyPolicy.isActive(periods))
    }

    @Test
    fun unknownSnapshotStartDoesNotInventAnOverlap() {
        val periods = listOf(
            interval(1, null, "2026-01-01T00:00:00Z"),
            interval(2, "2026-02-01T00:00:00Z", null),
        )

        assertNull(MembershipConsistencyPolicy.violation(periods))
        assertTrue(MembershipConsistencyPolicy.isActive(periods))
    }

    @Test
    fun dateOnlyBoundariesUseCalendarChronologyInsteadOfEntryTime() {
        val enteredAt = Instant.parse("2026-07-31T00:00:00Z")
        val period = MembershipInterval(
            id = 1,
            joinedAt = enteredAt,
            leftAt = enteredAt,
            joinedDate = LocalDate.of(2026, 5, 4),
            leftDate = LocalDate.of(2026, 5, 6),
            joinedTimeKnown = false,
            leftTimeKnown = false,
        )

        assertNull(MembershipConsistencyPolicy.violation(listOf(period)))
    }

    @Test
    fun uncertainSameDayBoundaryDoesNotInventAnOverlap() {
        val first = MembershipInterval(
            id = 1,
            joinedAt = Instant.parse("2026-05-01T00:00:00Z"),
            leftAt = Instant.parse("2026-07-31T00:00:00Z"),
            joinedDate = LocalDate.of(2026, 5, 1),
            leftDate = LocalDate.of(2026, 5, 4),
            leftTimeKnown = false,
        )
        val second = MembershipInterval(
            id = 2,
            joinedAt = Instant.parse("2026-07-31T00:00:00Z"),
            leftAt = null,
            joinedDate = LocalDate.of(2026, 5, 4),
            joinedTimeKnown = false,
        )

        assertNull(MembershipConsistencyPolicy.violation(listOf(first, second)))
    }

    @Test
    fun sameDayKnownStartsAreOrderedByInstantInsteadOfInsertionId() {
        val laterInsertedFirst = MembershipInterval(
            id = 1,
            joinedAt = Instant.parse("2026-05-04T18:00:00Z"),
            leftAt = Instant.parse("2026-05-04T20:00:00Z"),
            joinedDate = LocalDate.of(2026, 5, 4),
            leftDate = LocalDate.of(2026, 5, 4),
        )
        val earlierInsertedSecond = MembershipInterval(
            id = 2,
            joinedAt = Instant.parse("2026-05-04T08:00:00Z"),
            leftAt = Instant.parse("2026-05-04T10:00:00Z"),
            joinedDate = LocalDate.of(2026, 5, 4),
            leftDate = LocalDate.of(2026, 5, 4),
        )

        assertNull(
            MembershipConsistencyPolicy.violation(
                listOf(laterInsertedFirst, earlierInsertedSecond),
            ),
        )
    }

    @Test
    fun mixedSameDayStartPrecisionUsesATransitiveOrder() {
        val knownLate = MembershipInterval(
            id = 1,
            joinedAt = Instant.parse("2026-05-04T18:00:00Z"),
            leftAt = Instant.parse("2026-05-04T20:00:00Z"),
            joinedDate = LocalDate.of(2026, 5, 4),
            leftDate = LocalDate.of(2026, 5, 4),
        )
        val dateOnly = MembershipInterval(
            id = 2,
            joinedAt = Instant.parse("2026-07-31T00:00:00Z"),
            leftAt = Instant.parse("2026-08-01T00:00:00Z"),
            joinedDate = LocalDate.of(2026, 5, 4),
            leftDate = LocalDate.of(2026, 5, 5),
            joinedTimeKnown = false,
            leftTimeKnown = false,
        )
        val knownEarly = MembershipInterval(
            id = 3,
            joinedAt = Instant.parse("2026-05-04T08:00:00Z"),
            leftAt = Instant.parse("2026-05-04T10:00:00Z"),
            joinedDate = LocalDate.of(2026, 5, 4),
            leftDate = LocalDate.of(2026, 5, 4),
        )

        assertNull(MembershipConsistencyPolicy.violation(listOf(knownLate, dateOnly, knownEarly)))
    }

    private fun interval(id: Long, joined: String?, left: String?) = MembershipInterval(
        id = id,
        joinedAt = joined?.let(Instant::parse),
        leftAt = left?.let(Instant::parse),
    )
}
