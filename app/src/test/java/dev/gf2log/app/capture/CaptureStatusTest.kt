package dev.gf2log.app.capture

import dev.gf2log.protocol.Gfl2PayloadDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatusTest {
    @Test
    fun tracksOnlyTheCurrentApplicationProcess() {
        CaptureStatus.markStopped()
        assertFalse(CaptureStatus.isRunning)
        assertEquals("Capture is stopped", CaptureStatus.read())

        CaptureStatus.markRunning("Capturing selected package")
        CaptureStatus.update("Decoded Platoon members")
        assertTrue(CaptureStatus.isRunning)
        assertEquals("Decoded Platoon members", CaptureStatus.read())

        CaptureStatus.markStopped("Capture failed")
        assertFalse(CaptureStatus.isRunning)
        assertEquals("Capture failed", CaptureStatus.read())
    }

    @Test
    fun exposesImmutableGuidedCaptureProgressUntilTheNextSession() {
        CaptureStatus.beginSession(guided = true)
        CaptureStatus.markUsefulPayload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS)
        val progress = requireNotNull(CaptureStatus.readGuidedProgress())
        assertEquals(
            setOf(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS),
            progress.capturedPayloadTypes,
        )

        CaptureStatus.markStopped("Guided capture complete")
        assertEquals(progress, CaptureStatus.readGuidedProgress())

        CaptureStatus.beginSession(guided = false)
        assertNull(CaptureStatus.readGuidedProgress())
    }
}
