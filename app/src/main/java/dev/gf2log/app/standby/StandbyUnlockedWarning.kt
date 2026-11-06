package dev.gf2log.app.standby

import android.content.Context
import androidx.core.content.edit

/**
 * Standby keeps the phone unlocked behind a black screen that looks switched off. On a phone with
 * a PIN, pattern, or password, the user is told so, and agrees, once before standby first opens.
 */
internal object StandbyUnlockedWarning {

    fun shouldWarn(context: Context): Boolean =
        isRequired(secure = hasScreenLock(context), acknowledged = prefs(context).getBoolean(KEY_ACKNOWLEDGED, false))

    /** Only a screen lock that standby leaves open needs the warning, and only until it is agreed to. */
    fun isRequired(secure: Boolean, acknowledged: Boolean): Boolean = secure && !acknowledged

    fun acknowledge(context: Context) {
        prefs(context).edit { putBoolean(KEY_ACKNOWLEDGED, true) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "standby_mode"
    private const val KEY_ACKNOWLEDGED = "unlocked_warning_acknowledged"
}
