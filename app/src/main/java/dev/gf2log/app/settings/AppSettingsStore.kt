package dev.gf2log.app.settings

import android.content.Context

class AppSettingsStore(context: Context) {
    private val appContext = context.applicationContext

    fun read(): AppBackupSettings = UserSettingsPreferences.read(appContext)

    fun replace(settings: AppBackupSettings) =
        UserSettingsPreferences.replace(appContext, settings)
}
