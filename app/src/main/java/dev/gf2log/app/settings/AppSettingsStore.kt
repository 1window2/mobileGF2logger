package dev.gf2log.app.settings

import android.content.Context

internal interface BackupSettingsStore {
    fun read(): AppBackupSettings

    fun replace(settings: AppBackupSettings)
}

internal class AppSettingsStore(context: Context) : BackupSettingsStore {
    private val appContext = context.applicationContext

    override fun read(): AppBackupSettings = UserSettingsPreferences.read(appContext)

    override fun replace(settings: AppBackupSettings) =
        UserSettingsPreferences.replace(appContext, settings)
}
