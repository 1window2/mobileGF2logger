package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyMemberNameProjectionTest {
    @Test
    fun capturedCustomNameOverridesReportFallback() {
        val merged = WeeklyMemberNameProjection.merge(
            reportNamesByUid = mapOf(1L to "Snapshot", 2L to "Report only"),
            capturedNamesByUid = mapOf(1L to "Custom", 3L to "Event only"),
        )

        assertEquals("Custom", merged[1L])
        assertEquals("Report only", merged[2L])
        assertEquals("Event only", merged[3L])
    }
}
