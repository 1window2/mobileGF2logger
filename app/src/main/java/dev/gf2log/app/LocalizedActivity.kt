package dev.gf2log.app

import android.app.Activity
import android.content.Context
import android.os.Bundle

abstract class LocalizedActivity : Activity() {
    private var attachedLanguage = LanguagePreferences.DEFAULT_LANGUAGE
    private var attachedTheme = ThemePreferences.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        attachedLanguage = LanguagePreferences.get(newBase)
        attachedTheme = ThemePreferences.get(newBase)
        val localized = LanguagePreferences.wrap(newBase, attachedLanguage)
        super.attachBaseContext(ThemePreferences.wrap(localized, attachedTheme))
    }

    override fun onResume() {
        super.onResume()
        if (
            LanguagePreferences.get(this) != attachedLanguage ||
            ThemePreferences.get(this) != attachedTheme
        ) {
            recreate()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        ModernUi.prepareContent(window.decorView)
    }
}
