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
import java.io.File
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
    /** The native thread taints a flow whose chunk was dropped; it stays ignored until closure. */
    private val taintedFlows: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val parsers = HashMap<Long, Gfl2StreamParser>()
    private val metadata = HashMap<Long, CaptureFlowMetadata>()
    private val sessions = HashMap<Long, PlatoonCaptureSession>()
    private val preIdentity = BoundedFlowPayloadBuffer<ParsedPayload>(
        MAX_PRE_IDENTITY_PAYLOADS_PER_FLOW,
        MAX_PRE_IDENTITY_PAYLOADS_TOTAL,
    )
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
        sessions.values.forEach { runCatching(it::close) }
        sessions.clear()
        parsers.clear()
        metadata.clear()
        taintedFlows.clear()
        preIdentity.clear()
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
            submit { open(flowId, protocol, localAddress, localPort, remoteAddress, remotePort) }
        }

        override fun onPayload(flowId: Long, payload: ByteArray) {
            if (payload.isEmpty() || flowId in taintedFlows) return
            // A missing TCP chunk makes every later byte offset unreliable.
            if (!submit { accept(flowId, payload) }) taintedFlows += flowId
        }

        override fun onFlowClosed(flowId: Long) {
            // Earlier chunks may still be queued; the taint keeps them inert until session end.
            if (!submit { close(flowId) }) taintedFlows += flowId
        }

        override fun onTraffic(sentBytes: Long, receivedBytes: Long, inspectedBytes: Long) {
            stats.recordTraffic(sentBytes + receivedBytes, inspectedBytes)?.let { args ->
                CaptureStatus.report(CaptureNotice.TRAFFIC, *args.toTypedArray())
            }
        }

        override fun onCaptureStopped() = onStopped()
    }

    override fun isOpen(flowId: Long): Boolean = flowId in metadata || flowId in parsers

    override fun bind(flowId: Long, profile: PlatoonProfile): PlatoonCaptureSession {
        sessions.remove(flowId)?.close()
        return PlatoonCaptureSession(
            context = context,
            profile = profile,
            onRosterCaptured = { storageId -> tracker?.rosterCaptured(storageId) },
        ).also { sessions[flowId] = it }
    }

    override fun unbind(flowId: Long, session: PlatoonCaptureSession) {
        sessions.remove(flowId, session)
        runCatching(session::close)
    }

    /** Permanently blocks management routing for this flow until native closure. */
    override fun quarantine(flowId: Long) {
        taintedFlows += flowId
        sessions.remove(flowId)?.close()
        preIdentity.reject(flowId)
    }

    override fun profileChanged(storageId: String) {
        observedProfiles.remove(storageId)
    }

    override fun route(session: PlatoonCaptureSession, payload: ParsedPayload, flowEnded: Boolean) {
        val storageId = session.profile.storageId
        saveHistory(storageId, payload)
        val routed = session.dispatch(payload, flowEnded)
        routed.activity
            ?.onSuccess { if (it) tracker?.mark(storageId, Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY) }
            ?.onFailure { CaptureStatus.report(CaptureNotice.ACTIVITY_SAVE_FAILED) }
        routed.updates
            ?.onSuccess { if (it) tracker?.mark(storageId, Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES) }
            ?.onFailure { CaptureStatus.report(CaptureNotice.UPDATES_SAVE_FAILED) }
        routed.members
            .onSuccess { saved ->
                if (saved != null) {
                    CaptureStatus.report(
                        CaptureNotice.ROSTER_SAVED,
                        saved.rowCount.toString(),
                        session.profile.platoonName,
                    )
                }
            }
            .onFailure { CaptureStatus.report(CaptureNotice.ROSTER_SAVE_FAILED) }
        if (payload.payloadType == Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE) {
            tracker?.mark(storageId, Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE)
        }
    }

    private fun submit(task: () -> Unit): Boolean = worker.submitChunk(task).also { queued ->
        if (!queued) {
            CaptureStatus.report(CaptureNotice.PARSER_OVERLOADED, stats.recordDropped().toString())
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

    private fun accept(flowId: Long, payload: ByteArray) {
        if (flowId in taintedFlows) return
        process(flowId, parsers.getOrPut(flowId, ::Gfl2StreamParser).accept(payload))
    }

    private fun close(flowId: Long) {
        val parser = parsers.remove(flowId)
        if (!taintedFlows.remove(flowId) && parser != null) {
            process(flowId, parser.finish(), flowEnded = true)
        }
        metadata.remove(flowId)
        admission.flowClosed(flowId)
        preIdentity.remove(flowId)
        sessions.remove(flowId)?.close()
    }

    private fun process(flowId: Long, events: List<ParseEvent>, flowEnded: Boolean = false) {
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

        payloads.forEachIndexed { index, payload ->
            val profile = payload.data as? PlatoonProfileData
            if (profile != null && PlatoonProfilePolicy.isValid(profile)) identify(flowId, profile)
            val session = sessions[flowId]
            if (session == null) {
                holdUntilIdentified(flowId, payload)
            } else {
                route(session, payload, flowEnded = flowEnded && index == payloads.lastIndex)
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
        if (known == null) {
            admission.begin(flowId, ownerPackage, data, preIdentity.take(flowId))
            return
        }
        val current = sessions[flowId]
        if (current != null && current.profile.storageId != known.storageId) {
            quarantine(flowId)
            CaptureStatus.report(CaptureNotice.IDENTITY_CHANGED)
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
        preIdentity.take(flowId).forEach { pending -> route(session, pending, flowEnded = false) }
        CaptureStatus.report(
            CaptureNotice.PLATOON_DETECTED,
            profile.platoonName.take(PlatoonAdmissionCoordinator.MAX_STATUS_NAME_CHARS),
            profile.platoonId.toString(),
            profile.client.displayName,
        )
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

    private fun holdUntilIdentified(flowId: Long, payload: ParsedPayload) {
        if (admission.retain(flowId, payload)) return
        if (preIdentity.offer(flowId, payload) == BoundedFlowPayloadBuffer.OfferResult.OVERFLOW) {
            CaptureStatus.report(CaptureNotice.FLOW_BUFFER_OVERFLOW)
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
    }
}
