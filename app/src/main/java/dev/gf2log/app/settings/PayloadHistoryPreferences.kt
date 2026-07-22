package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.protocol.PayloadCatalog

class PayloadHistoryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(payloadType: Int): Boolean {
        val category = PayloadCatalog.find(payloadType) ?: return false
        return category.isRequired || preferences.getBoolean(key(payloadType), false)
    }

    fun setEnabled(payloadType: Int, enabled: Boolean) {
        val category = PayloadCatalog.find(payloadType) ?: return
        if (category.isRequired) return
        preferences.edit().putBoolean(key(payloadType), enabled).apply()
    }

    private fun key(payloadType: Int): String = "payload_$payloadType"

    private companion object {
        const val PREFERENCES_NAME = "payload_history_options"
    }
}
