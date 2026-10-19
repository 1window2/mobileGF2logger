package dev.gf2log.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.ZoneId
import dev.gf2log.app.standby.StandbyAutomation
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.text

/**
 * Standby: an all-black, minimum-brightness screen that keeps the phone awake and unlocked.
 * Touching the screen or pressing Back leaves it; so does the screen turning off or the user
 * switching away, so a locked phone is never left in standby. Leaving also cancels any planned
 * recording; [StandbyAutomation] replaces this screen with the game itself when a recording runs.
 *
 * Brightness is a window-level override, so the system brightness and auto-brightness are never
 * touched and come back on their own when this window goes away.
 */
class StandbyActivity : BaseActivity() {

    private var leaving = false

    override fun supportsPendingPlatoonAdmission(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = MIN_BRIGHTNESS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Draw black under the camera cutout too, so no lit strip remains.
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Shown on every entry, so someone who skipped the first-time explanation still learns
        // what this black screen is and how to leave it.
        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(getString(R.string.standby_running), TextRole.TITLE) {
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.standby_hint))
            })
            addView(text(getString(R.string.standby_touch_to_leave), TextRole.SECONDARY) {
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.standby_hint))
                setPadding(0, dp(6), 0, 0)
            })
            nextRecording()?.let { next ->
                addView(text(next, TextRole.SECONDARY) {
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.standby_hint))
                    setPadding(0, dp(12), 0, 0)
                })
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = getString(R.string.standby_touch_to_leave)
            setOnClickListener { leave() }
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { marginStart = dp(32); marginEnd = dp(32) })
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // The hint is the only lit content; removing it leaves every pixel off.
        hint.postDelayed({ hint.visibility = View.INVISIBLE }, HINT_VISIBLE_MS)

        onBackPressedDispatcher.addCallback(this) { leave() }

        // A last guard independent of the automation: never outlive the planned deadline.
        StandbyAutomation.deadline?.let { deadline ->
            val delay = deadline.toEpochMilli() + DEADLINE_MARGIN_MS - System.currentTimeMillis()
            root.postDelayed({ finishForAutomation() }, delay.coerceAtLeast(0))
        }
    }

    override fun onStart() {
        super.onStart()
        StandbyAutomation.onStandbyShown(this)
    }

    override fun onStop() {
        super.onStop()
        // Screen off, lock, or switching apps ends standby; a configuration change does not.
        if (isChangingConfigurations) return
        StandbyAutomation.onStandbyStopped(this)
        leaving = true
        finish()
    }

    /** Closes standby without cancelling the plan, for the automation that owns it. */
    internal fun finishForAutomation() {
        if (leaving) return
        leaving = true
        finish()
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        StandbyAutomation.cancel()
        finish()
    }

    /** "Next recording: HaoPlay South Korea · 04:55", or null when nothing is planned. */
    private fun nextRecording(): String? {
        val next = StandbyAutomation.pendingRuns().firstOrNull() ?: return null
        val time = Labels.format(Labels.TIME, next.startAt, ZoneId.systemDefault())
        return getString(R.string.standby_next_recording, Labels.region(this, next.server.region), time)
    }

    private companion object {
        /** The lowest backlight level; it is not "off", which would let the screen time out. */
        const val MIN_BRIGHTNESS = 0f
        const val HINT_VISIBLE_MS = 3_000L
        const val DEADLINE_MARGIN_MS = 60_000L
    }
}
