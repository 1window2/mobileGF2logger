package dev.gf2log.app.management

internal object BackupFormatPolicy {
    const val PLATOON_ONLY_VERSION = 1
    const val COMPLETE_VERSION = 2
    const val SCOPED_VERSION = 3

    fun requirePlatoonOnly(formatVersion: Int, hasSettings: Boolean) {
        require(
            formatVersion in setOf(PLATOON_ONLY_VERSION, SCOPED_VERSION) && !hasSettings,
        ) {
            "Complete backups must be restored from Settings"
        }
    }

    fun requireComplete(formatVersion: Int, hasSettings: Boolean) {
        require(formatVersion in setOf(COMPLETE_VERSION, SCOPED_VERSION) && hasSettings) {
            "Backup does not contain complete app settings"
        }
    }
}
