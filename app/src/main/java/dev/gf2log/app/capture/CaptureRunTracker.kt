package dev.gf2log.app.capture

import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.PayloadCatalog

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

    /** Tags the target scope still lacks, or null until that scope's roster was captured. */
    fun missingAfterRoster(): List<String>? {
        val captured = checklist.targetCaptured()
        if (Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS !in captured) return null
        return REQUIRED_TYPES.minus(captured).map(PayloadCatalog::tag)
    }

    companion object {
        val REQUIRED_TYPES = setOf(
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
        )
    }
}
