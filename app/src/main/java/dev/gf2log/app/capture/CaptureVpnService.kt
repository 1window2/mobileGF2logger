package dev.gf2log.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.gf2log.app.R
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.management.PlatoonProfileIdentity
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.settings.CapturePreferences
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.PayloadHistoryPreferences
import dev.gf2log.protocol.Gfl2StreamParser
import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.PayloadCatalog
import dev.gf2log.protocol.model.ParseEvent
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonProfileData
import java.io.File
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CaptureVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private val parsers = ConcurrentHashMap<Long, Gfl2StreamParser>()
    private val taintedFlows = ConcurrentHashMap.newKeySet<Long>()
    private val flowMetadata = ConcurrentHashMap<Long, CaptureFlowMetadata>()
    private val flowSessions = ConcurrentHashMap<Long, PlatoonCaptureSession>()
    private val pendingAdmissionByFlow = ConcurrentHashMap<Long, String>()
    private val pendingFlowPayloads = BoundedFlowPayloadBuffer<ParsedPayload>(
        MAX_PENDING_PAYLOADS_PER_FLOW,
        MAX_PENDING_PAYLOADS_TOTAL,
    )
    private val decodedPayloadCount = AtomicLong()
    private val observedPayloadBytes = AtomicLong()
    private val inspectedPayloadBytes = AtomicLong()
    private val reportedTrafficBucket = AtomicLong()
    private val parseWarningCount = AtomicLong()
    private val droppedParserTaskCount = AtomicLong()
    private val unknownPayloadCounts = ConcurrentHashMap<Int, AtomicLong>()
    private val captureChecklist = ScopedCaptureChecklist(REQUIRED_CAPTURE_TYPES)
    private val profileAdmissionGate = PlatoonProfilePolicy.AdmissionGate()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val historyStores = ConcurrentHashMap<String, CaptureHistoryStore>()
    private lateinit var profileRegistry: PlatoonProfileRegistry
    private lateinit var clientServerRegions: ClientServerRegionPreferences
    private lateinit var payloadHistoryPreferences: PayloadHistoryPreferences
    private lateinit var capturePreferences: CapturePreferences
    private lateinit var diagnosticsStore: CaptureDiagnosticsStore
    private var sessionStartedAt: Instant? = null
    private var captureOnce = false
    private val captureOnceGraceStop = Runnable {
        val captured = captureChecklist.targetCaptured()
        if (captureOnce &&
            CaptureStatus.isRunning &&
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS in captured
        ) {
            val missing = REQUIRED_CAPTURE_TYPES
                .minus(captured)
                .map(PayloadCatalog::tag)
                .joinToString()
            stopCapture(CaptureNotice.CAPTURE_ONCE_PARTIAL, missing)
        }
    }
    private val migrationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2LegacyImport")
    }
    @Volatile
    private var captureStartPending = false
    private var parserExecutor = createParserExecutor()

    override fun onCreate() {
        super.onCreate()
        profileRegistry = PlatoonProfileRegistry(this)
        clientServerRegions = ClientServerRegionPreferences(this)
        payloadHistoryPreferences = PayloadHistoryPreferences(this)
        capturePreferences = CapturePreferences(this)
        diagnosticsStore = CaptureDiagnosticsStore(this)
        CaptureStatus.attachLegacyContext(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> {
                captureOnce = intent.getBooleanExtra(EXTRA_CAPTURE_ONCE, false)
                CaptureStatus.beginSession(captureOnce)
                startCapture()
            }
            ACTION_CONFIRM_PENDING_PLATOON -> {
                val token = intent.getStringExtra(EXTRA_PENDING_TOKEN)
                val region = GameServerRegion.fromStored(
                    intent.getStringExtra(EXTRA_SERVER_REGION),
                )
                if (token.isNullOrBlank() || region == GameServerRegion.MANUAL) {
                    CaptureStatus.report(CaptureNotice.ADMISSION_INVALID)
                    stopSelf(startId)
                } else if (!submitParserTask {
                        confirmPendingPlatoon(token, region)
                        if (tunnel == null) stopSelf(startId)
                    }
                ) {
                    CaptureStatus.report(CaptureNotice.PARSER_OVERLOADED)
                    stopSelf(startId)
                }
            }
            ACTION_DISCARD_PENDING_PLATOON -> {
                val token = intent.getStringExtra(EXTRA_PENDING_TOKEN)
                if (!token.isNullOrBlank()) {
                    if (!submitParserTask {
                            discardPendingPlatoon(token)
                            if (tunnel == null) stopSelf(startId)
                        }
                    ) {
                        CaptureStatus.report(CaptureNotice.PARSER_OVERLOADED)
                        stopSelf(startId)
                    }
                } else {
                    stopSelf(startId)
                }
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }

    override fun onDestroy() {
        captureStartPending = false
        releaseCaptureResources()
        if (CaptureStatus.isRunning) CaptureStatus.markStopped()
        mainHandler.removeCallbacksAndMessages(null)
        migrationExecutor.shutdownNow()
        closeAllFlowSessions()
        super.onDestroy()
    }

    private fun startCapture() {
        if (tunnel != null) {
            CaptureStatus.report(CaptureNotice.ALREADY_RUNNING)
            updateNotification("Capturing selected game traffic")
            return
        }
        if (captureStartPending) {
            CaptureStatus.report(CaptureNotice.PREPARING)
            return
        }

        startInForeground("Preparing capture")
        CaptureStatus.report(CaptureNotice.PREPARING)
        if (!NativeCaptureBridge.isAvailable) {
            failStart(CaptureNotice.NATIVE_UNAVAILABLE)
            return
        }

        captureStartPending = true
        migrationExecutor.execute {
            val migration = runCatching {
                profileRegistry.ensureInitialized().forEach { profile ->
                    val scope = PlatoonStorageScope(profile.storageId)
                    PlatoonRepository(this, scope).reconcileRetainedCsvFiles()
                }
            }
            mainHandler.post {
                if (!captureStartPending) return@post
                captureStartPending = false
                migration.fold(
                    onSuccess = { startTunnel() },
                    onFailure = { failStart(CaptureNotice.HISTORY_IMPORT_FAILED) },
                )
            }
        }
    }

    private fun startTunnel() {
        val builder = Builder()
            .setSession("GF2logger")
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addAddress(VPN_IPV6_ADDRESS, VPN_IPV6_PREFIX_LENGTH)
            .addRoute("::", 0)

        val installedTargets = mutableListOf<String>()
        SupportedGamePackages.all.forEach { targetPackage ->
            try {
                builder.addAllowedApplication(targetPackage)
                installedTargets += targetPackage
            } catch (_: PackageManager.NameNotFoundException) {
                // A user may install either publisher's client or both.
            }
        }
        if (installedTargets.isEmpty()) {
            failStart(CaptureNotice.NO_SUPPORTED_CLIENT)
            return
        }

        val descriptor = try {
            builder.establish()
        } catch (error: Exception) {
            failStart(
                CaptureNotice.VPN_ESTABLISH_FAILED,
                error.message ?: error.javaClass.simpleName,
            )
            return
        }

        if (descriptor == null) {
            failStart(CaptureNotice.VPN_PERMISSION_REVOKED)
            return
        }
        tunnel = descriptor
        if (parserExecutor.isShutdown) {
            parserExecutor = createParserExecutor()
        }
        parsers.clear()
        taintedFlows.clear()
        flowMetadata.clear()
        closeAllFlowSessions()
        pendingFlowPayloads.clear()
        decodedPayloadCount.set(0)
        observedPayloadBytes.set(0)
        inspectedPayloadBytes.set(0)
        reportedTrafficBucket.set(0)
        parseWarningCount.set(0)
        droppedParserTaskCount.set(0)
        unknownPayloadCounts.clear()
        captureChecklist.clear()
        profileAdmissionGate.clear()
        mainHandler.removeCallbacks(captureOnceGraceStop)
        sessionStartedAt = Instant.now()

        val started = try {
            NativeCaptureBridge.start(
                descriptor.fd,
                this,
                object : NativeCaptureBridge.PayloadListener {
                    override fun onFlowOpened(
                        flowId: Long,
                        protocol: Int,
                        localAddress: String,
                        localPort: Int,
                        remoteAddress: String,
                        remotePort: Int,
                    ) {
                        enqueueFlowOpened(
                            flowId,
                            protocol,
                            localAddress,
                            localPort,
                            remoteAddress,
                            remotePort,
                        )
                    }

                    override fun onPayload(flowId: Long, payload: ByteArray) {
                        enqueuePayload(flowId, payload)
                    }

                    override fun onFlowClosed(flowId: Long) {
                        enqueueFlowClosed(flowId)
                    }

                    override fun onTraffic(
                        sentBytes: Long,
                        receivedBytes: Long,
                        inspectedBytes: Long,
                    ) {
                        recordTraffic(sentBytes + receivedBytes, inspectedBytes)
                    }

                    override fun onCaptureStopped() {
                        handleNativeCaptureStopped()
                    }
                },
            )
        } catch (error: Exception) {
            false
        }
        if (!started) {
            failStart(CaptureNotice.NATIVE_START_FAILED)
            return
        }

        CaptureStatus.markRunning(installedTargets)
        updateNotification("Capturing selected game traffic")
    }

    private fun enqueuePayload(flowId: Long, payload: ByteArray) {
        if (payload.isEmpty()) return
        if (flowId in taintedFlows) return
        if (!submitParserTask {
            if (flowId in taintedFlows) return@submitParserTask
            val parser = parsers.computeIfAbsent(flowId) { Gfl2StreamParser() }
            processEvents(flowId, parser.accept(payload))
        }) {
            // A missing TCP chunk makes every later byte offset unreliable.
            // Keep this flow quarantined until native closure instead of
            // feeding a corrupted stream into the framing parser.
            taintedFlows += flowId
        }
    }

    private fun enqueueFlowOpened(
        flowId: Long,
        protocol: Int,
        localAddress: String,
        localPort: Int,
        remoteAddress: String,
        remotePort: Int,
    ) {
        submitParserTask {
            CaptureFlowStateCleanup.registerUnlessQuarantined(
                flowId = flowId,
                value = CaptureFlowMetadata(
                    protocol = protocol,
                    localAddress = localAddress,
                    localPort = localPort,
                    remoteAddress = remoteAddress,
                    remotePort = remotePort,
                    ownerPackage = CaptureFlowOwnerResolver.resolve(
                        this,
                        protocol,
                        localAddress,
                        localPort,
                        remoteAddress,
                        remotePort,
                    ),
                ),
                metadata = flowMetadata,
                quarantinedFlows = taintedFlows,
            )
        }
    }

    private fun enqueueFlowClosed(flowId: Long) {
        if (!submitParserTask {
                val metadata = flowMetadata[flowId]
                if (taintedFlows.remove(flowId)) {
                    CaptureFlowStateCleanup.remove(flowId, parsers, flowMetadata)
                    closeFlowSession(flowId)
                    return@submitParserTask
                }
                val parser = CaptureFlowStateCleanup.remove(flowId, parsers, flowMetadata)
                if (parser != null) {
                    processEvents(
                        flowId = flowId,
                        events = parser.finish(),
                        metadata = metadata,
                        flowEnded = true,
                    )
                }
                closeFlowSession(flowId)
            }
        ) {
            CaptureFlowStateCleanup.remove(flowId, parsers, flowMetadata)
            closeFlowSession(flowId)
            // Earlier queued chunks may still run even though the close task was rejected.
            // Keep the flow tainted until the capture session resets all parser state.
            taintedFlows += flowId
        }
    }

    private fun submitParserTask(task: () -> Unit): Boolean = try {
        parserExecutor.execute {
            runCatching(task).onFailure {
                CaptureStatus.report(CaptureNotice.PROCESSING_FAILED, it.javaClass.simpleName)
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        val dropped = droppedParserTaskCount.incrementAndGet()
        CaptureStatus.report(CaptureNotice.PARSER_OVERLOADED, dropped.toString())
        false
    }

    private fun processEvents(
        flowId: Long,
        events: List<ParseEvent>,
        metadata: CaptureFlowMetadata? = flowMetadata[flowId],
        flowEnded: Boolean = false,
    ) {
        val warnings = events.filterIsInstance<ParseEvent.Warning>()
        if (warnings.isNotEmpty()) parseWarningCount.addAndGet(warnings.size.toLong())

        val decoded = events.filterIsInstance<ParseEvent.Payload>()
        events.filterIsInstance<ParseEvent.UnknownPayload>().forEach { event ->
            unknownPayloadCounts.computeIfAbsent(event.payloadType) { AtomicLong() }.incrementAndGet()
        }
        decoded.forEachIndexed { index, event ->
            if (event.value.payloadType == Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE) {
                val profile = event.value.data as? PlatoonProfileData
                if (PlatoonProfilePolicy.isValid(profile)) {
                    requireNotNull(profile)
                    identifyFlow(flowId, metadata, profile)
                }
            }
            val session = flowSessions[flowId]
            if (session == null) {
                retainPendingPayload(flowId, event.value)
                return@forEachIndexed
            }
            routeConfirmedPayload(
                session,
                event.value,
                flowEnded = flowEnded && index == decoded.lastIndex,
            )
        }
        if (decoded.isNotEmpty()) decodedPayloadCount.addAndGet(decoded.size.toLong())
    }

    private fun identifyFlow(
        flowId: Long,
        metadata: CaptureFlowMetadata?,
        data: PlatoonProfileData,
    ) {
        if (pendingFlowPayloads.isRejected(flowId)) {
            quarantineFlow(flowId)
            CaptureStatus.report(CaptureNotice.IDENTIFIED_FLOW_BUFFER_OVERFLOW)
            return
        }
        val ownerPackage = metadata?.ownerPackage
        if (ownerPackage !in SupportedGamePackages.all) {
            quarantineFlow(flowId)
            CaptureStatus.report(CaptureNotice.CLIENT_UNVERIFIED)
            return
        }
        val verifiedOwnerPackage = requireNotNull(ownerPackage)
        val client = requireNotNull(PlatoonClient.fromPackage(verifiedOwnerPackage))
        pendingAdmissionByFlow[flowId]?.let { token ->
            val pending = PendingPlatoonAdmissionStore.summary(token)
            if (
                pending?.ownerPackage == verifiedOwnerPackage &&
                pending.profile.platoonId == data.platoonId
            ) {
                return
            }
            discardPendingPlatoon(token)
            quarantineFlow(flowId)
            CaptureStatus.report(CaptureNotice.PENDING_IDENTITY_CHANGED)
            return
        }
        val knownProfiles = profileRegistry.findByClientAndPlatoonId(
            client,
            data.platoonId.toLong(),
        )
        val configuredRegion = clientServerRegions.configured(verifiedOwnerPackage)
        val known = when {
            knownProfiles.size == 1 -> knownProfiles.single()
            knownProfiles.size > 1 -> knownProfiles.singleOrNull {
                it.serverRegion == configuredRegion
            }
            else -> null
        }
        if (known == null) {
            beginPendingPlatoon(flowId, verifiedOwnerPackage, data)
            return
        }
        val current = flowSessions[flowId]
        if (current != null && current.profile.storageId != known.storageId) {
            quarantineFlow(flowId)
            CaptureStatus.report(CaptureNotice.IDENTITY_CHANGED)
            return
        }
        val profile = runCatching {
            profileRegistry.updateObserved(known.storageId, data)
        }.getOrElse {
            quarantineFlow(flowId)
            CaptureStatus.report(CaptureNotice.PROFILE_UPDATE_FAILED)
            return
        }
        if (current?.profile?.storageId == profile.storageId) {
            markRequiredPayloadCaptured(
                profile.storageId,
                Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
            )
            return
        }
        val session = replaceFlowSession(flowId, profile)
        pendingFlowPayloads.take(flowId).forEach { pending ->
            routeConfirmedPayload(session, pending)
        }
        CaptureStatus.report(
            CaptureNotice.PLATOON_DETECTED,
            profile.platoonName.take(40),
            profile.platoonId.toString(),
            profile.client.displayName,
        )
        markRequiredPayloadCaptured(
            profile.storageId,
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
        )
    }

    private fun retainPendingPayload(flowId: Long, payload: ParsedPayload) {
        pendingAdmissionByFlow[flowId]?.let { token ->
            when (val result = PendingPlatoonAdmissionStore.offer(token, flowId, payload)) {
                PendingPlatoonAdmissionStore.OfferResult.Accepted -> Unit
                PendingPlatoonAdmissionStore.OfferResult.Claimed ->
                    CaptureStatus.report(CaptureNotice.ADMISSION_IN_PROGRESS)
                PendingPlatoonAdmissionStore.OfferResult.Missing -> {
                    pendingAdmissionByFlow.remove(flowId, token)
                    quarantineFlow(flowId)
                }
                is PendingPlatoonAdmissionStore.OfferResult.Overflow -> {
                    result.rejectedFlowIds.forEach { rejectedFlow ->
                        pendingAdmissionByFlow.remove(rejectedFlow, token)
                        quarantineFlow(rejectedFlow)
                    }
                    CaptureStatus.report(CaptureNotice.PENDING_OVERFLOW)
                }
            }
            return
        }
        when (pendingFlowPayloads.offer(flowId, payload)) {
            BoundedFlowPayloadBuffer.OfferResult.OVERFLOW ->
                CaptureStatus.report(CaptureNotice.FLOW_BUFFER_OVERFLOW)
            BoundedFlowPayloadBuffer.OfferResult.ACCEPTED,
            BoundedFlowPayloadBuffer.OfferResult.REJECTED,
            -> Unit
        }
    }

    private fun beginPendingPlatoon(
        flowId: Long,
        ownerPackage: String,
        data: PlatoonProfileData,
    ) {
        val result = PendingPlatoonAdmissionStore.begin(ownerPackage, data, flowId)
        val token = result.token
        if (token == null) {
            result.rejectedFlowIds.forEach(::quarantineFlow)
            CaptureStatus.report(CaptureNotice.PENDING_QUEUE_FULL)
            return
        }
        pendingAdmissionByFlow[flowId] = token
        pendingFlowPayloads.take(flowId).forEach { pending ->
            when (val offered = PendingPlatoonAdmissionStore.offer(token, flowId, pending)) {
                PendingPlatoonAdmissionStore.OfferResult.Accepted -> Unit
                is PendingPlatoonAdmissionStore.OfferResult.Overflow -> {
                    offered.rejectedFlowIds.forEach { rejected ->
                        pendingAdmissionByFlow.remove(rejected, token)
                        quarantineFlow(rejected)
                    }
                    CaptureStatus.report(CaptureNotice.PENDING_OVERFLOW)
                    return
                }
                PendingPlatoonAdmissionStore.OfferResult.Claimed,
                PendingPlatoonAdmissionStore.OfferResult.Missing,
                -> {
                    pendingAdmissionByFlow.remove(flowId, token)
                    quarantineFlow(flowId)
                    return
                }
            }
        }
        CaptureStatus.report(CaptureNotice.PENDING_DETECTED)
    }

    private fun confirmPendingPlatoon(token: String, region: GameServerRegion) {
        val claim = PendingPlatoonAdmissionStore.claim(token) ?: return
        val ownerPackage = claim.summary.ownerPackage
        if (region !in clientServerRegions.allowed(ownerPackage)) {
            PendingPlatoonAdmissionStore.releaseClaim(token)
            CaptureStatus.report(CaptureNotice.ADMISSION_REGION_MISMATCH)
            return
        }
        val client = requireNotNull(PlatoonClient.fromPackage(ownerPackage))
        val existing = profileRegistry.findByIdentity(
            client,
            region,
            claim.summary.profile.platoonId.toLong(),
        )
        if (!profileAdmissionGate.canAdmit(ownerPackage, existing != null)) {
            PendingPlatoonAdmissionStore.releaseClaim(token)
            CaptureStatus.report(CaptureNotice.ADMISSION_LIMIT)
            return
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
            PendingPlatoonAdmissionStore.releaseClaim(token)
            CaptureStatus.report(CaptureNotice.ADMISSION_PROFILE_FAILED)
            return
        }
        if (existing == null) profileAdmissionGate.markAdmitted(ownerPackage)

        val sessions = mutableMapOf<Long, PlatoonCaptureSession>()
        val routed = runCatching {
            claim.flowIds.forEach { flowId ->
                val session = replaceFlowSession(flowId, profile)
                sessions[flowId] = session
                if (flowId !in claim.endedFlowIds && flowMetadata.containsKey(flowId)) {
                    flowSessions[flowId] = session
                }
            }
            val lastPayloadIndexByFlow = claim.payloads
                .withIndex()
                .associate { it.value.flowId to it.index }
            claim.payloads.forEachIndexed { index, buffered ->
                val session = sessions.getOrPut(buffered.flowId) {
                    replaceFlowSession(buffered.flowId, profile)
                }
                routeConfirmedPayload(
                    session = session,
                    payload = buffered.payload,
                    flowEnded = buffered.flowId in claim.endedFlowIds &&
                        lastPayloadIndexByFlow[buffered.flowId] == index,
                )
            }
        }
        if (routed.isFailure) {
            sessions.forEach { (flowId, session) ->
                flowSessions.remove(flowId, session)
                runCatching(session::close)
            }
            PendingPlatoonAdmissionStore.releaseClaim(token)
            CaptureStatus.report(CaptureNotice.ADMISSION_REPLAY_FAILED)
            return
        }
        claim.endedFlowIds.forEach { flowId ->
            sessions.remove(flowId)?.let { session ->
                flowSessions.remove(flowId, session)
                session.close()
            }
        }
        PendingPlatoonAdmissionStore.complete(token).forEach { flowId ->
            pendingAdmissionByFlow.remove(flowId, token)
        }
        CaptureStatus.report(
            CaptureNotice.PLATOON_CONFIRMED,
            profile.platoonName.take(40),
            profile.platoonId.toString(),
            profile.client.displayName,
        )
    }

    private fun discardPendingPlatoon(token: String) {
        PendingPlatoonAdmissionStore.discard(token).forEach { flowId ->
            pendingAdmissionByFlow.remove(flowId, token)
            if (flowMetadata.containsKey(flowId) || parsers.containsKey(flowId)) {
                quarantineFlow(flowId)
            }
        }
        CaptureStatus.report(CaptureNotice.PENDING_DISCARDED)
    }

    private fun replaceFlowSession(
        flowId: Long,
        profile: dev.gf2log.app.management.PlatoonProfile,
    ): PlatoonCaptureSession {
        flowSessions.remove(flowId)?.close()
        return PlatoonCaptureSession(
            context = this,
            profile = profile,
            onRosterCaptured = ::markRosterCaptured,
        ).also { flowSessions[flowId] = it }
    }

    /** Permanently blocks management routing for this flow until native closure. */
    private fun quarantineFlow(flowId: Long) {
        taintedFlows += flowId
        flowSessions.remove(flowId)?.close()
        pendingFlowPayloads.reject(flowId)
    }

    private fun routePayload(
        session: PlatoonCaptureSession,
        payload: ParsedPayload,
        flowEnded: Boolean = false,
    ) {
        val routed = session.dispatch(payload, flowEnded)
        routed.activity?.onSuccess { accepted ->
            if (accepted) {
                markRequiredPayloadCaptured(
                    session.profile.storageId,
                    Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
                )
            }
        }?.onFailure { CaptureStatus.report(CaptureNotice.ACTIVITY_SAVE_FAILED) }
        routed.updates?.onSuccess { accepted ->
            if (accepted) {
                markRequiredPayloadCaptured(
                    session.profile.storageId,
                    Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
                )
            }
        }?.onFailure { CaptureStatus.report(CaptureNotice.UPDATES_SAVE_FAILED) }
        routed.members.onSuccess { saved ->
            if (saved != null) {
                CaptureStatus.report(
                    CaptureNotice.ROSTER_SAVED,
                    saved.rowCount.toString(),
                    session.profile.platoonName,
                )
            }
        }.onFailure { CaptureStatus.report(CaptureNotice.ROSTER_SAVE_FAILED) }
    }

    private fun routeConfirmedPayload(
        session: PlatoonCaptureSession,
        payload: ParsedPayload,
        flowEnded: Boolean = false,
    ) {
        saveHistoryOnly(session.profile.storageId, payload)
        routePayload(session, payload, flowEnded)
        if (payload.payloadType == Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE) {
            markRequiredPayloadCaptured(
                session.profile.storageId,
                Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
            )
        }
    }

    private fun saveHistoryOnly(storageId: String, payload: ParsedPayload) {
        if (!payloadHistoryPreferences.isEnabled(payload.payloadType)) return
        val historyStore = historyStores.computeIfAbsent(storageId) {
            val scope = PlatoonStorageScope(storageId)
            CaptureHistoryStore(
                File(scope.rootDirectory(this), CaptureHistoryStore.HISTORY_DIRECTORY),
            )
        }
        runCatching { historyStore.save(payload) }
            .onFailure { CaptureStatus.report(CaptureNotice.HISTORY_SAVE_FAILED) }
    }

    private fun closeFlowSession(flowId: Long) {
        pendingAdmissionByFlow.remove(flowId)?.let { token ->
            PendingPlatoonAdmissionStore.markFlowEnded(token, flowId)
        }
        pendingFlowPayloads.remove(flowId)
        flowSessions.remove(flowId)?.close()
    }

    private fun closeAllFlowSessions() {
        flowSessions.values.forEach { runCatching { it.close() } }
        flowSessions.clear()
    }

    private fun maybeStopCaptureOnce() {
        if (!captureOnce || !CaptureStatus.isRunning) return
        if (!captureChecklist.targetComplete()) return
        mainHandler.removeCallbacks(captureOnceGraceStop)
        mainHandler.post {
            if (captureOnce &&
                CaptureStatus.isRunning &&
                captureChecklist.targetComplete()
            ) {
                stopCapture(CaptureNotice.CAPTURE_ONCE_COMPLETE)
            }
        }
    }

    private fun markRosterCaptured(storageId: String) {
        markRequiredPayloadCaptured(storageId, Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS)
        if (captureOnce && captureChecklist.targetScopeId() == storageId) {
            mainHandler.removeCallbacks(captureOnceGraceStop)
            mainHandler.postDelayed(captureOnceGraceStop, CAPTURE_ONCE_GRACE_MILLIS)
        }
    }

    private fun markRequiredPayloadCaptured(storageId: String, payloadType: Int) {
        captureChecklist.mark(storageId, payloadType, chooseTarget = captureOnce)
        if (!captureOnce || captureChecklist.targetScopeId() == storageId) {
            CaptureStatus.markUsefulPayload(payloadType)
        }
        maybeStopCaptureOnce()
    }

    private fun recordTraffic(observed: Long, inspected: Long) {
        observedPayloadBytes.set(observed)
        inspectedPayloadBytes.set(inspected)
        val bucket = observed / TRAFFIC_REPORT_BYTES
        val previousBucket = reportedTrafficBucket.get()
        if (bucket > previousBucket && reportedTrafficBucket.compareAndSet(previousBucket, bucket)) {
            CaptureStatus.report(
                CaptureNotice.TRAFFIC,
                (observed / 1024).toString(),
                (inspected / 1024).toString(),
                decodedPayloadCount.get().toString(),
                parseWarningCount.get().toString(),
                droppedParserTaskCount.get().toString(),
            )
        }
    }

    private fun handleNativeCaptureStopped() {
        mainHandler.post {
            if (!CaptureStatus.isRunning) return@post
            tunnel?.close()
            tunnel = null
            drainParserTasks()
            parsers.clear()
            flowMetadata.clear()
            closeAllFlowSessions()
            pendingFlowPayloads.clear()
            CaptureStatus.markStopped(CaptureNotice.STOPPED_UNEXPECTEDLY)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun failStart(notice: CaptureNotice, vararg args: String) {
        captureStartPending = false
        releaseCaptureResources()
        CaptureStatus.markStopped(notice, *args)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture(notice: CaptureNotice? = null, vararg args: String) {
        captureStartPending = false
        releaseCaptureResources()
        CaptureStatus.markStopped(notice, *args)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCaptureResources() {
        NativeCaptureBridge.stop()
        tunnel?.close()
        tunnel = null
        drainParserTasks()
        parsers.clear()
        taintedFlows.clear()
        flowMetadata.clear()
        closeAllFlowSessions()
        pendingAdmissionByFlow.forEach { (flowId, token) ->
            PendingPlatoonAdmissionStore.markFlowEnded(token, flowId)
        }
        pendingAdmissionByFlow.clear()
        pendingFlowPayloads.clear()
        saveDiagnostics()
    }

    private fun saveDiagnostics() {
        if (sessionStartedAt == null) return
        diagnosticsStore.save(
            CaptureDiagnosticsStore.Diagnostics(
                startedAt = sessionStartedAt,
                stoppedAt = Instant.now(),
                forwardedBytes = observedPayloadBytes.get(),
                inspectedBytes = inspectedPayloadBytes.get(),
                decodedPayloads = decodedPayloadCount.get(),
                warnings = parseWarningCount.get(),
                droppedChunks = droppedParserTaskCount.get(),
                unknownPayloads = unknownPayloadCounts.entries
                    .sortedBy { it.key }
                    .joinToString { "${it.key}:${it.value.get()}" },
                finalStatus = CaptureStatus.snapshot().notice?.format(this).orEmpty(),
            ),
        )
        sessionStartedAt = null
    }

    private fun drainParserTasks() {
        parserExecutor.shutdown()
        val completed = try {
            parserExecutor.awaitTermination(PARSER_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            val dropped = parserExecutor.shutdownNow().size
            droppedParserTaskCount.addAndGet(dropped.toLong())
        }
    }

    private fun createParserExecutor() = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(PARSER_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "GF2ProtocolParser") },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun startInForeground(content: String) {
        val notification = buildNotification(notificationContent(content))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(
                NOTIFICATION_ID,
                buildNotification(notificationContent(content)),
            )
    }

    private fun notificationContent(content: String): String =
        if (capturePreferences.detailedNotifications) {
            content
        } else {
            getString(R.string.notification_capture_active)
        }

    private fun buildNotification(content: String): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "dev.gf2log.action.START"
        const val ACTION_STOP = "dev.gf2log.action.STOP"
        const val ACTION_CONFIRM_PENDING_PLATOON = "dev.gf2log.action.CONFIRM_PENDING_PLATOON"
        const val ACTION_DISCARD_PENDING_PLATOON = "dev.gf2log.action.DISCARD_PENDING_PLATOON"
        const val EXTRA_CAPTURE_ONCE = "capture_once"
        const val EXTRA_PENDING_TOKEN = "pending_platoon_token"
        const val EXTRA_SERVER_REGION = "pending_server_region"
        private const val NOTIFICATION_CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        private const val VPN_ADDRESS = "10.77.0.1"
        private const val VPN_PREFIX_LENGTH = 30
        private const val VPN_IPV6_ADDRESS = "fd77:1::1"
        private const val VPN_IPV6_PREFIX_LENGTH = 120
        private const val VPN_MTU = 1500
        private const val PARSER_QUEUE_CAPACITY = 256
        private const val PARSER_DRAIN_TIMEOUT_SECONDS = 3L
        private const val TRAFFIC_REPORT_BYTES = 64 * 1024
        private const val CAPTURE_ONCE_GRACE_MILLIS = 60_000L
        private const val MAX_PENDING_PAYLOADS_PER_FLOW = 32
        private const val MAX_PENDING_PAYLOADS_TOTAL = 128
        private val REQUIRED_CAPTURE_TYPES = setOf(
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
        )
    }
}
