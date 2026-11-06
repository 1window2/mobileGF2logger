package dev.gf2log.app.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureFlowIntakeTest {
    private val worker = CaptureWorker("intake-test", chunkCapacity = 1) { throw AssertionError(it) }
    private val tainted: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val order = Collections.synchronizedList(mutableListOf<String>())
    private var dropped = 0
    private val intake = CaptureFlowIntake(worker, tainted) { dropped += 1 }

    @After
    fun tearDown() = worker.shutdown()

    @Test
    fun aCloseNeedsNoBudgetAndRunsAfterTheFlowsQueuedChunks() {
        val gate = blockWorker()
        intake.opened(7) { order += "open" }
        intake.payload(7) { order += "chunk" }
        assertEquals(1, dropped)
        assertTrue(7L in tainted)

        intake.closed(7) {
            order += "close"
            tainted.remove(7)
        }
        assertEquals(1, dropped)

        gate.countDown()
        drain()
        assertEquals(listOf("open", "close"), order)
        assertFalse(7L in tainted)
    }

    @Test
    fun aFlowWhoseOpenWasRefusedStaysInertUntilItCloses() {
        val gate = blockWorker()
        intake.payload(1) { order += "other" }
        intake.opened(2) { order += "open" }
        assertEquals(1, dropped)
        assertTrue(2L in tainted)

        gate.countDown()
        drain()
        // Budget is free again, but the flow has no owner metadata, so its chunks are ignored.
        intake.payload(2) { order += "chunk" }
        drain()
        assertEquals(listOf("other"), order)

        intake.closed(2) { tainted.remove(2) }
        drain()
        assertFalse(2L in tainted)
    }

    @Test
    fun onlyAShutDownWorkerLeavesAClosedFlowTainted() {
        worker.shutdown()
        intake.closed(3) { order += "close" }
        assertTrue(3L in tainted)
        assertTrue(order.isEmpty())
    }

    private fun blockWorker(): CountDownLatch {
        val gate = CountDownLatch(1)
        assertTrue(worker.execute { gate.await(5, TimeUnit.SECONDS) })
        return gate
    }

    private fun drain() {
        val drained = CountDownLatch(1)
        assertTrue(worker.execute { drained.countDown() })
        assertTrue(drained.await(5, TimeUnit.SECONDS))
    }
}
