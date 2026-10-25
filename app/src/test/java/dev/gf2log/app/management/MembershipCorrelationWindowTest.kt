package dev.gf2log.app.management

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipCorrelationWindowTest {
    private val previousRoster = Instant.parse("2026-08-01T00:00:00Z")
    private val revealingRoster = Instant.parse("2026-08-01T06:00:00Z")

    @Test
    fun `an activity between the two rosters can date the boundary`() {
        assertTrue(admits(previousRoster.plusSeconds(1)))
        assertTrue(admits(revealingRoster))
    }

    @Test
    fun `an activity after the revealing roster cannot become the boundary`() {
        // The roster at revealingRoster already showed the change, so a later fact is no proof.
        assertFalse(admits(revealingRoster.plusMillis(1)))
        assertFalse(admits(revealingRoster.plusSeconds(60L * 60L)))
    }

    @Test
    fun `an activity at or before the previous roster cannot become the boundary`() {
        assertFalse(admits(previousRoster))
        assertFalse(admits(previousRoster.minusSeconds(60L)))
    }

    @Test
    fun `a sparse roster gap still bounds the lead to the correlation window`() {
        val distantPrevious = revealingRoster.minusSeconds(3L * 24L * 60L * 60L)
        val leadLimit = revealingRoster.minusMillis(MembershipCorrelationWindow.MAX_LEAD_MILLIS)

        assertTrue(MembershipCorrelationWindow.admits(leadLimit, revealingRoster, distantPrevious))
        assertFalse(
            MembershipCorrelationWindow.admits(
                leadLimit.minusMillis(1),
                revealingRoster,
                distantPrevious,
            ),
        )
    }

    @Test
    fun `a boundary without a previous roster stays inferred`() {
        assertFalse(MembershipCorrelationWindow.admits(revealingRoster, revealingRoster, null))
    }

    private fun admits(occurredAt: Instant) =
        MembershipCorrelationWindow.admits(occurredAt, revealingRoster, previousRoster)
}
