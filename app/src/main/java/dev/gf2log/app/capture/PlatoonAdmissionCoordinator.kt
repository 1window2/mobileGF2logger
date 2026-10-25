package dev.gf2log.app.capture

import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonProfileData

/**
 * Buffers a previously unknown Platoon identity in [PendingPlatoonAdmissionStore] and later
 * confirms, replays, or discards it. Runs on the capture worker both during capture and after it
 * stopped, because candidates stay in memory until the user decides or the process dies.
 */
internal class PlatoonAdmissionCoordinator(
    private val profileRegistry: PlatoonProfileRegistry,
    private val clientServerRegions: ClientServerRegionPreferences,
    private val flows: Flows,
) {
    /** The flow state an admission decision acts on; owned by [CaptureFlowEngine]. */
    interface Flows {
        fun isOpen(flowId: Long): Boolean
        fun bind(flowId: Long, profile: PlatoonProfile): PlatoonCaptureSession
        fun unbind(flowId: Long, session: PlatoonCaptureSession)
        fun route(session: PlatoonCaptureSession, payload: ParsedPayload)
        fun quarantine(flowId: Long)
        fun profileChanged(storageId: String)
    }

    private val tokenByFlow = HashMap<Long, String>()

    fun beginSession() = admissionGate.clear()

    fun endSession() {
        tokenByFlow.forEach { (flowId, token) -> PendingPlatoonAdmissionStore.markFlowEnded(token, flowId) }
        tokenByFlow.clear()
    }

    fun flowClosed(flowId: Long) {
        tokenByFlow.remove(flowId)?.let { PendingPlatoonAdmissionStore.markFlowEnded(it, flowId) }
    }

    /**
     * Returns true when [flowId] already feeds a candidate. A changed identity discards that
     * candidate and quarantines the flow instead of re-binding it.
     */
    fun claimsIdentity(flowId: Long, ownerPackage: String, data: PlatoonProfileData): Boolean {
        val token = tokenByFlow[flowId] ?: return false
        val pending = PendingPlatoonAdmissionStore.summary(token)
        if (pending?.ownerPackage != ownerPackage || pending.profile.platoonId != data.platoonId) {
            discard(token)
            flows.quarantine(flowId)
            CaptureStatus.report(CaptureNotice.PENDING_IDENTITY_CHANGED)
        }
        return true
    }

    /** Returns true when [payload] was consumed by the candidate [flowId] belongs to. */
    fun retain(flowId: Long, payload: ParsedPayload): Boolean {
        val token = tokenByFlow[flowId] ?: return false
        offer(token, flowId, payload)
        return true
    }

    fun begin(
        flowId: Long,
        ownerPackage: String,
        data: PlatoonProfileData,
        preIdentityPayloads: List<ParsedPayload>,
    ) {
        val result = PendingPlatoonAdmissionStore.begin(ownerPackage, data, flowId)
        val token = result.token
        if (token == null) {
            result.rejectedFlowIds.forEach(flows::quarantine)
            CaptureStatus.report(CaptureNotice.PENDING_QUEUE_FULL)
            return
        }
        tokenByFlow[flowId] = token
        preIdentityPayloads.forEach { if (!offer(token, flowId, it)) return }
        CaptureStatus.report(CaptureNotice.PENDING_DETECTED)
    }

    fun confirm(token: String, region: GameServerRegion) {
        var admitted: PlatoonProfile? = null
        val completedFlows = PendingPlatoonAdmissionStore.withClaim(token) { claim ->
            when (val outcome = admit(claim, region)) {
                is Outcome.Admitted -> {
                    admitted = outcome.profile
                    null
                }
                is Outcome.Failed -> outcome.notice
            }
        } ?: return
        completedFlows.forEach { tokenByFlow.remove(it, token) }
        val profile = admitted ?: return
        CaptureStatus.report(
            CaptureNotice.PLATOON_CONFIRMED,
            profile.platoonName.take(MAX_STATUS_NAME_CHARS),
            profile.platoonId.toString(),
            profile.client.displayName,
        )
    }

    /** Persists the claimed identity and replays its payloads, reporting any failure. */
    private fun admit(claim: PendingPlatoonAdmissionStore.Claim, region: GameServerRegion): Outcome {
        val ownerPackage = claim.summary.ownerPackage
        if (region !in clientServerRegions.allowed(ownerPackage)) {
            return failed(CaptureNotice.ADMISSION_REGION_MISMATCH)
        }
        val client = requireNotNull(PlatoonClient.fromPackage(ownerPackage))
        val existing = profileRegistry.findByIdentity(
            client,
            region,
            claim.summary.profile.platoonId.toLong(),
        )
        if (!admissionGate.canAdmit(ownerPackage, existing != null)) {
            return failed(CaptureNotice.ADMISSION_LIMIT)
        }
        val previousRegion = clientServerRegions.stored(ownerPackage)
        val profile = runCatching {
            clientServerRegions.set(ownerPackage, region)
            profileRegistry.upsertDetected(ownerPackage, region, claim.summary.profile).also {
                check(profileRegistry.setActive(it.storageId)) {
                    "Unable to select the confirmed Platoon"
                }
            }
        }.getOrElse { error ->
            runCatching {
                if (previousRegion == null) {
                    clientServerRegions.clear(ownerPackage)
                } else {
                    clientServerRegions.set(ownerPackage, previousRegion)
                }
            }.onFailure(error::addSuppressed)
            return failed(CaptureNotice.ADMISSION_PROFILE_FAILED)
        }
        if (existing == null) admissionGate.markAdmitted(ownerPackage)
        flows.profileChanged(profile.storageId)
        if (!replay(claim, profile)) return failed(CaptureNotice.ADMISSION_REPLAY_FAILED)
        return Outcome.Admitted(profile)
    }

    private fun failed(notice: CaptureNotice): Outcome {
        CaptureStatus.report(notice)
        return Outcome.Failed(notice)
    }

    /**
     * Binds every claimed flow to [profile] before any buffered payload is routed, then routes
     * them in their captured order. Binding is the only step that fails the replay, and it fails
     * before anything was persisted, so a retry cannot duplicate history or roster files. Once
     * routing starts the replay completes: each payload reports its own save failures.
     */
    private fun replay(claim: PendingPlatoonAdmissionStore.Claim, profile: PlatoonProfile): Boolean {
        val sessions = linkedMapOf<Long, PlatoonCaptureSession>()
        // Live flows stay bound; an ended flow needs a session only while its payloads replay.
        val flowIds = claim.flowIds.filterNot(claim.endedFlowIds::contains) +
            claim.payloads.map(PendingPlatoonAdmissionStore.BufferedPayload::flowId)
        val bound = runCatching {
            flowIds.forEach { flowId -> sessions.getOrPut(flowId) { flows.bind(flowId, profile) } }
        }
        if (bound.isFailure) {
            sessions.forEach { (flowId, session) -> flows.unbind(flowId, session) }
            return false
        }
        claim.payloads.forEach { buffered ->
            runCatching { flows.route(sessions.getValue(buffered.flowId), buffered.payload) }
                .onFailure { error ->
                    CaptureStatus.report(CaptureNotice.PROCESSING_FAILED, error.javaClass.simpleName)
                }
        }
        claim.endedFlowIds.forEach { flowId ->
            sessions.remove(flowId)?.let { flows.unbind(flowId, it) }
        }
        return true
    }

    fun discard(token: String) {
        PendingPlatoonAdmissionStore.discard(token).forEach { flowId ->
            tokenByFlow.remove(flowId, token)
            if (flows.isOpen(flowId)) flows.quarantine(flowId)
        }
        CaptureStatus.report(CaptureNotice.PENDING_DISCARDED)
    }

    /** Returns false when the flow can no longer contribute to the candidate. */
    private fun offer(token: String, flowId: Long, payload: ParsedPayload): Boolean =
        when (val result = PendingPlatoonAdmissionStore.offer(token, flowId, payload)) {
            PendingPlatoonAdmissionStore.OfferResult.Accepted -> true
            PendingPlatoonAdmissionStore.OfferResult.Claimed -> {
                CaptureStatus.report(CaptureNotice.ADMISSION_IN_PROGRESS)
                true
            }
            PendingPlatoonAdmissionStore.OfferResult.Missing -> {
                tokenByFlow.remove(flowId, token)
                flows.quarantine(flowId)
                false
            }
            is PendingPlatoonAdmissionStore.OfferResult.Overflow -> {
                result.rejectedFlowIds.forEach { rejected ->
                    tokenByFlow.remove(rejected, token)
                    flows.quarantine(rejected)
                }
                CaptureStatus.report(CaptureNotice.PENDING_OVERFLOW)
                false
            }
        }

    private sealed interface Outcome {
        data class Admitted(val profile: PlatoonProfile) : Outcome
        data class Failed(val notice: CaptureNotice) : Outcome
    }

    companion object {
        const val MAX_STATUS_NAME_CHARS = 40

        /**
         * Process-wide like the pending candidates it limits: the service, and with it this
         * coordinator, is recreated after capture stops, while candidates from that capture can
         * still be confirmed. A per-instance gate would reset and admit every one of them.
         */
        private val admissionGate = PlatoonProfilePolicy.AdmissionGate()
    }
}
