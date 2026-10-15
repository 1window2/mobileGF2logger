package dev.gf2log.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureWorkerTest {
    @Test
    fun chunkBudgetRejectsOverflowButNeverLifecycleTasks() {
        val failures = mutableListOf<Throwable>()
        val worker = CaptureWorker("test-worker", chunkCapacity = 2) { failures += it }
        val gate = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        try {
            assertTrue(worker.execute { gate.await(5, TimeUnit.SECONDS) })
            assertTrue(worker.submitChunk { order += "chunk-1" })
            assertTrue(worker.submitChunk { order += "chunk-2" })
            assertFalse(worker.submitChunk { order += "dropped" })
            assertTrue(worker.execute { order += "teardown" })

            gate.countDown()
            val drained = CountDownLatch(1)
            worker.execute { drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))

            assertEquals(listOf("chunk-1", "chunk-2", "teardown"), order)
            assertTrue(worker.submitChunk { order += "chunk-3" })
        } finally {
            worker.shutdown()
        }
        assertTrue(failures.isEmpty())
    }

    @Test
    fun failingTasksAreReportedAndReleaseTheirBudget() {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val worker = CaptureWorker("test-worker", chunkCapacity = 1) { failures += it }
        try {
            repeat(3) {
                val finished = CountDownLatch(1)
                assertTrue(worker.submitChunk { error("boom") })
                worker.execute { finished.countDown() }
                assertTrue(finished.await(5, TimeUnit.SECONDS))
            }
            assertEquals(3, failures.size)
        } finally {
            worker.shutdown()
        }
        assertFalse(worker.execute {})
        assertFalse(worker.submitChunk {})
    }
}
