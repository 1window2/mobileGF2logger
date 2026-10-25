package dev.gf2log.app.standby

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The accessibility service standby mode uses to act for the user while they sleep: tap the
 * game's start screen, close the game from Recents, lock the phone, and keep the screen on
 * between steps.
 *
 * It reads nothing on screen except which app is in front, so taps never land outside the game,
 * and the app names on Recents cards, to close the right one. Every screen-on hold carries its own expiry, so a failure elsewhere can never keep the phone
 * awake past the standby deadline.
 */
class StandbyAutomationService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var foregroundPackage: String? = null
    private val expireHold = Runnable { releaseScreen() }

    override fun onServiceConnected() {
        current = java.lang.ref.WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        releaseScreen()
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    /** The package of the window currently in front, if known. */
    fun frontPackage(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: foregroundPackage

    /**
     * Taps at a point given as fractions of the screen, but only while [packageName] is in front.
     * Returns false when the tap was not sent.
     */
    fun tapIfFront(packageName: String, xFraction: Float, yFraction: Float): Boolean {
        if (frontPackage() != packageName) return false
        // The real size of the default display in its current rotation, system bars included,
        // so the fractions match a full-screen game in landscape.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val path = Path().apply { moveTo(metrics.widthPixels * xFraction, metrics.heightPixels * yFraction) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Closes [packageName] the way a person would, by dismissing its card in Recents; Android
     * does not let one app stop another. [done] gets whether a card was dismissed.
     */
    fun closeFromRecents(packageName: String, done: (Boolean) -> Unit) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
        if (label.isNullOrBlank() || !performGlobalAction(GLOBAL_ACTION_RECENTS)) return done(false)
        tryDismiss(label, RECENTS_ATTEMPTS, done)
    }

    /** Recents animates in at its own pace, slower on some phones; look again before giving up. */
    private fun tryDismiss(label: String, attemptsLeft: Int, done: (Boolean) -> Unit) {
        handler.postDelayed({
            when {
                dismissCard(label) -> done(true)
                attemptsLeft > 1 -> tryDismiss(label, attemptsLeft - 1, done)
                else -> done(false)
            }
        }, RECENTS_SETTLE_MS)
    }

    private fun dismissCard(label: String): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        val card = root.findAccessibilityNodeInfosByText(label)
            .firstOrNull { it.contentDescription?.toString() == label } ?: return false
        val launcher = runCatching { packageManager.getResourcesForApplication(card.packageName.toString()) }.getOrNull()
        // The launcher's own "Close" action on the card, recognised by its resource name so any
        // language works; the standard dismiss action where a launcher offers that instead.
        val close = card.actionList.firstOrNull { action ->
            action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id ||
                (action.label != null && launcher != null && runCatching {
                    launcher.getResourceEntryName(action.id).contains("close", ignoreCase = true)
                }.getOrDefault(false))
        } ?: return false
        return card.performAction(close.id)
    }

    /** Locks the phone; false when the platform refused, so the caller can fall back to timeout. */
    fun lockScreen(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    /**
     * Keeps the screen on, without covering anything, until [releaseScreen] or [untilMillis]
     * (wall clock), whichever comes first.
     */
    fun holdScreenOn(untilMillis: Long) {
        handler.removeCallbacks(expireHold)
        val delay = untilMillis - System.currentTimeMillis()
        if (delay <= 0) {
            releaseScreen()
            return
        }
        if (overlay == null) {
            val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            val added = runCatching { getSystemService(WindowManager::class.java).addView(view, params) }.isSuccess
            if (added) overlay = view
        }
        handler.postDelayed(expireHold, delay)
    }

    fun releaseScreen() {
        handler.removeCallbacks(expireHold)
        overlay?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        overlay = null
    }

    companion object {
        private const val TAP_DURATION_MS = 60L
        private const val RECENTS_SETTLE_MS = 800L
        private const val RECENTS_ATTEMPTS = 4

        @Volatile
        private var current = java.lang.ref.WeakReference<StandbyAutomationService>(null)

        /** The connected service, or null while the user has it turned off. */
        val instance: StandbyAutomationService? get() = current.get()

        /** Whether the user has turned this service on in the system accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, StandbyAutomationService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
