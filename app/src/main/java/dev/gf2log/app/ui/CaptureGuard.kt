package dev.gf2log.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import dev.gf2log.app.R
import dev.gf2log.app.capture.CapturePhase
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.CaptureVpnService

/** True from the moment capture is requested until it stops; risky data operations wait. */
internal val captureActive: Boolean
    get() = CaptureStatus.snapshot().phase != CapturePhase.STOPPED

/**
 * Data operations that must not interleave with capture ask once and stop it in place, instead of
 * sending the user back to Home to find the Stop button.
 */
internal object CaptureGuard {
    fun stop(context: Context) {
        context.startService(Intent(context, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
        CaptureStatus.markStopped()
    }

    /** Runs [action] now, or after the user agrees to stop the running capture; [reason] says why. */
    fun whenStopped(activity: Activity, @StringRes reason: Int, action: () -> Unit) {
        if (!captureActive) {
            action()
            return
        }
        Dialogs.confirm(activity, R.string.capture_running_title, reason, R.string.stop_capture_and_continue) {
            stop(activity)
            action()
        }
    }
}
