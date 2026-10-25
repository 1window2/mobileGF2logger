package dev.gf2log.app.management

import java.time.Instant

/**
 * Decides which activity fact may date an inferred roster join or withdrawal. The change
 * happened after the previous roster and no later than the roster that revealed it, so only an
 * activity in that interval can mark it; a later activity happened while a roster already
 * showed the result. The rulebook defines no 21935 action that marks membership, so the
 * interval is the only evidence; the lead bound keeps a sparse roster gap from adopting a
 * distant fact.
 */
internal object MembershipCorrelationWindow {
    const val MAX_LEAD_MILLIS = 12L * 60L * 60L * 1000L

    /**
     * Whether an activity at [occurredAt] can date a boundary that the roster at [inferredAt]
     * revealed. Without a [previousRosterAt] the change has no lower bound and stays inferred.
     */
    fun admits(occurredAt: Instant, inferredAt: Instant, previousRosterAt: Instant?): Boolean =
        previousRosterAt != null &&
            occurredAt.isAfter(previousRosterAt) &&
            !occurredAt.isAfter(inferredAt) &&
            !occurredAt.isBefore(inferredAt.minusMillis(MAX_LEAD_MILLIS))
}
