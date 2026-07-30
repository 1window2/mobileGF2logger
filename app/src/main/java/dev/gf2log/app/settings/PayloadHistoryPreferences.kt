package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.protocol.PayloadCatalog

class PayloadHistoryPreferences(context: Context) {
    private val appContext = context.applicationContext

    fun isEnabled(payloadType: Int): Boolean {
        val category = PayloadCatalog.find(payloadType) ?: return false
        return category.isRequired ||
            UserSettingsPreferences.payloadHistoryEnabled(appContext, payloadType)
    }

    fun setEnabled(payloadType: Int, enabled: Boolean) {
        val category = PayloadCatalog.find(payloadType) ?: return
        if (category.isRequired) return
        UserSettingsPreferences.setPayloadHistoryEnabled(appContext, payloadType, enabled)
    }
}
