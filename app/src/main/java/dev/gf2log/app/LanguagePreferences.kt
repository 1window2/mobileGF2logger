package dev.gf2log.app

import android.content.Context
import android.content.res.Configuration
import dev.gf2log.app.settings.UserSettingsPreferences
import java.util.Locale

object LanguagePreferences {
    const val DEFAULT_LANGUAGE = "en"
    const val KOREAN = "ko"
    fun get(context: Context): String = UserSettingsPreferences.language(context)
        .takeIf { it == DEFAULT_LANGUAGE || it == KOREAN }
        ?: DEFAULT_LANGUAGE

    fun set(context: Context, language: String) {
        require(language == DEFAULT_LANGUAGE || language == KOREAN)
        UserSettingsPreferences.setLanguage(context, language)
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
