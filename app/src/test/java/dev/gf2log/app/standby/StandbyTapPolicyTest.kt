package dev.gf2log.app.standby

import dev.gf2log.app.capture.CaptureNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyTapPolicyTest {

    @Test
    fun `a rejected platoon profile stops the taps like an accepted one`() {
        listOf(
            CaptureNotice.CLIENT_UNVERIFIED,
            CaptureNotice.PROFILE_UPDATE_FAILED,
            CaptureNotice.IDENTIFIED_FLOW_BUFFER_OVERFLOW,
            CaptureNotice.PLATOON_DETECTED,
            CaptureNotice.ROSTER_SAVED,
            // A save into a damaged Platoon follows an identified, logged-in flow too.
            CaptureNotice.DATABASE_DAMAGED,
        ).forEach { notice -> assertTrue("$notice", StandbyTapPolicy.stopsTaps(notice)) }
    }

    @Test
    fun `notices a title screen can cause do not stop the taps`() {
        listOf(
            CaptureNotice.PREPARING,
            CaptureNotice.CAPTURING,
            CaptureNotice.TRAFFIC,
            CaptureNotice.FLOW_BUFFER_OVERFLOW,
        ).forEach { notice -> assertFalse("$notice", StandbyTapPolicy.stopsTaps(notice)) }
    }

    @Test
    fun `no tap is sent after a post-login notice`() {
        listOf(
            CaptureNotice.CLIENT_UNVERIFIED,
            CaptureNotice.PROFILE_UPDATE_FAILED,
            CaptureNotice.IDENTIFIED_FLOW_BUFFER_OVERFLOW,
        ).forEach { notice ->
            val taps = simulateTaps(noticeAt = 20_000L, notice = notice)
            assertTrue("$notice: taps after it at ${taps.filter { it > 20_000L }}", taps.none { it > 20_000L })
            assertEquals("$notice: only the 15 s and 19 s taps", listOf(15_000L, 19_000L), taps)
        }
    }

    @Test
    fun `without any notice the taps stop at the end of the window`() {
        val taps = simulateTaps(noticeAt = null, notice = null)

        assertEquals(15_000L, taps.first())
        assertTrue(taps.last() < TAP_WINDOW_MS)
    }

    @Test
    fun `an unverifiable client ends the run at once with its own outcome`() {
        assertEquals(StandbyOutcome.CLIENT_UNVERIFIED, StandbyTapPolicy.endsRun(CaptureNotice.CLIENT_UNVERIFIED))
        assertNull(StandbyTapPolicy.endsRun(CaptureNotice.PROFILE_UPDATE_FAILED))
        assertNull(StandbyTapPolicy.endsRun(CaptureNotice.PLATOON_DETECTED))
    }

    /** Ticks once a second through a run, as StandbyAutomation does, and returns when taps went out. */
    private fun simulateTaps(noticeAt: Long?, notice: CaptureNotice?): List<Long> {
        var nextTapAt = FIRST_TAP_DELAY_MS
        var loggedIn = false
        val taps = mutableListOf<Long>()
        for (now in 0L..TAP_WINDOW_MS + 10_000L step 1_000L) {
            if (noticeAt != null && notice != null && now >= noticeAt && StandbyTapPolicy.stopsTaps(notice)) loggedIn = true
            if (StandbyTapPolicy.tapDue(now, nextTapAt, TAP_WINDOW_MS, loggedIn)) {
                nextTapAt = now + TAP_INTERVAL_MS
                taps += now
            }
        }
        return taps
    }

    private companion object {
        const val FIRST_TAP_DELAY_MS = 15_000L
        const val TAP_INTERVAL_MS = 4_000L
        const val TAP_WINDOW_MS = 120_000L
    }
}
