package dev.gf2log.app.management

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileNameTest {
    @Test
    fun `accepts only named canonical backup files`() {
        assertTrue(BackupFileName.isValid("mobileGF2logger-full.gf2backup"))
        assertTrue(BackupFileName.isValid("BACKUP.GF2BACKUP"))

        assertFalse(BackupFileName.isValid(null))
        assertFalse(BackupFileName.isValid(".gf2backup"))
        assertFalse(BackupFileName.isValid("backup.zip"))
        assertFalse(BackupFileName.isValid("backup.gf2backup.csv"))
    }
}
