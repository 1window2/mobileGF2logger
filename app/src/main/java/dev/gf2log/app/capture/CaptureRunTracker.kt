package dev.gf2log.app.capture

import dev.gf2log.app.R
import dev.gf2log.protocol.Gfl2PayloadDecoder

/**
 * One capture run's required-payload checklist. Marks arrive on the capture worker; a one-time
 * capture reports completion and the target roster through callbacks that must hop to main.
 */
internal class CaptureRunTracker(
    val captureOnce: Boolean,
    private val onComplete: (CaptureRunTracker) -> Unit = {},
    private val onTargetRosterCaptured: (CaptureRunTracker) -> Unit = {},
) {
    private val checklist = ScopedCaptureChecklist(REQUIRED_TYPES)

    fun mark(storageId: String, payloadType: Int) {
        checklist.mark(storageId, payloadType, chooseTarget = captureOnce)
        if (!captureOnce || checklist.targetScopeId() == storageId) {
            CaptureStatus.markUsefulPayload(payloadType)
        }
        if (captureOnce && checklist.targetComplete()) onComplete(this)
    }

    fun rosterCaptured(storageId: String) {
        mark(storageId, Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS)
        if (captureOnce && checklist.targetScopeId() == storageId) onTargetRosterCaptured(this)
    }

    /** Payload types the target scope still lacks, or null until that scope's roster was captured. */
    fun missingAfterRoster(): List<Int>? {
        val captured = checklist.targetCaptured()
        if (Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS !in captured) return null
        return CHECKLIST_LABELS.keys.filterNot(captured::contains)
    }

    /**
     * The stop notice and its args once the grace period after the target roster ends, or null
     * before that roster. A checklist completed right at the deadline still reads as complete.
     */
    fun graceOutcome(): Pair<CaptureNotice, List<String>>? {
        val missing = missingAfterRoster() ?: return null
        return if (missing.isEmpty()) {
            CaptureNotice.CAPTURE_ONCE_COMPLETE to emptyList()
        } else {
            CaptureNotice.CAPTURE_ONCE_PARTIAL to missing.map(Int::toString)
        }
    }

    companion object {
        /** Required payload types in checklist order, each with the label Home shows for it. */
        val CHECKLIST_LABELS: Map<Int, Int> = linkedMapOf(
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE to R.string.checklist_identity,
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS to R.string.checklist_roster,
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY to R.string.checklist_activity,
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES to R.string.checklist_updates,
        )

        val REQUIRED_TYPES: Set<Int> = CHECKLIST_LABELS.keys
    }
}
