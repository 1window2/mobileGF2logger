package dev.gf2log.app

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Applies the persisted language and appearance before views exist, draws edge to edge, and
 * hosts the pending-Platoon admission prompt for every screen that supports it.
 */
abstract class BaseActivity : ComponentActivity() {
    private var attachedLanguage = LanguagePreferences.DEFAULT_LANGUAGE
    private var attachedTheme = ThemePreferences.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        attachedLanguage = LanguagePreferences.get(newBase)
        attachedTheme = preferredTheme(newBase)
        val localized = LanguagePreferences.wrap(newBase, attachedLanguage)
        super.attachBaseContext(ThemePreferences.wrap(localized, attachedTheme))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The default detector reads this Activity's resources, which already carry the app's
        // own light/dark choice rather than the system setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
    }

    /** Allows a purpose-built surface to use a stable presentation theme. */
    protected open fun preferredTheme(context: Context): String = ThemePreferences.get(context)

    protected open fun supportsPendingPlatoonAdmission(): Boolean = true

    final override fun onResume() {
        super.onResume()
        if (
            LanguagePreferences.get(this) != attachedLanguage ||
            preferredTheme(this) != attachedTheme
        ) {
            recreate()
            return
        }
        onScreenResumed()
        if (supportsPendingPlatoonAdmission() && OnboardingPreferences.isCompleted(this)) {
            PendingPlatoonAdmissionPrompt.showIfNeeded(this)
        }
    }

    /** Called from onResume only when this instance remains current. */
    protected open fun onScreenResumed() = Unit

    /** Screens not yet composed from shared components keep the legacy tree styling pass. */
    protected open val legacyStyling: Boolean = true

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!legacyStyling) return
        val content = findViewById<android.view.View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        ModernUi.prepareContent(window.decorView)
        window.decorView.post { if (!isFinishing) ModernUi.prepareContent(window.decorView) }
    }
}
