package dev.gf2log.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import dev.gf2log.app.R
import dev.gf2log.app.capture.CapturePhase
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.CaptureVpnService

/** True from the moment capture is requested until its teardown finishes; risky data operations wait. */
internal val captureActive: Boolean
    get() = CaptureStatus.snapshot().phase != CapturePhase.STOPPED

/**
 * Data operations that must not interleave with capture ask once and stop it in place, instead of
 * sending the user back to Home to find the Stop button.
 *
 * Asking happens before any follow-up dialog or file picker, so a capture that standby or an alarm
 * starts meanwhile is caught again where the operation runs: [whileStopped] runs it only while
 * capture is stopped, and [beginCaptureStart] refuses a start until it has finished.
 */
internal object CaptureGuard {
    /** Teardown joins the native loop and drains queued chunks; anything longer means it is stuck. */
    private const val STOP_TIMEOUT_MS = 20_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Orders a maintenance check against a capture start, so one of them always sees the other. */
    private val lock = Any()

    /** Operations inside [whileStopped] right now; guarded by [lock]. */
    private var maintenance = 0

    /** Thrown by callers whose operation [whileStopped] refused because capture was active. */
    class CaptureActiveException : IllegalStateException("Capture became active before the operation ran")

    /**
     * Runs [operation] on the calling thread if capture is STOPPED at this moment, and keeps every
     * capture start out until it returns. Null, without running it, when capture is active.
     */
    fun <T> whileStopped(operation: () -> T): T? {
        synchronized(lock) {
            if (CaptureStatus.snapshot().phase != CapturePhase.STOPPED) return null
            maintenance++
        }
        try {
            return operation()
        } finally {
            synchronized(lock) { maintenance-- }
        }
    }

    /**
     * Call right before asking the capture service to start: publishes STARTING (when capture is
     * stopped) so no maintenance begins after it, or returns false, and the start must not be
     * sent, while one is running.
     */
    fun beginCaptureStart(): Boolean = synchronized(lock) {
        if (maintenance > 0) return false
        if (CaptureStatus.snapshot().phase == CapturePhase.STOPPED) CaptureStatus.markStarting()
        true
    }

    /** A start [beginCaptureStart] allowed never reached the service: back to STOPPED. */
    fun abandonCaptureStart() = synchronized(lock) {
        if (CaptureStatus.snapshot().phase == CapturePhase.STARTING) CaptureStatus.markStopped()
    }

    /** Requests a stop; the service publishes STOPPED only after its teardown has finished. */
    fun stop(context: Context) {
        context.startService(Intent(context, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
    }

    /** Runs [action] now, or after the user agrees to stop the running capture; [reason] says why. */
    fun whenStopped(activity: Activity, @StringRes reason: Int, action: () -> Unit) {
        when (CaptureStatus.snapshot().phase) {
            CapturePhase.STOPPED -> action()
            // Already stopping: nothing to ask, only the end of its teardown to wait for.
            CapturePhase.STOPPING -> afterTeardown(activity, action)
            CapturePhase.STARTING, CapturePhase.RUNNING ->
                Dialogs.confirm(activity, R.string.capture_running_title, reason, R.string.stop_capture_and_continue) {
                    stop(activity)
                    afterTeardown(activity, action)
                }
        }
    }

    /** Continues with [action] once capture is STOPPED, or explains why it could not within the timeout. */
    private fun afterTeardown(activity: Activity, action: () -> Unit) {
        var settled = false
        var cancelWait: (() -> Unit)? = null
        val timeout = Runnable {
            if (settled) return@Runnable
            settled = true
            cancelWait?.invoke()
            if (!activity.isFinishing && !activity.isDestroyed) {
                Dialogs.message(
                    activity,
                    activity.getString(R.string.capture_stop_timeout_title),
                    activity.getString(R.string.capture_stop_timeout_message),
                )
            }
        }
        // Posted first, so a wait that is already satisfied below can withdraw it.
        handler.postDelayed(timeout, STOP_TIMEOUT_MS)
        cancelWait = CaptureStatus.whenStopped {
            if (settled) return@whenStopped
            settled = true
            handler.removeCallbacks(timeout)
            // A screen that left meanwhile no longer owns the operation it asked for.
            if (!activity.isFinishing && !activity.isDestroyed) action()
        }
    }
}
