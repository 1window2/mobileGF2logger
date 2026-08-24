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
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.settings.PayloadHistoryPreferences
import dev.gf2log.app.settings.CapturePreferences
import dev.gf2log.app.settings.ClientServerRegionPreferences
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
    private val pendingFlowPayloads = BoundedFlowPayloadBuffer<ParsedPayload>(
        MAX_PENDING_PAYLOADS_PER_FLOW,
    )
    private val decodedPayloadCount = AtomicLong()
    private val observedPayloadBytes = AtomicLong()
    private val inspectedPayloadBytes = AtomicLong()
    private val reportedTrafficBucket = AtomicLong()
    private val parseWarningCount = AtomicLong()
    private val droppedParserTaskCount = AtomicLong()
    private val unknownPayloadCounts = ConcurrentHashMap<Int, AtomicLong>()
    private val captureChecklist = ScopedCaptureChecklist(REQUIRED_CAPTURE_TYPES)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var historyStore: CaptureHistoryStore
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
            stopCapture("Captured Platoon roster; missing $missing after the navigation period")
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
        historyStore = CaptureHistoryStore(
            File(filesDir, CaptureHistoryStore.HISTORY_DIRECTORY),
        )
        payloadHistoryPreferences = PayloadHistoryPreferences(this)
        capturePreferences = CapturePreferences(this)
        diagnosticsStore = CaptureDiagnosticsStore(this)
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
            CaptureStatus.markRunning("Capture is already running")
            updateNotification("Capturing selected game traffic")
            return
        }
        if (captureStartPending) {
            CaptureStatus.update("Preparing capture")
            return
        }

        startInForeground("Preparing capture")
        CaptureStatus.update("Preparing capture")
        if (!NativeCaptureBridge.isAvailable) {
            failStart("Protocol parser ready; native forwarding core is not integrated yet")
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
                    onFailure = { failStart("Unable to import previous Platoon history") },
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
            failStart("Install a supported HaoPlay or Darkwinter client first")
            return
        }

        val descriptor = try {
            builder.establish()
        } catch (error: Exception) {
            failStart("Unable to establish VPN: ${error.message ?: error.javaClass.simpleName}")
            return
        }

        if (descriptor == null) {
            failStart("VPN permission was revoked")
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
        mainHandler.removeCallbacks(captureOnceGraceStop)
        sessionStartedAt = Instant.now()

        CaptureStatus.markRunning("Starting native capture")
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

                    override fun onPayload(flowId: Long, isSent: Boolean, payload: ByteArray) {
                        enqueuePayload(flowId, isSent, payload)
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
            failStart("Native forwarding core failed to start")
            return
        }

        CaptureStatus.markRunning("Capturing only ${installedTargets.joinToString()}")
        updateNotification("Capturing selected game traffic")
    }

    private fun enqueuePayload(flowId: Long, isSent: Boolean, payload: ByteArray) {
        if (payload.isEmpty()) return
        if (isSent) return
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
            flowMetadata[flowId] = CaptureFlowMetadata(
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
                CaptureStatus.update("Protocol processing failed: ${it.javaClass.simpleName}")
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        val dropped = droppedParserTaskCount.incrementAndGet()
        CaptureStatus.update("Parser overloaded; dropped $dropped queued chunks")
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
            if (payloadHistoryPreferences.isEnabled(event.value.payloadType)) {
                runCatching { historyStore.save(event.value) }
                    .onFailure { CaptureStatus.update("Unable to save parsed-packet history") }
            }
            val session = flowSessions[flowId]
            if (session == null) {
                retainPendingPayload(flowId, event.value)
                return@forEachIndexed
            }
            routePayload(
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
        val ownerPackage = metadata?.ownerPackage
        if (ownerPackage !in SupportedGamePackages.all) {
            pendingFlowPayloads.reject(flowId)
            CaptureStatus.update("Detected a Platoon profile, but its game client could not be verified")
            return
        }
        val profile = runCatching {
            profileRegistry.upsertDetected(
                ownerPackage = requireNotNull(ownerPackage),
                region = clientServerRegions.get(ownerPackage),
                data = data,
            )
        }.getOrElse {
            pendingFlowPayloads.reject(flowId)
            CaptureStatus.update("Unable to isolate the detected Platoon")
            return
        }
        val current = flowSessions[flowId]
        if (current?.profile?.storageId == profile.storageId) {
            markRequiredPayloadCaptured(
                profile.storageId,
                Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
            )
            return
        }
        current?.close()
        val session = PlatoonCaptureSession(
            context = this,
            profile = profile,
            onRosterCaptured = ::markRosterCaptured,
            onStatus = CaptureStatus::update,
        )
        flowSessions[flowId] = session
        pendingFlowPayloads.take(flowId).forEach { pending ->
            routePayload(session, pending)
        }
        CaptureStatus.update(
            "Detected ${profile.platoonName.take(40)} (${profile.platoonId}) via " +
                profile.client.displayName,
        )
        markRequiredPayloadCaptured(
            profile.storageId,
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
        )
    }

    private fun retainPendingPayload(flowId: Long, payload: ParsedPayload) {
        when (pendingFlowPayloads.offer(flowId, payload)) {
            BoundedFlowPayloadBuffer.OfferResult.OVERFLOW ->
                CaptureStatus.update("Discarded an unidentified Platoon flow that exceeded its buffer")
            BoundedFlowPayloadBuffer.OfferResult.ACCEPTED,
            BoundedFlowPayloadBuffer.OfferResult.REJECTED,
            -> Unit
        }
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
        }?.onFailure { CaptureStatus.update("Unable to update Platoon activity history") }
        routed.updates?.onSuccess { accepted ->
            if (accepted) {
                markRequiredPayloadCaptured(
                    session.profile.storageId,
                    Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
                )
            }
        }?.onFailure { CaptureStatus.update("Unable to update exact Platoon history") }
        routed.members.onSuccess { saved ->
            if (saved != null) {
                CaptureStatus.update(
                    "Saved ${saved.rowCount} members for ${session.profile.platoonName}",
                )
            }
        }.onFailure { CaptureStatus.update("Unable to save Platoon CSV") }
    }

    private fun closeFlowSession(flowId: Long) {
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
                stopCapture("Captured Platoon roster, activity, and updates")
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
            CaptureStatus.update(
                "Forwarded ${observed / 1024} KiB; inspected ${inspected / 1024} KiB; " +
                    "decoded ${decodedPayloadCount.get()} payloads; " +
                    "warnings ${parseWarningCount.get()}; dropped ${droppedParserTaskCount.get()}",
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
            CaptureStatus.markStopped("Capture stopped unexpectedly; press Prepare capture to retry")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun failStart(message: String) {
        captureStartPending = false
        releaseCaptureResources()
        CaptureStatus.markStopped(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture(message: String = "") {
        captureStartPending = false
        releaseCaptureResources()
        if (message.isBlank()) CaptureStatus.markStopped() else CaptureStatus.markStopped(message)
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
                finalStatus = CaptureStatus.read(),
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
        const val EXTRA_CAPTURE_ONCE = "capture_once"
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
        private val REQUIRED_CAPTURE_TYPES = setOf(
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
        )
    }
}
