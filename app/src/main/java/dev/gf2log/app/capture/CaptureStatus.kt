package dev.gf2log.app.capture

import android.content.Context

object CaptureStatus {
    private const val PREFERENCES = "capture_status"
    private const val KEY_MESSAGE = "message"
    private const val DEFAULT_MESSAGE = "Capture is stopped"

    fun read(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY_MESSAGE, DEFAULT_MESSAGE) ?: DEFAULT_MESSAGE

    fun write(context: Context, message: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MESSAGE, message)
            .apply()
    }
}
