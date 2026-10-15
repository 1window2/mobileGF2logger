package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MembershipConsistencyPolicyTest {
    @Test
    fun adjacentPeriodsAreValid() {
        val periods = listOf(
            interval(1, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"),
            interval(2, "2026-02-01T00:00:00Z", null),
        )

        assertNull(violation(periods))
    }

    @Test
    fun overlappingAndMultipleOpenPeriodsAreRejected() {
        assertNotNull(
            violation(
                listOf(
                    interval(1, "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z"),
                    interval(2, "2026-02-01T00:00:00Z", "2026-04-01T00:00:00Z"),
                ),
            ),
        )
        assertNotNull(
            violation(
                listOf(
                    interval(1, "2026-01-01T00:00:00Z", null),
                    interval(2, "2026-02-01T00:00:00Z", null),
                ),
            ),
        )
    }

    @Test
    fun closedTimelineIsValid() {
        val periods = listOf(interval(1, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"))

        assertNull(violation(periods))
    }

    @Test
    fun unknownSnapshotStartDoesNotInventAnOverlap() {
        val periods = listOf(
            interval(1, null, "2026-01-01T00:00:00Z"),
            interval(2, "2026-02-01T00:00:00Z", null),
        )

        assertNull(violation(periods))
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

        assertNull(violation(listOf(period)))
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

        assertNull(violation(listOf(first, second)))
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
            violation(
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

        assertNull(violation(listOf(knownLate, dateOnly, knownEarly)))
    }

    private fun interval(id: Long, joined: String?, left: String?) = MembershipInterval(
        id = id,
        joinedAt = joined?.let(Instant::parse),
        leftAt = left?.let(Instant::parse),
    )

    @Test
    fun boundariesWithoutAStoredDateAreDatedInTheGameZone() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        // A manual stay from 06:00 to 07:00 on 10-16, then an exact open join at 07:30 that day.
        // In UTC the exact join falls on 10-15 and would sort, open, before the manual stay.
        val manual = MembershipInterval(
            id = 1,
            joinedAt = Instant.parse("2026-10-15T22:00:00Z"),
            leftAt = Instant.parse("2026-10-15T23:00:00Z"),
            joinedDate = LocalDate.of(2026, 10, 16),
            leftDate = LocalDate.of(2026, 10, 16),
        )
        val exact = MembershipInterval(id = 2, joinedAt = Instant.parse("2026-10-15T23:30:00Z"), leftAt = null)

        assertNull(MembershipConsistencyPolicy.violation(listOf(manual, exact), shanghai))
    }

    @Test
    fun aDateOnlyRejoinOnTheDayOfAnExactWithdrawalIsNotAProvenOverlap() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val withdrawn = MembershipInterval(
            id = 1,
            joinedAt = Instant.parse("2026-10-01T02:00:00Z"),
            leftAt = Instant.parse("2026-10-16T02:00:00Z"),
        )
        val rejoined = MembershipInterval(
            id = 2,
            joinedAt = Instant.parse("2026-10-15T16:00:00Z"),
            leftAt = null,
            joinedDate = LocalDate.of(2026, 10, 16),
            joinedTimeKnown = false,
        )

        assertNull(MembershipConsistencyPolicy.violation(listOf(withdrawn, rejoined), shanghai))
    }

    private fun violation(periods: List<MembershipInterval>) =
        MembershipConsistencyPolicy.violation(periods, ZoneOffset.UTC)
}
