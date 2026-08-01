package dev.gf2log.app.settings

import android.content.Context

class CapturePreferences(context: Context) {
    private val appContext = context.applicationContext

    var detailedNotifications: Boolean
        get() = UserSettingsPreferences.detailedNotifications(appContext)
        set(value) {
            UserSettingsPreferences.setDetailedNotifications(appContext, value)
        }
}
