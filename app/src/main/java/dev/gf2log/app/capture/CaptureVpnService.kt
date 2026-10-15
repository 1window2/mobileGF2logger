package dev.gf2log.app.capture

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.gf2log.app.R
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.PayloadHistoryPreferences

/**
 * Lifecycle glue between Android's VPN service, the native forwarding core, and the capture worker.
 *
 * Every field is confined to the main thread. Blocking work (history migration, joining the native
 * thread, draining queued chunks) runs on [CaptureWorker], whose FIFO order guarantees teardown as
 * "stop native, then drain parser, then clear state". A start requested during teardown waits for it.
 */
class CaptureVpnService : VpnService() {
    private enum class State { IDLE, STARTING, RUNNING, STOPPING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stats = CaptureTrafficStats()
    private lateinit var profileRegistry: PlatoonProfileRegistry
    private lateinit var diagnosticsStore: CaptureDiagnosticsStore
    private lateinit var notifications: CaptureNotifications
    private lateinit var worker: CaptureWorker
    private lateinit var engine: CaptureFlowEngine
    private var state = State.IDLE
    private var run: CaptureRunTracker? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var restartCaptureOnce: Boolean? = null
    private var destroyed = false

    private val captureOnceGraceStop = Runnable {
        val missing = run?.takeIf { state == State.RUNNING }?.missingAfterRoster() ?: return@Runnable
        stop(CaptureNotice.CAPTURE_ONCE_PARTIAL, missing.joinToString())
    }

