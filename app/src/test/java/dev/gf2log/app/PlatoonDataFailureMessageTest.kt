package dev.gf2log.app

import dev.gf2log.app.management.CsvCheckpointExpiredException
import dev.gf2log.app.management.CsvImportInProgressException
import dev.gf2log.app.management.InvalidBackupException
import dev.gf2log.app.management.PlatoonDatabaseDamagedException
import dev.gf2log.app.ui.CaptureGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatoonDataFailureMessageTest {
    private val damaged = IllegalStateException("load failed", PlatoonDatabaseDamagedException("quarantined"))
    private val importing = IllegalStateException("load failed", CsvImportInProgressException())

    @Test
    fun `a screen action names damage and a running import instead of its generic failure`() {
        assertEquals(R.string.platoon_database_damaged_short, platoonDataFailureMessage(damaged, R.string.save_failed))
        assertEquals(R.string.platoon_csv_import_in_progress, platoonDataFailureMessage(importing, R.string.save_failed))
        assertEquals(R.string.save_failed, platoonDataFailureMessage(IllegalArgumentException("overlap"), R.string.save_failed))
        // A stored `false` has no failure to explain.
        assertEquals(R.string.note_delete_failed, platoonDataFailureMessage(null, R.string.note_delete_failed))
    }

    @Test
    fun `a rebuild reports its outcome and says what a refusal waits for`() {
        assertEquals(R.string.platoon_database_rebuilt, rebuildResultMessage(null))
        assertEquals(R.string.stop_capture_before_rebuild, rebuildResultMessage(CaptureGuard.CaptureActiveException()))
        assertEquals(R.string.platoon_csv_import_in_progress, rebuildResultMessage(CsvImportInProgressException()))
        assertEquals(R.string.platoon_database_rebuild_failed, rebuildResultMessage(IllegalStateException("rename failed")))
    }

    @Test
    fun `a second import or undo while one runs is told to wait`() {
        assertEquals(R.string.platoon_csv_import_in_progress, csvImportFailureMessage(CsvImportInProgressException()))
        assertEquals(R.string.csv_import_database_damaged, csvImportFailureMessage(damaged))
        assertEquals(R.string.platoon_csv_import_in_progress, csvUndoFailureMessage(importing))
        assertEquals(R.string.platoon_database_damaged_short, csvUndoFailureMessage(damaged))
        assertEquals(R.string.csv_import_undo_expired, csvUndoFailureMessage(CsvCheckpointExpiredException()))
    }

    @Test
    fun `a restore refused by a running import is not an invalid or failed backup`() {
        assertEquals(R.string.platoon_csv_import_in_progress, fullBackupRestoreFailureMessage(CsvImportInProgressException()))
        assertEquals(R.string.invalid_full_backup, fullBackupRestoreFailureMessage(InvalidBackupException(IllegalArgumentException("bad manifest"))))
        assertEquals(R.string.full_backup_restore_failed, fullBackupRestoreFailureMessage(IllegalStateException("io")))
    }
}
