package dev.gf2log.app.capture

import android.content.Context
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.PayloadHistoryPreferences
import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.Gfl2StreamParser
import dev.gf2log.protocol.model.ParseEvent
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonProfileData
import dev.gf2log.protocol.model.decodedElementCount
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses each TCP flow, identifies its Platoon from 21905, and routes payloads to that verified
 * scope. Everything except the [nativeListener] callbacks runs on the [CaptureWorker], which
 * therefore owns all of this state without locking.
 */
internal class CaptureFlowEngine(
    private val context: Context,
    private val worker: CaptureWorker,
    private val stats: CaptureTrafficStats,
    private val profileRegistry: PlatoonProfileRegistry,
    private val clientServerRegions: ClientServerRegionPreferences,
    private val payloadHistory: PayloadHistoryPreferences,
) : PlatoonAdmissionCoordinator.Flows {
    /** The native thread taints a flow whose open or chunk was dropped; it stays ignored until closure. */
    private val taintedFlows: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val intake = CaptureFlowIntake(worker, taintedFlows) {
        CaptureStatus.report(CaptureNotice.PARSER_OVERLOADED, stats.recordDropped().toString())
    }
    private val parsers = HashMap<Long, Gfl2StreamParser>()
    private val metadata = HashMap<Long, CaptureFlowMetadata>()
    private val sessions = HashMap<Long, PlatoonCaptureSession>()
    private val preIdentity = BoundedFlowPayloadBuffer<ReceivedPayload>(
        MAX_PRE_IDENTITY_PAYLOADS_PER_FLOW,
        MAX_PRE_IDENTITY_PAYLOADS_TOTAL,
        MAX_PRE_IDENTITY_ELEMENTS_TOTAL,
    ) { it.payload.data.decodedElementCount() }
    /** Flows that already reported losing pre-identity history, so each reports it once. */
    private val historyDroppedFlows = HashSet<Long>()
    private val historyStores = HashMap<String, CaptureHistoryStore>()
    private val observedProfiles = HashMap<String, Pair<ObservedIdentity, PlatoonProfile>>()
    private var ownerResolver = CaptureFlowOwnerResolver(context)
    private var tracker: CaptureRunTracker? = null

    val admission = PlatoonAdmissionCoordinator(profileRegistry, clientServerRegions, this)

    fun beginSession(tracker: CaptureRunTracker) {
        this.tracker = tracker
        ownerResolver = CaptureFlowOwnerResolver(context)
        admission.beginSession()
    }

    /** Discards per-flow state after the native thread has been joined and its chunks drained. */
    fun endSession() {
        admission.endSession()
        sessions.clear()
        parsers.clear()
        metadata.clear()
        taintedFlows.clear()
        preIdentity.clear()
        historyDroppedFlows.clear()
        observedProfiles.clear()
        tracker = null
    }

    fun nativeListener(onStopped: () -> Unit) = object : NativeCaptureBridge.PayloadListener {
        override fun onFlowOpened(
            flowId: Long,
            protocol: Int,
            localAddress: String,
            localPort: Int,
            remoteAddress: String,
            remotePort: Int,
        ) {
            intake.opened(flowId) { open(flowId, protocol, localAddress, localPort, remoteAddress, remotePort) }
        }

        override fun onPayload(flowId: Long, payload: ByteArray) {
            if (payload.isEmpty()) return
            // Stamped on arrival, so a backlog or a pending admission never shifts when it was captured.
            val receivedAt = Instant.now()
            intake.payload(flowId) { accept(flowId, payload, receivedAt) }
        }

        override fun onFlowClosed(flowId: Long) {
            intake.closed(flowId) { close(flowId) }
        }

        override fun onTraffic(sentBytes: Long, receivedBytes: Long, inspectedBytes: Long) {
            stats.recordTraffic(sentBytes + receivedBytes, inspectedBytes)?.let { args ->
                CaptureStatus.report(CaptureNotice.TRAFFIC, *args.toTypedArray())
            }
        }

        override fun onCaptureStopped() = onStopped()
    }

    override fun isOpen(flowId: Long): Boolean = flowId in metadata || flowId in parsers

    override fun bind(flowId: Long, profile: PlatoonProfile): PlatoonCaptureSession =
        PlatoonCaptureSession(
            context = context,
            profile = profile,
            onRosterCaptured = { storageId ->
                tracker?.rosterCaptured(storageId)
                RosterEvents.persisted(storageId)
            },
        ).also { sessions[flowId] = it }

    override fun unbind(flowId: Long, session: PlatoonCaptureSession) {
        sessions.remove(flowId, session)
    }

    /** Permanently blocks management routing for this flow until native closure. */
    override fun quarantine(flowId: Long) {
        taintedFlows += flowId
        sessions.remove(flowId)
        preIdentity.reject(flowId)
    }

    override fun profileChanged(storageId: String) {
        observedProfiles.remove(storageId)
    }

    override fun route(session: PlatoonCaptureSession, payload: ParsedPayload, receivedAt: Instant) {
        val storageId = session.profile.storageId
        saveHistory(storageId, payload)
        val routed = session.dispatch(payload, receivedAt)
        val reportFailure = { failure: Throwable, fallback: CaptureNotice ->
            val (notice, args) = PlatoonCaptureSession.saveFailureNotice(failure, session.profile.platoonName, fallback)
            CaptureStatus.report(notice, *args.toTypedArray())
        }
        routed.activity
            ?.onSuccess { if (it) tracker?.mark(storageId, Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY) }
            ?.onFailure { reportFailure(it, CaptureNotice.ACTIVITY_SAVE_FAILED) }
        routed.updates
            ?.onSuccess { if (it) tracker?.mark(storageId, Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES) }
            ?.onFailure { reportFailure(it, CaptureNotice.UPDATES_SAVE_FAILED) }
        routed.members
            ?.onSuccess { saved ->
                if (saved != null) {
                    CaptureStatus.report(
                        CaptureNotice.ROSTER_SAVED,
                        saved.rowCount.toString(),
                        session.profile.platoonName,
                    )
                }
            }
            ?.onFailure { reportFailure(it, CaptureNotice.ROSTER_SAVE_FAILED) }
        if (payload.payloadType == Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE) {
            tracker?.mark(storageId, Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE)
        }
    }

    private fun open(
        flowId: Long,
        protocol: Int,
        localAddress: String,
        localPort: Int,
        remoteAddress: String,
        remotePort: Int,
    ) {
        if (flowId in taintedFlows) return
        metadata[flowId] = CaptureFlowMetadata(
            protocol = protocol,
            localAddress = localAddress,
            localPort = localPort,
            remoteAddress = remoteAddress,
            remotePort = remotePort,
            ownerPackage = ownerResolver.resolve(
                protocol,
                localAddress,
                localPort,
                remoteAddress,
                remotePort,
            ),
        )
    }

    private fun accept(flowId: Long, payload: ByteArray, receivedAt: Instant) {
        if (flowId in taintedFlows) return
        process(flowId, parsers.getOrPut(flowId, ::Gfl2StreamParser).accept(payload), receivedAt)
    }

    private fun close(flowId: Long) {
        val parser = parsers.remove(flowId)
        if (!taintedFlows.remove(flowId) && parser != null) {
            process(flowId, parser.finish(), Instant.now())
        }
        metadata.remove(flowId)
        admission.flowClosed(flowId)
        preIdentity.remove(flowId)
        historyDroppedFlows.remove(flowId)
        sessions.remove(flowId)
    }

    private fun process(flowId: Long, events: List<ParseEvent>, receivedAt: Instant) {
        if (events.isEmpty()) return
        val payloads = mutableListOf<ParsedPayload>()
        var warnings = 0
        events.forEach { event ->
            when (event) {
                is ParseEvent.Payload -> payloads += event.value
                is ParseEvent.Warning -> warnings += 1
                is ParseEvent.UnknownPayload -> stats.recordUnknown(event.payloadType)
            }
        }
        stats.recordParsed(payloads.size, warnings)

        payloads.forEach { payload ->
            val profile = payload.data as? PlatoonProfileData
            if (profile != null && PlatoonProfilePolicy.isValid(profile)) identify(flowId, profile)
            val session = sessions[flowId]
            if (session == null) {
                holdUntilIdentified(flowId, ReceivedPayload(payload, receivedAt))
            } else {
                route(session, payload, receivedAt)
            }
        }
    }

    private fun identify(flowId: Long, data: PlatoonProfileData) {
        if (preIdentity.isRejected(flowId)) {
            quarantine(flowId)
            CaptureStatus.report(CaptureNotice.IDENTIFIED_FLOW_BUFFER_OVERFLOW)
            return
        }
        val ownerPackage = metadata[flowId]?.ownerPackage
        val client = PlatoonClient.fromPackage(ownerPackage)
        if (ownerPackage == null || ownerPackage !in SupportedGamePackages.all || client == null) {
            quarantine(flowId)
            CaptureStatus.report(CaptureNotice.CLIENT_UNVERIFIED)
            return
        }
        if (admission.claimsIdentity(flowId, ownerPackage, data)) return

        val known = findKnownProfile(client, ownerPackage, data)
        val current = sessions[flowId]
        if (PlatoonProfilePolicy.changesBoundIdentity(current?.profile?.storageId, known?.storageId)) {
            quarantine(flowId)
            CaptureStatus.report(CaptureNotice.IDENTITY_CHANGED)
            return
        }
        if (known == null) {
            admission.begin(flowId, ownerPackage, data, preIdentity.take(flowId))
            return
        }
        val profile = observe(known.storageId, data)
        if (profile == null) {
            quarantine(flowId)
            CaptureStatus.report(CaptureNotice.PROFILE_UPDATE_FAILED)
            return
        }
        // An already bound flow only refreshes its profile; the caller routes this 21905 itself.
        if (current != null) return

        val session = bind(flowId, profile)
        preIdentity.take(flowId).forEach { pending -> route(session, pending.payload, pending.receivedAt) }
        CaptureStatus.report(
            CaptureNotice.PLATOON_DETECTED,
            profile.platoonName.take(PlatoonAdmissionCoordinator.MAX_STATUS_NAME_CHARS),
            profile.platoonId.toString(),
            profile.client.displayName,
        )
        if (historyDroppedFlows.remove(flowId)) CaptureStatus.report(CaptureNotice.HISTORY_SAVE_FAILED)
    }

    private fun findKnownProfile(
        client: PlatoonClient,
        ownerPackage: String,
        data: PlatoonProfileData,
    ): PlatoonProfile? {
        val known = profileRegistry.findByClientAndPlatoonId(client, data.platoonId.toLong())
        return when {
            known.size == 1 -> known.single()
            known.size > 1 -> {
                val configuredRegion = clientServerRegions.configured(ownerPackage)
                known.singleOrNull { it.serverRegion == configuredRegion }
            }
            else -> null
        }
    }

    /** Persists an observed name or banner once per session, and again only when it changes. */
    private fun observe(storageId: String, data: PlatoonProfileData): PlatoonProfile? {
        val identity = ObservedIdentity(data.platoonName, data.bannerFrameId, data.bannerMarkId)
        observedProfiles[storageId]?.let { (observed, profile) -> if (observed == identity) return profile }
        val profile = runCatching { profileRegistry.updateObserved(storageId, data) }.getOrNull()
            ?: return null
        observedProfiles[storageId] = identity to profile
        return profile
    }

    private fun holdUntilIdentified(flowId: Long, received: ReceivedPayload) {
        if (admission.retain(flowId, received)) return
        val type = received.payload.payloadType
        when (PreIdentityPolicy.retention(type, payloadHistory.isEnabled(type))) {
            PreIdentityPolicy.Retention.REQUIRED ->
                if (preIdentity.offer(flowId, received) == BoundedFlowPayloadBuffer.OfferResult.OVERFLOW) {
                    CaptureStatus.report(CaptureNotice.FLOW_BUFFER_OVERFLOW)
                }
            // Reported only if the flow is identified: a flow of no Platoon had no history to lose.
            PreIdentityPolicy.Retention.HISTORY_ONLY ->
                if (preIdentity.offerOptional(flowId, received) == BoundedFlowPayloadBuffer.OfferResult.DROPPED) {
                    historyDroppedFlows += flowId
                }
            PreIdentityPolicy.Retention.NONE -> Unit
        }
    }

    private fun saveHistory(storageId: String, payload: ParsedPayload) {
        if (!payloadHistory.isEnabled(payload.payloadType)) return
        val store = historyStores.getOrPut(storageId) {
            CaptureHistoryStore(
                File(
                    PlatoonStorageScope(storageId).rootDirectory(context),
                    CaptureHistoryStore.HISTORY_DIRECTORY,
                ),
            )
        }
        runCatching { store.save(payload) }
            .onFailure { CaptureStatus.report(CaptureNotice.HISTORY_SAVE_FAILED) }
    }

    private data class ObservedIdentity(val name: String, val bannerFrameId: UInt, val bannerMarkId: UInt)

    private companion object {
        const val MAX_PRE_IDENTITY_PAYLOADS_PER_FLOW = 32
        const val MAX_PRE_IDENTITY_PAYLOADS_TOTAL = 128
        const val MAX_PRE_IDENTITY_ELEMENTS_TOTAL = 65_536L
    }
}

