package dev.gf2log.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CaptureTrafficStatsTest {
    @Test
    fun trafficIsReportedOncePerBucketAndDiagnosticsOncePerSession() {
        val stats = CaptureTrafficStats()
        assertNull(stats.finish("never started"))

        stats.begin(Instant.EPOCH)
        stats.recordParsed(decoded = 2, warnings = 1)
        stats.recordUnknown(9)
        stats.recordUnknown(9)
        stats.recordUnknown(3)
        stats.recordDropped()

        assertNull(stats.recordTraffic(forwarded = 1_000, inspected = 10))
        assertEquals(listOf("64", "1", "2", "1", "1"), stats.recordTraffic(64 * 1024, 1_024))
        assertNull(stats.recordTraffic(65 * 1024, 1_024))

        val diagnostics = requireNotNull(stats.finish("done", Instant.ofEpochSecond(5)))
        assertEquals(Instant.EPOCH, diagnostics.startedAt)
        assertEquals(2L, diagnostics.decodedPayloads)
        assertEquals("3:1, 9:2", diagnostics.unknownPayloads)
        assertEquals("done", diagnostics.finalStatus)
        assertNull(stats.finish("again"))
    }

    @Test
    fun unknownTypesBeyondTheCapAreCountedTogether() {
        val stats = CaptureTrafficStats()
        stats.begin(Instant.EPOCH)
        val cap = CaptureTrafficStats.MAX_UNKNOWN_TYPES
        (0 until cap + 100).forEach { stats.recordUnknown(it) }
        stats.recordUnknown(0)
        stats.recordUnknown(cap + 500)

        val entries = requireNotNull(stats.finish("done")).unknownPayloads.split(", ")
        assertEquals(cap + 1, entries.size)
        assertEquals("0:2", entries.first())
        assertEquals("*:101", entries.last())

        stats.begin(Instant.EPOCH)
        stats.recordUnknown(7)
        assertEquals("7:1", requireNotNull(stats.finish("again")).unknownPayloads)
    }
}
