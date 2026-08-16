package dev.gf2log.app

import android.content.Context
import dev.gf2log.app.settings.UserSettingsPreferences

/** Persists the one-time onboarding completion gate in app-private settings. */
object OnboardingPreferences {
    fun isCompleted(context: Context): Boolean =
        UserSettingsPreferences.onboardingCompleted(context)

    fun complete(context: Context) {
        UserSettingsPreferences.setOnboardingCompleted(context, true)
    }
}
