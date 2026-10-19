package dev.gf2log.app.standby

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * The one-time offer, made the first time standby mode is entered, to exclude the app from battery
 * optimization so the system does not stop it during an overnight standby. It is asked only once;
 * after that the user changes it in the app's battery settings.
 */
internal object StandbyBatteryPrompt {

    fun shouldAsk(context: Context): Boolean =
        !isExempt(context) && !prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit { putBoolean(KEY_ASKED, true) }
    }

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** The system's own allow/deny dialog for this app; distributed outside Play, so the policy limit does not apply. */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "standby_mode"
    private const val KEY_ASKED = "battery_optimization_asked"
}
