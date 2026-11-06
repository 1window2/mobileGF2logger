package dev.gf2log.app.management

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotClockPolicyTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val skew = SnapshotClockPolicy.MAX_CLOCK_SKEW

    @Test
    fun `the reference is the phone clock unless a server time is later`() {
        assertEquals(now, SnapshotClockPolicy.reference(now, null))
        assertEquals(now, SnapshotClockPolicy.reference(now, now.minusSeconds(3_600)))
        val serverAhead = now.plusSeconds(30L * DAY)
        assertEquals(serverAhead, SnapshotClockPolicy.reference(now, serverAhead))
    }

    @Test
    fun `a roster beyond the reference by more than the skew is ahead`() {
        assertFalse(SnapshotClockPolicy.isAhead(now, now))
        assertFalse(SnapshotClockPolicy.isAhead(now.plus(skew), now))
        assertTrue(SnapshotClockPolicy.isAhead(now.plus(skew).plusMillis(1), now))
        // The usual cause: a capture with the phone clock a year ahead, revealed a day later.
        assertTrue(SnapshotClockPolicy.isAhead(Instant.parse("2027-11-06T12:00:00Z"), now))
    }

    @Test
    fun `server time keeps a real roster from looking ahead of a clock set behind`() {
        val realRoster = now
        val clockSetBack = now.minusSeconds(30L * DAY)
        val login = now.minusSeconds(600)

        assertTrue(SnapshotClockPolicy.isAhead(realRoster, SnapshotClockPolicy.reference(clockSetBack, null)))
        assertFalse(SnapshotClockPolicy.isAhead(realRoster, SnapshotClockPolicy.reference(clockSetBack, login)))
    }

    @Test
    fun `a roster showing a login after its own capture was dated behind`() {
        assertFalse(SnapshotClockPolicy.isBehind(now, null))
        assertFalse(SnapshotClockPolicy.isBehind(now, now.minusSeconds(60)))
        assertFalse(SnapshotClockPolicy.isBehind(now, now.plus(skew)))
        assertTrue(SnapshotClockPolicy.isBehind(now, now.plus(skew).plusSeconds(1)))
    }

    @Test
    fun `an earlier dated newer login proves a roster older than its date`() {
        assertTrue(SnapshotClockPolicy.contradicts(ownLogin = 1_000L, earlierDatedLogin = 1_001L))
        assertFalse(SnapshotClockPolicy.contradicts(ownLogin = 1_000L, earlierDatedLogin = 1_000L))
        assertFalse(SnapshotClockPolicy.contradicts(ownLogin = 1_000L, earlierDatedLogin = 999L))
        // Zero records no login, so it proves nothing.
        assertFalse(SnapshotClockPolicy.contradicts(ownLogin = 0L, earlierDatedLogin = 1_001L))
    }

    @Test
    fun `a set-aside roster returns only once its date has passed and nothing contradicts it`() {
        val roster = now.plusSeconds(30L * DAY)
        var checked = false
        val contradiction = { checked = true; false }

        assertFalse(SnapshotClockPolicy.mayReturn(roster, now, contradiction))
        assertFalse("contradiction is read only after the date has passed", checked)

        assertTrue(SnapshotClockPolicy.mayReturn(roster, roster, contradiction))
        assertTrue(checked)
        assertFalse(SnapshotClockPolicy.mayReturn(roster, roster.plusSeconds(DAY)) { true })
    }

    private companion object {
        const val DAY = 24L * 60L * 60L
    }
}
