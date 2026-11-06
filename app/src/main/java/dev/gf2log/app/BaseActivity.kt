package dev.gf2log.app

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import dev.gf2log.app.capture.PendingPlatoonAdmissionStore

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
        dialogDrafts += dev.gf2log.app.ui.Dialogs.readDrafts(savedInstanceState)
    }

    private val dialogDrafts = mutableListOf<dev.gf2log.app.ui.DialogDraft>()

    override fun onSaveInstanceState(outState: Bundle) {
        dev.gf2log.app.ui.Dialogs.saveDrafts(this, outState, dialogDrafts)
        super.onSaveInstanceState(outState)
    }

    /**
     * The input dialog named [key] that was open when the previous instance went away, for the
     * screen to reopen once it has what the dialog needs; null when there is none.
     */
    internal fun takeDialogDraft(key: String): dev.gf2log.app.ui.DialogDraft? =
        dialogDrafts.firstOrNull { it.key == key }?.also(dialogDrafts::remove)

    /** Allows a purpose-built surface to use a stable presentation theme. */
    protected open fun preferredTheme(context: Context): String = ThemePreferences.get(context)

    protected open fun supportsPendingPlatoonAdmission(): Boolean = true

    private var pendingCandidates = 0
    private val admissionListener: (List<PendingPlatoonAdmissionStore.Summary>) -> Unit = { candidates ->
        PendingPlatoonAdmissionPrompt.onCandidatesChanged(this, candidates)
        // A confirmed candidate becomes the active Platoon; let the screen rebind to it.
        if (candidates.size < pendingCandidates && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            onScreenResumed()
        }
        pendingCandidates = candidates.size
    }

    override fun onStart() {
        super.onStart()
        if (supportsPendingPlatoonAdmission() && OnboardingPreferences.isCompleted(this)) {
            PendingPlatoonAdmissionStore.addListener(admissionListener)
        }
    }

    override fun onStop() {
        PendingPlatoonAdmissionStore.removeListener(admissionListener)
        PendingPlatoonAdmissionPrompt.dismiss(this)
        super.onStop()
    }

    override fun onDestroy() {
        dev.gf2log.app.ui.Dialogs.dismissAll(this)
        super.onDestroy()
    }

    final override fun onResume() {
        super.onResume()
        if (
            LanguagePreferences.get(this) != attachedLanguage ||
            preferredTheme(this) != attachedTheme
        ) {
            recreate()
            return
        }
        foreground = java.lang.ref.WeakReference(this)
        if (!isFinishing) onScreenResumed()
    }

    /** Called from onResume only when this instance remains current. */
    protected open fun onScreenResumed() = Unit

    private var draftPrompt: java.lang.ref.WeakReference<android.app.Dialog>? = null

    /**
     * For a screen whose active Platoon changed while it holds an unsaved draft. The screen stays
     * bound to the draft's own storage, so [save] still writes where the draft came from, and the
     * user chooses to save or discard before it follows the new Platoon. Asked once while showing,
     * not again on every resume.
     */
    protected fun confirmDraftBeforeScopeChange(
        @androidx.annotation.StringRes saveLabel: Int,
        save: () -> Unit,
        discard: () -> Unit,
    ) {
        if (draftPrompt?.get()?.isShowing == true) return
        val dialog = dev.gf2log.app.ui.Dialogs.show(
            dev.gf2log.app.ui.Dialogs.builder(this)
                .setTitle(R.string.unsaved_changes_title)
                .setMessage(R.string.platoon_changed_unsaved_message)
                .setNegativeButton(R.string.discard) { _, _ -> discard() }
                .setPositiveButton(saveLabel) { _, _ -> save() },
        )
        draftPrompt = java.lang.ref.WeakReference(dialog)
    }

    companion object {
        @Volatile
        private var foreground = java.lang.ref.WeakReference<BaseActivity>(null)

        /** The most recently resumed screen, used to refresh after work that outlived its caller. */
        internal fun foregroundScreen(): BaseActivity? =
            foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
    }
}
