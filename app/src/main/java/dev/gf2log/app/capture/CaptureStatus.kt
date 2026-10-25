package dev.gf2log.app.capture

import android.content.Context
import androidx.annotation.StringRes
import dev.gf2log.app.R
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [STOPPING] lasts from a stop request until the native loop is joined, queued chunks are drained,
 * and the session is cleared; only then is capture [STOPPED] and data operations may begin.
 */
enum class CapturePhase { STOPPED, STARTING, RUNNING, STOPPING }

/** Every user-visible capture status message, localized through [messageRes]. */
enum class CaptureNotice(@StringRes val messageRes: Int, val severity: Severity) {
    PREPARING(R.string.capture_notice_preparing, Severity.INFO),
    ALREADY_RUNNING(R.string.capture_notice_already_running, Severity.INFO),
    /** Args: comma-separated captured package IDs. */
    CAPTURING(R.string.capture_notice_capturing, Severity.INFO),
    NATIVE_UNAVAILABLE(R.string.capture_notice_native_unavailable, Severity.ERROR),
    HISTORY_IMPORT_FAILED(R.string.capture_notice_history_import_failed, Severity.ERROR),
    NO_SUPPORTED_CLIENT(R.string.capture_notice_no_supported_client, Severity.ERROR),
    /** Args: failure reason. */
    VPN_ESTABLISH_FAILED(R.string.capture_notice_vpn_establish_failed, Severity.ERROR),
    VPN_PERMISSION_REVOKED(R.string.capture_notice_vpn_permission_revoked, Severity.ERROR),
    NATIVE_START_FAILED(R.string.capture_notice_native_start_failed, Severity.ERROR),
    STOPPED_UNEXPECTEDLY(R.string.capture_notice_stopped_unexpectedly, Severity.ERROR),
    CAPTURE_ONCE_COMPLETE(R.string.capture_notice_capture_once_complete, Severity.INFO),
    /** Args: comma-separated missing payload tags. */
    CAPTURE_ONCE_PARTIAL(R.string.capture_notice_capture_once_partial, Severity.WARNING),
    /** Args: forwarded KiB, inspected KiB, decoded payloads, warnings, dropped chunks. */
    TRAFFIC(R.string.capture_notice_traffic, Severity.INFO),
    /** Args: exception class name. */
    PROCESSING_FAILED(R.string.capture_notice_processing_failed, Severity.ERROR),
    /** Args: total dropped chunks. */
    PARSER_OVERLOADED(R.string.capture_notice_parser_overloaded, Severity.WARNING),
    FLOW_BUFFER_OVERFLOW(R.string.capture_notice_flow_buffer_overflow, Severity.WARNING),
    IDENTIFIED_FLOW_BUFFER_OVERFLOW(
        R.string.capture_notice_identified_flow_buffer_overflow,
        Severity.WARNING,
    ),
    CLIENT_UNVERIFIED(R.string.capture_notice_client_unverified, Severity.WARNING),
    IDENTITY_CHANGED(R.string.capture_notice_identity_changed, Severity.WARNING),
    PENDING_IDENTITY_CHANGED(R.string.capture_notice_pending_identity_changed, Severity.WARNING),
    PROFILE_UPDATE_FAILED(R.string.capture_notice_profile_update_failed, Severity.ERROR),
    /** Args: Platoon name, Platoon ID, client name. */
    PLATOON_DETECTED(R.string.capture_notice_platoon_detected, Severity.INFO),
    PENDING_DETECTED(R.string.capture_notice_pending_detected, Severity.INFO),
    PENDING_QUEUE_FULL(R.string.capture_notice_pending_queue_full, Severity.WARNING),
    PENDING_OVERFLOW(R.string.capture_notice_pending_overflow, Severity.WARNING),
    ADMISSION_IN_PROGRESS(R.string.capture_notice_admission_in_progress, Severity.INFO),
    ADMISSION_INVALID(R.string.capture_notice_admission_invalid, Severity.ERROR),
    ADMISSION_REGION_MISMATCH(R.string.capture_notice_admission_region_mismatch, Severity.ERROR),
    ADMISSION_LIMIT(R.string.capture_notice_admission_limit, Severity.WARNING),
    ADMISSION_PROFILE_FAILED(R.string.capture_notice_admission_profile_failed, Severity.ERROR),
    ADMISSION_REPLAY_FAILED(R.string.capture_notice_admission_replay_failed, Severity.ERROR),
    /** Args: Platoon name, Platoon ID, client name. */
    PLATOON_CONFIRMED(R.string.capture_notice_platoon_confirmed, Severity.INFO),
    PENDING_DISCARDED(R.string.capture_notice_pending_discarded, Severity.INFO),
    /** Args: member count, Platoon name. */
    ROSTER_SAVED(R.string.capture_notice_roster_saved, Severity.INFO),
    ROSTER_SAVE_FAILED(R.string.capture_notice_roster_save_failed, Severity.ERROR),
    /** Args: Platoon name, joined count, left count. */
    ROSTER_UPDATED(R.string.capture_notice_roster_updated, Severity.INFO),
    /** Args: Platoon name. */
    ROSTER_RECOVERED(R.string.capture_notice_roster_recovered, Severity.WARNING),
    ACTIVITY_SAVE_FAILED(R.string.capture_notice_activity_save_failed, Severity.ERROR),
    UPDATES_SAVE_FAILED(R.string.capture_notice_updates_save_failed, Severity.ERROR),
    HISTORY_SAVE_FAILED(R.string.capture_notice_history_save_failed, Severity.ERROR),
    ;

