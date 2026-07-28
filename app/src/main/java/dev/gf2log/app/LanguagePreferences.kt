package dev.gf2log.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LanguagePreferences {
    const val DEFAULT_LANGUAGE = "en"
    const val KOREAN = "ko"
    private const val PREFERENCES = "display_settings"
    private const val KEY_LANGUAGE = "language"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, DEFAULT_LANGUAGE)
            ?.takeIf { it == DEFAULT_LANGUAGE || it == KOREAN }
            ?: DEFAULT_LANGUAGE

    fun set(context: Context, language: String) {
        require(language == DEFAULT_LANGUAGE || language == KOREAN)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }

    fun wrap(context: Context, language: String = get(context)): Context {
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
