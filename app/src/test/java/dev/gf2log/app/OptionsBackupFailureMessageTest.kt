package dev.gf2log.app

import dev.gf2log.app.management.InvalidBackupException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class OptionsBackupFailureMessageTest {
    @Test
    fun `validation failures identify an invalid complete backup`() {
        assertEquals(
            R.string.invalid_full_backup,
            fullBackupRestoreFailureMessage(
                InvalidBackupException(IllegalArgumentException("invalid manifest")),
            ),
        )
    }

    @Test
    fun `operational failures do not blame the selected backup`() {
        assertEquals(
            R.string.full_backup_restore_failed,
            fullBackupRestoreFailureMessage(IOException("document provider failed")),
        )
        assertEquals(
            R.string.full_backup_restore_failed,
            fullBackupRestoreFailureMessage(IllegalStateException("unexpected restore failure")),
        )
        assertEquals(
            R.string.full_backup_restore_failed,
            fullBackupRestoreFailureMessage(IllegalArgumentException("local restore state")),
        )
    }

    @Test
    fun `export failures name the backup kind that failed`() {
        assertEquals(R.string.full_backup_export_failed, backupExportFailureMessage(complete = true))
        assertEquals(R.string.status_backup_export_failed, backupExportFailureMessage(complete = false))
    }

    @Test
    fun `a damaged export is still reported after its screen was recreated or closed`() {
        // The rebuild prompt cannot show on a destroyed screen, so the outcome falls back to a toast.
        assertEquals(BackupOutcomeDelivery.DAMAGED_NOTICE, backupOutcomeDelivery(damaged = true, ownerGone = true))
        assertEquals(BackupOutcomeDelivery.OFFER_REBUILD, backupOutcomeDelivery(damaged = true, ownerGone = false))
        assertEquals(BackupOutcomeDelivery.RESULT_NOTICE, backupOutcomeDelivery(damaged = false, ownerGone = true))
        assertEquals(BackupOutcomeDelivery.RESULT_NOTICE, backupOutcomeDelivery(damaged = false, ownerGone = false))
    }
}
