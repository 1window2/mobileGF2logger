package dev.gf2log.app.ui

import dev.gf2log.app.capture.CapturePhase
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.MainThread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureGuardTest {
    @Before
    fun setUp() {
        MainThread.testExecutor = { it() }
        CaptureStatus.markStopped()
    }

    @After
    fun tearDown() {
        CaptureStatus.markStopped()
        MainThread.testExecutor = null
    }

    @Test
    fun `maintenance runs while capture is stopped`() {
        assertEquals("deleted", CaptureGuard.whileStopped { "deleted" })
    }

    @Test
    fun `a capture started after the user was asked keeps the deletion from running`() {
        // The guard passed while STOPPED; standby or an alarm then started capture before the
        // user finished confirming.
        assertEquals(CapturePhase.STOPPED, CaptureStatus.snapshot().phase)
        assertTrue(CaptureGuard.beginCaptureStart())
        var deleted = false

        val result = CaptureGuard.whileStopped { deleted = true }

        assertNull(result)
        assertFalse(deleted)
    }

    @Test
    fun `no capture start is allowed while maintenance runs`() {
        CaptureGuard.whileStopped {
            assertFalse(CaptureGuard.beginCaptureStart())
            assertEquals(CapturePhase.STOPPED, CaptureStatus.snapshot().phase)
        }

        assertTrue(CaptureGuard.beginCaptureStart())
        assertEquals(CapturePhase.STARTING, CaptureStatus.snapshot().phase)
    }

    @Test
    fun `a failing maintenance still lets capture start afterwards`() {
        runCatching { CaptureGuard.whileStopped { error("disk full") } }

        assertTrue(CaptureGuard.beginCaptureStart())
    }

    @Test
    fun `a start that never reached the service is taken back`() {
        assertTrue(CaptureGuard.beginCaptureStart())

        CaptureGuard.abandonCaptureStart()

        assertEquals(CapturePhase.STOPPED, CaptureStatus.snapshot().phase)
        assertEquals(Unit, CaptureGuard.whileStopped { })
    }
}
