package dev.gf2log.app.settings

import android.content.Context

class CapturePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    var detailedNotifications: Boolean
        get() = preferences.getBoolean(KEY_DETAILED_NOTIFICATIONS, true)
        set(value) {
            preferences.edit().putBoolean(KEY_DETAILED_NOTIFICATIONS, value).apply()
        }

    companion object {
        private const val PREFERENCES = "capture_preferences"
        private const val KEY_DETAILED_NOTIFICATIONS = "detailed_notifications"
    }
}
