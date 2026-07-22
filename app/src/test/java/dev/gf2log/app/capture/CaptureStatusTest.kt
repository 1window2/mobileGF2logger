package dev.gf2log.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatusTest {
    @Test
    fun tracksOnlyTheCurrentApplicationProcess() {
        CaptureStatus.markStopped()
        assertFalse(CaptureStatus.isRunning)
        assertEquals("Capture is stopped", CaptureStatus.read())

        CaptureStatus.markRunning("Capturing selected package")
        CaptureStatus.update("Decoded guild members")
        assertTrue(CaptureStatus.isRunning)
        assertEquals("Decoded guild members", CaptureStatus.read())

        CaptureStatus.markStopped("Capture failed")
        assertFalse(CaptureStatus.isRunning)
        assertEquals("Capture failed", CaptureStatus.read())
    }
}
