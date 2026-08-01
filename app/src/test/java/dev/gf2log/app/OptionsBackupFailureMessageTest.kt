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
}
