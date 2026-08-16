package dev.gf2log.app

import android.content.Context
import android.content.res.Configuration
import dev.gf2log.app.settings.UserSettingsPreferences

/** Owns the persisted light, dark, or system display choice. */
object ThemePreferences {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun get(context: Context): String = UserSettingsPreferences.themeMode(context)
        .takeIf { it in SUPPORTED }
        ?: SYSTEM

    fun set(context: Context, mode: String) {
        require(mode in SUPPORTED)
        UserSettingsPreferences.setThemeMode(context, mode)
    }

    fun wrap(context: Context, mode: String = get(context)): Context {
        if (mode == SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        val requestedNightMode = if (mode == DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                requestedNightMode
        return context.createConfigurationContext(configuration)
    }

    private val SUPPORTED = setOf(SYSTEM, LIGHT, DARK)
}