    override fun onCreate() {
        super.onCreate()
        profileRegistry = PlatoonProfileRegistry(this)
        diagnosticsStore = CaptureDiagnosticsStore(this)
        notifications = CaptureNotifications(this).also { it.createChannel() }
        worker = CaptureWorker("GF2CaptureWorker", PARSER_QUEUE_CAPACITY) { error ->
            CaptureStatus.report(CaptureNotice.PROCESSING_FAILED, error.javaClass.simpleName)
        }
        engine = CaptureFlowEngine(
            context = this,
            worker = worker,
            stats = stats,
            profileRegistry = profileRegistry,
            clientServerRegions = ClientServerRegionPreferences(this),
            payloadHistory = PayloadHistoryPreferences(this),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getBooleanExtra(EXTRA_CAPTURE_ONCE, false))
            ACTION_STOP -> stop()
            ACTION_CONFIRM_PENDING_PLATOON -> {
                val token = intent.getStringExtra(EXTRA_PENDING_TOKEN)
                val region = GameServerRegion.fromStored(intent.getStringExtra(EXTRA_SERVER_REGION))
                if (token.isNullOrBlank() || region == GameServerRegion.MANUAL) {
                    CaptureStatus.report(CaptureNotice.ADMISSION_INVALID)
                    stopIfIdle(startId)
                } else {
                    runAdmission(startId) { engine.admission.confirm(token, region) }
                }
            }
            ACTION_DISCARD_PENDING_PLATOON -> {
                val token = intent.getStringExtra(EXTRA_PENDING_TOKEN)
                if (token.isNullOrBlank()) {
                    stopIfIdle(startId)
                } else {
                    runAdmission(startId) { engine.admission.discard(token) }
                }
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        stop()
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(captureOnceGraceStop)
        when (state) {
            State.IDLE -> worker.shutdown()
            // The teardown shuts the worker down once it completes.
            State.STOPPING -> Unit
            State.STARTING, State.RUNNING -> stop()
        }
        super.onDestroy()
    }

    /** Candidates outlive capture, so admission decisions also run while capture is stopped. */
    private fun runAdmission(startId: Int, task: () -> Unit) {
        val queued = worker.execute {
            try {
                task()
            } finally {
                mainHandler.post { stopIfIdle(startId) }
            }
        }
        if (!queued) stopIfIdle(startId)
    }

    private fun stopIfIdle(startId: Int) {
        if (state == State.IDLE && !destroyed) stopSelf(startId)
    }

    private fun start(captureOnce: Boolean) {
        // Every startForegroundService request must be answered with a foreground notification.
        notifications.startForeground(
            if (state == State.RUNNING) {
                R.string.capture_notification_running
            } else {
                R.string.capture_notification_preparing
            },
        )
        when (state) {
            State.RUNNING -> {
                CaptureStatus.report(CaptureNotice.ALREADY_RUNNING)
                return
            }
            State.STARTING -> {
                CaptureStatus.report(CaptureNotice.PREPARING)
                return
            }
            State.STOPPING -> {
                restartCaptureOnce = captureOnce
                CaptureStatus.markStarting()
                return
            }
            State.IDLE -> Unit
        }

        state = State.STARTING
        val tracker = CaptureRunTracker(
            captureOnce = captureOnce,
            onComplete = { completed ->
                mainHandler.post {
                    if (run === completed && state == State.RUNNING) {
                        stop(CaptureNotice.CAPTURE_ONCE_COMPLETE)
                    }
                }
            },
            onTargetRosterCaptured = { captured ->
                mainHandler.post {
                    if (run === captured) {
                        mainHandler.removeCallbacks(captureOnceGraceStop)
                        mainHandler.postDelayed(captureOnceGraceStop, CAPTURE_ONCE_GRACE_MILLIS)
                    }
                }
            },
        )
        run = tracker
        CaptureStatus.beginSession(captureOnce)
        CaptureStatus.markStarting()
        if (!NativeCaptureBridge.isAvailable) {
            stop(CaptureNotice.NATIVE_UNAVAILABLE)
            return
        }
        worker.execute {
            val migration = runCatching {
                profileRegistry.ensureInitialized().forEach { profile ->
                    PlatoonRepository(this, PlatoonStorageScope(profile.storageId))
                        .reconcileRetainedCsvFiles()
                }
            }
            mainHandler.post {
                if (run !== tracker || state != State.STARTING) return@post
                if (migration.isSuccess) {
                    establish(tracker)
                } else {
                    stop(CaptureNotice.HISTORY_IMPORT_FAILED)
                }
            }
        }
    }

    private fun establish(tracker: CaptureRunTracker) {
        val builder = Builder()
            .setSession("GF2logger")
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addAddress(VPN_IPV6_ADDRESS, VPN_IPV6_PREFIX_LENGTH)
            .addRoute("::", 0)

        val installedTargets = SupportedGamePackages.all.filter { targetPackage ->
            try {
                builder.addAllowedApplication(targetPackage)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false // A user may install either publisher's client or both.
            }
        }
        if (installedTargets.isEmpty()) {
            stop(CaptureNotice.NO_SUPPORTED_CLIENT)
            return
        }

        val descriptor = try {
            builder.establish()
        } catch (error: Exception) {
            stop(CaptureNotice.VPN_ESTABLISH_FAILED, error.message ?: error.javaClass.simpleName)
            return
        }
        if (descriptor == null) {
            stop(CaptureNotice.VPN_PERMISSION_REVOKED)
            return
        }
        tunnel = descriptor
        stats.begin()
        // Queued before the native thread exists, so it precedes every native callback.
        worker.execute { engine.beginSession(tracker) }

        val listener = engine.nativeListener {
            mainHandler.post {
                if (run === tracker && state == State.RUNNING) stop(CaptureNotice.STOPPED_UNEXPECTEDLY)
            }
        }
        val started = runCatching { NativeCaptureBridge.start(descriptor.fd, this, listener) }
            .getOrDefault(false)
        if (!started) {
            stop(CaptureNotice.NATIVE_START_FAILED)
            return
        }

        state = State.RUNNING
        CaptureStatus.markRunning(installedTargets)
        notifications.update(R.string.capture_notification_running)
    }

    private fun stop(notice: CaptureNotice? = null, vararg args: String) {
        mainHandler.removeCallbacks(captureOnceGraceStop)
        restartCaptureOnce = null
        val lastNotice = CaptureStatus.snapshot().notice
        CaptureStatus.markStopped(notice, *args)
        notifications.remove()
        when (state) {
            State.IDLE -> {
                if (!destroyed) stopSelf()
                return
            }
            State.STOPPING -> return
            State.STARTING, State.RUNNING -> Unit
        }

        state = State.STOPPING
        run = null
        val descriptor = tunnel
        tunnel = null
        worker.execute {
            try {
                // Joins the native thread; no callback can enqueue work after this returns.
                NativeCaptureBridge.stop()
                runCatching { descriptor?.close() }
            } finally {
                // Runs after every chunk the native thread queued before the join.
                worker.execute {
                    try {
                        engine.endSession()
                        val finalNotice = CaptureStatus.snapshot().notice ?: lastNotice
                        stats.finish(finalNotice?.format(this).orEmpty())?.let(diagnosticsStore::save)
                    } finally {
                        mainHandler.post(::onTeardownComplete)
                    }
                }
            }
        }
    }

    private fun onTeardownComplete() {
        state = State.IDLE
        if (destroyed) {
            worker.shutdown()
            return
        }
        val restart = restartCaptureOnce
        restartCaptureOnce = null
        if (restart != null) start(restart) else stopSelf()
    }

    companion object {
        const val ACTION_START = "dev.gf2log.action.START"
        const val ACTION_STOP = "dev.gf2log.action.STOP"
        const val ACTION_CONFIRM_PENDING_PLATOON = "dev.gf2log.action.CONFIRM_PENDING_PLATOON"
        const val ACTION_DISCARD_PENDING_PLATOON = "dev.gf2log.action.DISCARD_PENDING_PLATOON"
        const val EXTRA_CAPTURE_ONCE = "capture_once"
        const val EXTRA_PENDING_TOKEN = "pending_platoon_token"
        const val EXTRA_SERVER_REGION = "pending_server_region"
        private const val VPN_ADDRESS = "10.77.0.1"
        private const val VPN_PREFIX_LENGTH = 30
        private const val VPN_IPV6_ADDRESS = "fd77:1::1"
        private const val VPN_IPV6_PREFIX_LENGTH = 120
        private const val VPN_MTU = 1500
        private const val PARSER_QUEUE_CAPACITY = 256
        private const val CAPTURE_ONCE_GRACE_MILLIS = 60_000L
    }
}
