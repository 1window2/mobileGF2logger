package dev.gf2log.app.capture

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single thread that owns every parser, flow, session, and admission structure.
 *
 * Native callbacks share a bounded chunk budget and are rejected when it is exhausted. Lifecycle
 * and admission tasks bypass that budget, so teardown, confirm, and discard are never dropped
 * while the worker is open. Tasks run in submission order.
 */
internal class CaptureWorker(
    name: String,
    private val chunkCapacity: Int,
    private val onFailure: (Throwable) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }
    private val queuedChunks = AtomicInteger()

    init {
        require(chunkCapacity > 0)
    }

    fun submitChunk(task: () -> Unit): Boolean {
        if (queuedChunks.incrementAndGet() > chunkCapacity) {
            queuedChunks.decrementAndGet()
            return false
        }
        val queued = execute {
            try {
                task()
            } finally {
                queuedChunks.decrementAndGet()
            }
        }
        if (!queued) queuedChunks.decrementAndGet()
        return queued
    }

    fun execute(task: () -> Unit): Boolean = try {
        executor.execute { runCatching(task).onFailure(onFailure) }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    /** Already queued tasks still run; later submissions are rejected. */
    fun shutdown() = executor.shutdown()
}
