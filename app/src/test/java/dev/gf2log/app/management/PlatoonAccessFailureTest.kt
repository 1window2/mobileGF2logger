package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatoonAccessFailureTest {
    @Test
    fun `damage is recognized anywhere along the cause chain`() {
        assertEquals(
            PlatoonAccessFailure.DATABASE_DAMAGED,
            PlatoonAccessFailure.of(PlatoonDatabaseDamagedException("quarantined")),
        )
        assertEquals(
            PlatoonAccessFailure.DATABASE_DAMAGED,
            PlatoonAccessFailure.of(
                IllegalStateException("export failed", RuntimeException("wrapped", PlatoonDatabaseDamagedException("quick check"))),
            ),
        )
    }

    @Test
    fun `a held import claim asks to retry instead of reporting a failure`() {
        assertEquals(PlatoonAccessFailure.IMPORT_IN_PROGRESS, PlatoonAccessFailure.of(CsvImportInProgressException()))
        assertEquals(
            PlatoonAccessFailure.IMPORT_IN_PROGRESS,
            PlatoonAccessFailure.of(IllegalStateException("reconcile", CsvImportInProgressException())),
        )
    }

    @Test
    fun `damage outlasts the import so it wins over a claim`() {
        val both = PlatoonDatabaseDamagedException("damaged", CsvImportInProgressException())
        val wrapped = CsvImportInProgressException().also { it.initCause(PlatoonDatabaseDamagedException("damaged")) }

        assertEquals(PlatoonAccessFailure.DATABASE_DAMAGED, PlatoonAccessFailure.of(both))
        assertEquals(PlatoonAccessFailure.DATABASE_DAMAGED, PlatoonAccessFailure.of(wrapped))
    }

    @Test
    fun `anything else and no failure at all keep the generic message`() {
        assertEquals(PlatoonAccessFailure.OTHER, PlatoonAccessFailure.of(IllegalArgumentException("Invalid Platoon CSV")))
        assertEquals(PlatoonAccessFailure.OTHER, PlatoonAccessFailure.of(CsvCheckpointExpiredException()))
        assertEquals(PlatoonAccessFailure.OTHER, PlatoonAccessFailure.of(null))
    }
}
