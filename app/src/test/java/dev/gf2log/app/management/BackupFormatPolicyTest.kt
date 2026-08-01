package dev.gf2log.app.management

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupFormatPolicyTest {
    @Test
    fun `Platoon restore accepts only legacy database-only backups`() {
        BackupFormatPolicy.requirePlatoonOnly(
            BackupFormatPolicy.PLATOON_ONLY_VERSION,
            hasSettings = false,
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupFormatPolicy.requirePlatoonOnly(
                BackupFormatPolicy.COMPLETE_VERSION,
                hasSettings = true,
            )
        }
    }

    @Test
    fun `complete restore rejects legacy and incomplete backups`() {
        BackupFormatPolicy.requireComplete(
            BackupFormatPolicy.COMPLETE_VERSION,
            hasSettings = true,
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackupFormatPolicy.requireComplete(
                BackupFormatPolicy.PLATOON_ONLY_VERSION,
                hasSettings = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupFormatPolicy.requireComplete(
                BackupFormatPolicy.COMPLETE_VERSION,
                hasSettings = false,
            )
        }
    }
}
