package dev.gf2log.app.management

internal object BackupFormatPolicy {
    const val PLATOON_ONLY_VERSION = 1
    const val COMPLETE_VERSION = 2
    const val LEGACY_SCOPED_VERSION = 3
    const val SCOPED_VERSION = 4

    fun requirePlatoonOnly(formatVersion: Int, hasSettings: Boolean) {
        require(
            formatVersion in setOf(
                PLATOON_ONLY_VERSION,
                LEGACY_SCOPED_VERSION,
                SCOPED_VERSION,
            ) && !hasSettings,
        ) {
            "Complete backups must be restored from Settings"
        }
    }

    fun requireComplete(formatVersion: Int, hasSettings: Boolean) {
        require(supportsComplete(formatVersion, hasSettings)) {
            "Backup does not contain complete app settings"
        }
    }

    fun supportsComplete(formatVersion: Int, hasSettings: Boolean): Boolean =
        formatVersion in setOf(COMPLETE_VERSION, LEGACY_SCOPED_VERSION, SCOPED_VERSION) && hasSettings

    /**
     * The restore an archive receives. A null [requestComplete] auto-detects: archives carrying
     * restorable app settings are restored completely, anything else as Platoon data only.
     */
    fun restoredKind(
        formatVersion: Int,
        hasSettings: Boolean,
        requestComplete: Boolean?,
    ): RestoredBackupKind = when (requestComplete ?: supportsComplete(formatVersion, hasSettings)) {
        true -> RestoredBackupKind.COMPLETE
        false -> RestoredBackupKind.PLATOON
    }
}
