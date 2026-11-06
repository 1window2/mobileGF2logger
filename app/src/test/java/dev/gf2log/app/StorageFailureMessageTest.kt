package dev.gf2log.app

import dev.gf2log.app.management.CsvCheckpointExpiredException
import dev.gf2log.app.management.PlatoonDatabaseDamagedException
import dev.gf2log.app.management.PlatoonProfileAdministration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageFailureMessageTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a damaged database is named instead of a generic import failure`() {
        assertEquals(
            R.string.csv_import_database_damaged,
            csvImportFailureMessage(
                IllegalStateException("export failed", PlatoonDatabaseDamagedException("quick check")),
            ),
        )
        assertEquals(
            R.string.status_platoon_csv_import_failed,
            csvImportFailureMessage(IllegalArgumentException("Invalid Platoon CSV")),
        )
    }

    @Test
    fun `an expired undo checkpoint is explained`() {
        assertEquals(R.string.csv_import_undo_expired, csvUndoFailureMessage(CsvCheckpointExpiredException()))
        assertEquals(R.string.csv_import_undo_failed, csvUndoFailureMessage(IllegalStateException("missing")))
    }

    @Test
    fun `clearing the share cache deletes every file even when revoking fails`() {
        val directory = temporary.newFolder("shared-weekly")
        val first = directory.resolve("GF2logger-week-20260802-a.png").apply { writeText("a") }
        val second = directory.resolve("GF2logger-week-20260809-b.png").apply { writeText("b") }
        val revoked = mutableListOf<String>()

        WeeklyPngExport.deleteCachedFiles(directory) { file ->
            revoked += file.name
            if (file == first) error("grant already gone")
        }

        assertEquals(setOf(first.name, second.name), revoked.toSet())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `capture diagnostics are cleared only when they name the deleted Platoon`() {
        assertTrue(PlatoonProfileAdministration.namesPlatoon("Saved 12 members to Alpha", "Alpha"))
        assertFalse(PlatoonProfileAdministration.namesPlatoon("Saved 12 members to Bravo", "Alpha"))
        assertFalse(PlatoonProfileAdministration.namesPlatoon("anything", " "))
    }
}