    enum class Severity { INFO, WARNING, ERROR }
}

data class CaptureNoticeEvent(
    val notice: CaptureNotice,
    val args: List<String> = emptyList(),
    val atMillis: Long,
)

fun CaptureNoticeEvent.format(context: Context): String =
    context.getString(notice.messageRes, *args.toTypedArray())

data class CaptureSnapshot(
    val phase: CapturePhase,
    val notice: CaptureNoticeEvent?,
    val capturedTargets: List<String>,
    val guided: CaptureStatus.GuidedProgress?,
    /** Increases each time capture reaches [CapturePhase.RUNNING], so a new run is distinguishable. */
    val runCount: Long = 0,
)

/** Process-wide capture state, swapped atomically as one immutable [CaptureSnapshot]. */
object CaptureStatus {
    private val state = AtomicReference(
        CaptureSnapshot(CapturePhase.STOPPED, notice = null, capturedTargets = emptyList(), guided = null),
    )
    private val listeners = CopyOnWriteArraySet<(CaptureSnapshot) -> Unit>()
    private val dispatchPending = AtomicBoolean(false)

    fun snapshot(): CaptureSnapshot = state.get()

    val isRunning: Boolean
        get() = snapshot().phase == CapturePhase.RUNNING

    /** [listener] runs on the main looper: once immediately, then after every change. */
    fun addListener(listener: (CaptureSnapshot) -> Unit) {
        listeners += listener
        MainThread.runOrPost { if (listener in listeners) listener(snapshot()) }
    }

    fun removeListener(listener: (CaptureSnapshot) -> Unit) {
        listeners -= listener
    }

    fun markStarting() = update {
        it.copy(phase = CapturePhase.STARTING, notice = event(CaptureNotice.PREPARING))
    }

    /** A start abandoned before it reached the service only clears a stale notice. */
    fun clearIdleNotice() = update {
        if (it.phase != CapturePhase.STOPPED) it else it.copy(notice = null, capturedTargets = emptyList())
    }

    /** Publishes STOPPED directly; only the service calls this, and only with no teardown pending. */
    internal fun markStopped(notice: CaptureNotice? = null, vararg args: String) = update {
        it.copy(
            phase = CapturePhase.STOPPED,
            notice = notice?.let { value -> event(value, args) },
            capturedTargets = emptyList(),
        )
    }

    internal fun markStopping(notice: CaptureNotice?, vararg args: String) = update {
        it.copy(
            phase = CapturePhase.STOPPING,
            notice = notice?.let { value -> event(value, args) },
            capturedTargets = emptyList(),
        )
    }

    /**
     * Publishes STOPPED once teardown has finished, keeping the stop notice. A phase that a newer
     * start already owns is left alone.
     */
    internal fun markTornDown() = update {
        if (it.phase != CapturePhase.STOPPING) it else it.copy(phase = CapturePhase.STOPPED)
    }

    internal fun markRunning(targets: List<String>) = update {
        it.copy(
            phase = CapturePhase.RUNNING,
            notice = event(CaptureNotice.CAPTURING, arrayOf(targets.joinToString())),
            capturedTargets = targets.toList(),
            runCount = it.runCount + 1,
        )
    }

    /**
     * Runs [action] once on the main looper as soon as capture is [CapturePhase.STOPPED], which may
     * be immediately. Returns a function that cancels the wait; cancelling after it ran is harmless.
     */
    fun whenStopped(action: () -> Unit): () -> Unit {
        val done = AtomicBoolean(false)
        lateinit var listener: (CaptureSnapshot) -> Unit
        listener = { snapshot ->
            if (snapshot.phase == CapturePhase.STOPPED && done.compareAndSet(false, true)) {
                removeListener(listener)
                action()
            }
        }
        addListener(listener)
        return {
            done.set(true)
            removeListener(listener)
        }
    }

    internal fun report(notice: CaptureNotice, vararg args: String) = update {
        it.copy(notice = event(notice, args))
    }

    internal fun beginSession(guided: Boolean) = update {
        it.copy(guided = GuidedProgress().takeIf { guided })
    }

    internal fun markUsefulPayload(payloadType: Int) = update { current ->
        val guided = current.guided ?: return@update current
        if (payloadType in guided.capturedPayloadTypes) return@update current
        current.copy(guided = guided.copy(capturedPayloadTypes = guided.capturedPayloadTypes + payloadType))
    }

    private inline fun update(transform: (CaptureSnapshot) -> CaptureSnapshot) {
        while (true) {
            val previous = state.get()
            val next = transform(previous)
            if (next === previous) return
            if (state.compareAndSet(previous, next)) break
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        if (listeners.isEmpty() || !dispatchPending.compareAndSet(false, true)) return
        MainThread.post {
            dispatchPending.set(false)
            val current = snapshot()
            listeners.forEach { it(current) }
        }
    }

    private fun event(notice: CaptureNotice, args: Array<out String> = emptyArray()) =
        CaptureNoticeEvent(notice, args.toList(), System.currentTimeMillis())

    data class GuidedProgress(
        val capturedPayloadTypes: Set<Int> = emptySet(),
    )
}
