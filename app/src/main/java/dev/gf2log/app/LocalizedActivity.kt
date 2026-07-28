package dev.gf2log.app

import android.app.Activity
import android.content.Context

abstract class LocalizedActivity : Activity() {
    private var attachedLanguage = LanguagePreferences.DEFAULT_LANGUAGE

    override fun attachBaseContext(newBase: Context) {
        attachedLanguage = LanguagePreferences.get(newBase)
        super.attachBaseContext(LanguagePreferences.wrap(newBase, attachedLanguage))
    }

    override fun onResume() {
        super.onResume()
        if (LanguagePreferences.get(this) != attachedLanguage) recreate()
    }
}