/**
 * The native thread's side of [CaptureFlowEngine]: queues each flow callback on the worker.
 * Opens and chunks share the worker's chunk budget, and a flow that loses either is tainted: a
 * lost open leaves it without a verified owner, a lost chunk breaks its byte offsets. A close
 * bypasses the budget, so it always releases the flow's state, after the flow's queued chunks.
 */
internal class CaptureFlowIntake(
    private val worker: CaptureWorker,
    private val taintedFlows: MutableSet<Long>,
    private val onChunkDropped: () -> Unit,
) {
    fun opened(flowId: Long, open: () -> Unit) {
        if (!submit(open)) taintedFlows += flowId
    }

    fun payload(flowId: Long, accept: () -> Unit) {
        if (flowId in taintedFlows) return
        if (!submit(accept)) taintedFlows += flowId
    }

    /** Only a worker that has shut down refuses it; the taint then keeps the flow inert. */
    fun closed(flowId: Long, close: () -> Unit) {
        if (!worker.execute(close)) taintedFlows += flowId
    }

    private fun submit(task: () -> Unit): Boolean =
        worker.submitChunk(task).also { queued -> if (!queued) onChunkDropped() }
}

/** Which payloads of a flow that has no identity yet are worth holding until it has one. */
internal object PreIdentityPolicy {
    enum class Retention {
        /** Platoon data that is lost unless held; failing to hold it rejects the flow. */
        REQUIRED,

        /** Kept for parsed-packet history only while there is room; never rejects the flow. */
        HISTORY_ONLY,

        /** Nothing would use it after identification. */
        NONE,
    }

    private val PLATOON_DATA_TYPES = setOf(
        Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
        Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
        Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
    )

    fun retention(payloadType: Int, historyEnabled: Boolean): Retention = when {
        payloadType in PLATOON_DATA_TYPES -> Retention.REQUIRED
        // A 21905 that reaches the buffer could not identify the flow, so it names no Platoon.
        payloadType == Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE -> Retention.NONE
        historyEnabled -> Retention.HISTORY_ONLY
        else -> Retention.NONE
    }
}
