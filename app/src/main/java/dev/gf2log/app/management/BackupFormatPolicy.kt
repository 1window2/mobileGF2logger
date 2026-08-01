package dev.gf2log.app.management

internal object BackupFormatPolicy {
    const val PLATOON_ONLY_VERSION = 1
    const val COMPLETE_VERSION = 2

    fun requirePlatoonOnly(formatVersion: Int, hasSettings: Boolean) {
        require(formatVersion == PLATOON_ONLY_VERSION && !hasSettings) {
            "Complete backups must be restored from Settings"
        }
    }

    fun requireComplete(formatVersion: Int, hasSettings: Boolean) {
        require(formatVersion == COMPLETE_VERSION && hasSettings) {
            "Backup does not contain complete app settings"
        }
    }
}
