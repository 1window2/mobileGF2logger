package dev.gf2log.app

import android.content.Context
import dev.gf2log.app.settings.UserSettingsPreferences

object TargetPackagePreferences {
    // Retained for settings and backup compatibility. Runtime capture targets
    // are fixed by SupportedGamePackages and no longer read this preference.
    // Activity.getPreferences() uses ComponentName.getShortClassName(), which
    // retains the leading dot for dev.gf2log.app.MainActivity.
    internal const val LEGACY_PREFERENCES = ".app.MainActivity"
    internal const val KEY_TARGET_PACKAGE = "target_package"
    const val DEFAULT_TARGET_PACKAGE = SupportedGamePackages.HAOPLAY

    fun get(context: Context): String = UserSettingsPreferences.targetPackage(context)

    fun set(context: Context, targetPackage: String) {
        UserSettingsPreferences.setTargetPackage(context, targetPackage)
    }
}
