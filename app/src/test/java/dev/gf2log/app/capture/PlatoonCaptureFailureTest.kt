package dev.gf2log.app.capture

import dev.gf2log.app.management.CsvImportInProgressException
import dev.gf2log.app.management.PlatoonDatabaseDamagedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonCaptureFailureTest {
    @Test
    fun `a damaged database is never recovered by reconciling, which would rebuild it roster-only`() {
        assertFalse(PlatoonCaptureSession.recoversByReconcile(PlatoonDatabaseDamagedException("quarantined")))
        assertFalse(
            PlatoonCaptureSession.recoversByReconcile(IllegalStateException("ingest", PlatoonDatabaseDamagedException("x"))),
        )
    }

    @Test
    fun `a held import claim is not recovered either, and the retained CSV waits for a later reconcile`() {
        assertFalse(PlatoonCaptureSession.recoversByReconcile(CsvImportInProgressException()))
    }

    @Test
    fun `an ordinary ingest failure still tries the retained CSV`() {
        assertTrue(PlatoonCaptureSession.recoversByReconcile(IllegalStateException("constraint failed")))
    }

    @Test
    fun `a save into a damaged database names the Platoon instead of the generic notice`() {
        assertEquals(
            CaptureNotice.DATABASE_DAMAGED to listOf("Alpha"),
            PlatoonCaptureSession.saveFailureNotice(
                PlatoonDatabaseDamagedException("quarantined"),
                "Alpha",
                CaptureNotice.ACTIVITY_SAVE_FAILED,
            ),
        )
        listOf(
            CaptureNotice.ROSTER_SAVE_FAILED,
            CaptureNotice.ACTIVITY_SAVE_FAILED,
            CaptureNotice.UPDATES_SAVE_FAILED,
        ).forEach { fallback ->
            assertEquals(
                fallback to emptyList<String>(),
                PlatoonCaptureSession.saveFailureNotice(IllegalStateException("disk full"), "Alpha", fallback),
            )
        }
    }
}
