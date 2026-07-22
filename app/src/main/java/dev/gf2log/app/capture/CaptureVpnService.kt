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
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.protocol.Gfl2StreamParser
import dev.gf2log.protocol.model.ParseEvent
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CaptureVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private val parsers = ConcurrentHashMap<Long, Gfl2StreamParser>()
    private val decodedPayloadCount = AtomicLong()
    private val observedPayloadBytes = AtomicLong()
    private val reportedTrafficBucket = AtomicLong()
    private val parseWarningCount = AtomicLong()
    private val droppedParserTaskCount = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var guildMembersWriter: GuildMembersCsvWriter
    private lateinit var historyStore: CaptureHistoryStore
    private val parserExecutor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(PARSER_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "GF2ProtocolParser") },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun onCreate() {
        super.onCreate()
        guildMembersWriter = GuildMembersCsvWriter(
            File(filesDir, GuildMembersCsvWriter.OUTPUT_DIRECTORY),
        )
        historyStore = CaptureHistoryStore(
            File(filesDir, CaptureHistoryStore.HISTORY_DIRECTORY),
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> startCapture(intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty())
        }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }

    override fun onDestroy() {
        releaseCaptureResources()
        if (CaptureStatus.isRunning) CaptureStatus.markStopped()
        mainHandler.removeCallbacksAndMessages(null)
        guildMembersWriter.close()
        super.onDestroy()
    }

    private fun startCapture(targetPackage: String) {
        if (tunnel != null) {
            CaptureStatus.markRunning("Capture is already running")
            updateNotification("Capturing selected game traffic")
            return
        }

        startInForeground("Preparing capture")
        CaptureStatus.update("Preparing capture")
        if (targetPackage.isBlank()) {
            failStart("Enter the installed game package name")
            return
        }
        if (!NativeCaptureBridge.isAvailable) {
            failStart("Protocol parser ready; native forwarding core is not integrated yet")
            return
        }

        val descriptor = try {
            Builder()
                .setSession("GF2logger")
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addAddress(VPN_IPV6_ADDRESS, VPN_IPV6_PREFIX_LENGTH)
                .addRoute("::", 0)
                .addAllowedApplication(targetPackage)
                .establish()
        } catch (_: PackageManager.NameNotFoundException) {
            failStart("Target package is not installed: $targetPackage")
            return
        } catch (error: Exception) {
            failStart("Unable to establish VPN: ${error.message ?: error.javaClass.simpleName}")
            return
        }

        if (descriptor == null) {
            failStart("VPN permission was revoked")
            return
        }
        tunnel = descriptor
        decodedPayloadCount.set(0)
        observedPayloadBytes.set(0)
        reportedTrafficBucket.set(0)
        parseWarningCount.set(0)
        droppedParserTaskCount.set(0)

        CaptureStatus.markRunning("Starting native capture")
        val started = try {
            NativeCaptureBridge.start(
                descriptor.fd,
                this,
                object : NativeCaptureBridge.PayloadListener {
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

        CaptureStatus.markRunning("Capturing only $targetPackage")
        updateNotification("Capturing selected game traffic")
    }

    private fun enqueuePayload(flowId: Long, isSent: Boolean, payload: ByteArray) {
        if (payload.isEmpty()) return
        if (isSent) return
        submitParserTask {
            val parser = parsers.computeIfAbsent(flowId) { Gfl2StreamParser() }
            processEvents(parser.accept(payload))
        }
    }

    private fun enqueueFlowClosed(flowId: Long) {
        if (!submitParserTask {
                val parser = parsers.remove(flowId) ?: return@submitParserTask
                processEvents(parser.finish(), flowEnded = true)
            }
        ) {
            parsers.remove(flowId)
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

    private fun processEvents(events: List<ParseEvent>, flowEnded: Boolean = false) {
        val warnings = events.filterIsInstance<ParseEvent.Warning>()
        if (warnings.isNotEmpty()) parseWarningCount.addAndGet(warnings.size.toLong())

        val decoded = events.filterIsInstance<ParseEvent.Payload>()
        decoded.forEach { event ->
            runCatching { historyStore.save(event.value) }
                .onFailure { CaptureStatus.update("Unable to save parsed-packet history") }
            runCatching { guildMembersWriter.accept(event.value, flowEnded) }
                .onSuccess { saved ->
                    if (saved != null) {
                        CaptureStatus.update(
                            "Saved ${saved.rowCount} guild members to ${saved.file.name}",
                        )
                    }
                }
                .onFailure { CaptureStatus.update("Unable to save guild CSV") }
        }
        if (decoded.isNotEmpty()) decodedPayloadCount.addAndGet(decoded.size.toLong())
    }

    private fun recordTraffic(observed: Long, inspected: Long) {
        observedPayloadBytes.set(observed)
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
            CaptureStatus.markStopped("Capture stopped unexpectedly; press Prepare capture to retry")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun failStart(message: String) {
        releaseCaptureResources()
        CaptureStatus.markStopped(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture() {
        releaseCaptureResources()
        CaptureStatus.markStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCaptureResources() {
        NativeCaptureBridge.stop()
        tunnel?.close()
        tunnel = null
        drainParserTasks()
        parsers.clear()
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

    private fun startInForeground(content: String) {
        val notification = buildNotification(content)
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
            .notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
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
        const val EXTRA_TARGET_PACKAGE = "target_package"
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
    }
}
