package dev.gf2log.app.management

object BackupFileName {
    fun isValid(name: String?): Boolean {
        if (name == null) return false
        val suffix = ".${PlatoonBackupManager.FILE_EXTENSION}"
        return name.length > suffix.length && name.endsWith(suffix, ignoreCase = true)
    }
}
