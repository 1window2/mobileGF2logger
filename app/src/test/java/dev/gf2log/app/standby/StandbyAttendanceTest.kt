package dev.gf2log.app.standby

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyAttendanceTest {
    @After
    fun reset() = StandbyAttendance.attended()

    @Test
    fun `Home resumed by standby closing itself pins nothing until the first touch`() {
        // finish(): the phase is already idle when the standby screen closes and Home resumes.
        StandbyAttendance.automationEnded()

        assertFalse(StandbyAttendance.mayPinOnResume(armed = false))
        // The first touch pins once; later resumes behave as before.
        assertTrue(StandbyAttendance.takeUserReturn(armed = false))
        assertFalse(StandbyAttendance.takeUserReturn(armed = false))
        assertTrue(StandbyAttendance.mayPinOnResume(armed = false))
    }

    @Test
    fun `an armed standby never pins on resume or touch`() {
        assertFalse(StandbyAttendance.mayPinOnResume(armed = true))

        StandbyAttendance.automationEnded()

        assertFalse(StandbyAttendance.takeUserReturn(armed = true))
        assertFalse(StandbyAttendance.mayPinOnResume(armed = false))
    }

    @Test
    fun `tapping standby away or arming again restores pinning on resume without pinning itself`() {
        StandbyAttendance.automationEnded()

        StandbyAttendance.attended()

        assertTrue(StandbyAttendance.mayPinOnResume(armed = false))
        assertFalse(StandbyAttendance.takeUserReturn(armed = false))
    }
}
