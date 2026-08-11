package dev.gf2log.app.management

import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue

class StandardWeekSolverPropertyTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val periodStart = LocalDate.of(2026, 7, 26)

    @Test
    fun generatedAllocationsNeverProduceUnsupportedExactValues() {
        val random = Random(0x2_2_0)

        repeat(128) { caseIndex ->
            val allocation = List(6) { DAILY_VALUES[random.nextInt(DAILY_VALUES.size)] }
            var cumulative = 0L
            var total = 10_000L
            var lastLogin = 0L
            val snapshots = allocation.mapIndexed { index, merit ->
                val day = periodStart.plusDays(index + 1L)
                cumulative += merit
                total += merit
                if (merit > 0L) {
                    lastLogin = day.atTime(12, 0).atZone(zone).toEpochSecond()
                }
                PlatoonSnapshot(
                    id = index.toLong() + 1L,
                    capturedAt = day.atTime(23, 30).atZone(zone).toInstant(),
                    sourceFile = "generated-$caseIndex-$index.csv",
                    members = listOf(
                        SnapshotMember(
                            uid = 7L,
                            name = "Generated",
                            level = 1L,
                            weeklyMerit = cumulative,
                            totalMerit = total,
                            highScore = 0L,
                            totalScore = 0L,
                            lastLogin = lastLogin,
                        ),
                    ),
                )
            }

            val asOf = periodStart.plusDays(7).atTime(6, 0).atZone(zone).toInstant()
            val report = WeeklyReportBuilder.build(
                referenceDay = periodStart.plusDays(3),
                zoneId = zone,
                snapshots = snapshots,
                asOf = asOf,
            )
            val cells = report.members.single().days.drop(1).take(6)
            cells.zip(allocation).forEach { (cell, generated) ->
                cell.meritDelta?.let { published ->
                    assertEquals("case $caseIndex on ${cell.gameDay}", generated, published)
                }
                if (cell.meritCertainty == MetricCertainty.EXACT) {
                    assertEquals("exact case $caseIndex on ${cell.gameDay}", generated, cell.meritDelta)
                }
            }
            assertTrue(
                "case $caseIndex published more merit than observed",
                cells.mapNotNull { it.meritDelta }.sum() <= allocation.sum(),
            )
            val reversed = WeeklyReportBuilder.build(
                referenceDay = periodStart.plusDays(3),
                zoneId = zone,
                snapshots = snapshots.reversed(),
                asOf = asOf,
            )
            assertEquals(
                "case $caseIndex",
                cells.map { it.meritDelta to it.meritCertainty },
                reversed.members.single().days.drop(1).take(6)
                    .map { it.meritDelta to it.meritCertainty },
            )
        }
    }

    private companion object {
        val DAILY_VALUES = longArrayOf(0L, 50L, 90L)
    }
}
