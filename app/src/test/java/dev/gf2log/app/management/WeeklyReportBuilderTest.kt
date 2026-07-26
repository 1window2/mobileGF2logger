package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportBuilderTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun gunsmokeReportUsesSundayAndPerDayDeltas() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 22),
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-19T20:00:00Z", weekly = 100, score = 1000),
                snapshot("2026-07-20T19:59:00Z", weekly = 190, score = 1000),
                snapshot("2026-07-21T19:59:00Z", weekly = 304, score = 1025),
            ),
        )

        assertTrue(report.isGunsmokeWeek)
        assertEquals(LocalDate.of(2026, 7, 19), report.periodStart)
        assertEquals(90L, report.members.single().days[1].meritDelta)
        assertEquals(114L, report.members.single().days[2].meritDelta)
        assertEquals(25L, report.members.single().days[2].scoreDelta)
    }

    @Test
    fun offWeekReportUsesMonday() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = emptyList(),
        )

        assertTrue(!report.isGunsmokeWeek)
        assertEquals(LocalDate.of(2026, 7, 27), report.periodStart)
    }

    private fun snapshot(time: String, weekly: Long, score: Long) =
        PlatoonSnapshot(
            id = 0,
            capturedAt = Instant.parse(time),
            members = listOf(SnapshotMember(1, "One", 60, weekly, weekly, 0, score, 0)),
        )
}
