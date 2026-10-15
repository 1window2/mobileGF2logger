package dev.gf2log.app.capture

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One capture session's counters, written by the native and worker threads. */
internal class CaptureTrafficStats {
    private val forwardedBytes = AtomicLong()
    private val inspectedBytes = AtomicLong()
    private val decodedPayloads = AtomicLong()
    private val warnings = AtomicLong()
    private val droppedChunks = AtomicLong()
    private val reportedBucket = AtomicLong()
    private val unknownPayloads = ConcurrentHashMap<Int, AtomicLong>()

    @Volatile
    private var startedAt: Instant? = null

    fun begin(now: Instant = Instant.now()) {
        listOf(forwardedBytes, inspectedBytes, decodedPayloads, warnings, droppedChunks, reportedBucket)
            .forEach { it.set(0) }
        unknownPayloads.clear()
        startedAt = now
    }

    fun recordParsed(decoded: Int, warnings: Int) {
        if (decoded > 0) decodedPayloads.addAndGet(decoded.toLong())
        if (warnings > 0) this.warnings.addAndGet(warnings.toLong())
    }

    fun recordUnknown(payloadType: Int) {
        unknownPayloads.computeIfAbsent(payloadType) { AtomicLong() }.incrementAndGet()
    }

    fun recordDropped(): Long = droppedChunks.incrementAndGet()

    /** Returns [CaptureNotice.TRAFFIC] args each time forwarded traffic crosses another bucket. */
    fun recordTraffic(forwarded: Long, inspected: Long): List<String>? {
        forwardedBytes.set(forwarded)
        inspectedBytes.set(inspected)
        val bucket = forwarded / TRAFFIC_REPORT_BYTES
        val previous = reportedBucket.get()
        if (bucket <= previous || !reportedBucket.compareAndSet(previous, bucket)) return null
        return listOf(
            (forwarded / 1024).toString(),
            (inspected / 1024).toString(),
            decodedPayloads.get().toString(),
            warnings.get().toString(),
            droppedChunks.get().toString(),
        )
    }

    /** Ends the session; returns null when no tunnel was established since [begin]. */
    fun finish(finalStatus: String, stoppedAt: Instant = Instant.now()): CaptureDiagnosticsStore.Diagnostics? {
        val started = startedAt ?: return null
        startedAt = null
        return CaptureDiagnosticsStore.Diagnostics(
            startedAt = started,
            stoppedAt = stoppedAt,
            forwardedBytes = forwardedBytes.get(),
            inspectedBytes = inspectedBytes.get(),
            decodedPayloads = decodedPayloads.get(),
            warnings = warnings.get(),
            droppedChunks = droppedChunks.get(),
            unknownPayloads = unknownPayloads.entries
                .sortedBy { it.key }
                .joinToString { "${it.key}:${it.value.get()}" },
            finalStatus = finalStatus,
        )
    }

    private companion object {
        const val TRAFFIC_REPORT_BYTES = 64 * 1024
    }
}
