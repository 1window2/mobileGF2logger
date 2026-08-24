package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate

/** One persisted membership interval used by the deterministic consistency audit. */
internal data class MembershipInterval(
    val id: Long,
    val joinedAt: Instant?,
    val leftAt: Instant?,
    val joinedDate: LocalDate? = null,
    val leftDate: LocalDate? = null,
    val joinedTimeKnown: Boolean = true,
    val leftTimeKnown: Boolean = true,
)

/** Enforces chronology rules shared by manual edits and post-mutation verification. */
internal object MembershipConsistencyPolicy {
    fun violation(periods: List<MembershipInterval>): String? {
        periods.forEach { period ->
            if (!hasValidOwnChronology(period)) {
                return "Membership period ${period.id} does not end after it starts"
            }
        }
        if (periods.count { it.leftAt == null } > 1) {
            return "A member cannot have more than one open membership period"
        }
        // An absent start is deliberately unknown, not negative infinity. Reject only
        // overlaps proven by two known starts; uncertain legacy/snapshot boundaries stay editable.
        val ordered = periods.filter { it.joinedAt != null }.sortedWith(
            Comparator(::compareStarts),
        )
        ordered.zipWithNext().forEach { (earlier, later) ->
            if (periodsProvablyOverlap(earlier, later)) {
                return "Membership periods ${earlier.id} and ${later.id} overlap"
            }
        }
        return null
    }

    fun isActive(periods: List<MembershipInterval>): Boolean = periods.any { it.leftAt == null }

    private fun hasValidOwnChronology(period: MembershipInterval): Boolean {
        val joinedAt = period.joinedAt ?: return true
        val leftAt = period.leftAt ?: return true
        val joinedDate = period.joinedDate
        val leftDate = period.leftDate
        if (joinedDate != null && leftDate != null) {
            val dateOrder = leftDate.compareTo(joinedDate)
            if (dateOrder != 0) return dateOrder > 0
            if (!period.joinedTimeKnown || !period.leftTimeKnown) return true
        }
        return !leftAt.isBefore(joinedAt)
    }

    private fun compareStarts(first: MembershipInterval, second: MembershipInterval): Int {
        val dateOrder = if (first.joinedDate != null && second.joinedDate != null) {
            first.joinedDate.compareTo(second.joinedDate)
        } else {
            requireNotNull(first.joinedAt).compareTo(requireNotNull(second.joinedAt))
        }
        return if (dateOrder != 0) dateOrder else first.id.compareTo(second.id)
    }

    private fun periodsProvablyOverlap(
        earlier: MembershipInterval,
        later: MembershipInterval,
    ): Boolean {
        val earlierEnd = earlier.leftAt ?: return true
        val laterStart = requireNotNull(later.joinedAt)
        val earlierEndDate = earlier.leftDate
        val laterStartDate = later.joinedDate
        if (earlierEndDate != null && laterStartDate != null) {
            val dateOrder = earlierEndDate.compareTo(laterStartDate)
            if (dateOrder != 0) return dateOrder > 0
            if (!earlier.leftTimeKnown || !later.joinedTimeKnown) return false
        }
        return earlierEnd.isAfter(laterStart)
    }
}
