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

    @Test
    fun fiveHundredTwelveSparseCaptureRoutinesRemainConservativeAndOrderIndependent() {
        val random = Random(0x2_3_2)

        repeat(512) { caseIndex ->
            val allocation = List(6) { DAILY_VALUES[random.nextInt(DAILY_VALUES.size)] }
            var cumulative = 0L
            var total = 50_000L
            var lastLogin = 0L
            val complete = allocation.mapIndexed { index, merit ->
                val day = periodStart.plusDays(index + 1L)
                cumulative += merit
                total += merit
                if (merit > 0L) lastLogin = day.atTime(12, 0).atZone(zone).toEpochSecond()
                PlatoonSnapshot(
                    id = index.toLong() + 1,
                    capturedAt = day.atTime(23, 45).atZone(zone).toInstant(),
                    sourceFile = "sparse-$caseIndex-$index.csv",
                    members = listOf(
                        SnapshotMember(
                            uid = 8L,
                            name = "Sparse generated",
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
            val selectedIndexes = complete.indices.filter { random.nextBoolean() }
                .ifEmpty { listOf(random.nextInt(complete.size)) }
            val selected = selectedIndexes.map(complete::get)
            val asOf = periodStart.plusDays(8).atTime(6, 0).atZone(zone).toInstant()

            fun build(input: List<PlatoonSnapshot>) = WeeklyReportBuilder.build(
                referenceDay = periodStart.plusDays(3),
                zoneId = zone,
                snapshots = input,
                overrides = listOf(
                    WeeklyCellOverride(
                        uid = 8L,
                        periodStart = periodStart,
                        gameDay = periodStart,
                        meritDelta = 0L,
                        scoreDelta = null,
                        attempts = null,
                        attended = false,
                        dailyPatrol = false,
                    ),
                ),
                asOf = asOf,
            )

            val row = build(selected).members.single()
            row.days.drop(1).zip(allocation).forEach { (cell, actual) ->
                if (cell.meritCertainty == MetricCertainty.EXACT) {
                    assertEquals("exact merit case $caseIndex", actual, cell.meritDelta)
                }
                cell.attended?.let { attended ->
                    assertEquals("attendance case $caseIndex", actual > 0L, attended)
                }
                cell.dailyPatrol?.let { patrol ->
                    assertEquals("patrol case $caseIndex", actual == 90L, patrol)
                }
            }
            assertTrue("merit floor case $caseIndex", row.totalMerit <= allocation.sum())
            if (row.totalMeritCertainty == MetricCertainty.EXACT) {
                assertEquals("exact total case $caseIndex", allocation.sum(), row.totalMerit)
            }

            val reversed = build(selected.reversed()).members.single()
            assertEquals(
                "order case $caseIndex",
                row.days.map { listOf(it.meritDelta, it.meritCertainty, it.attended, it.dailyPatrol) },
                reversed.days.map {
                    listOf(it.meritDelta, it.meritCertainty, it.attended, it.dailyPatrol)
                },
            )
        }
    }

    private companion object {
        val DAILY_VALUES = longArrayOf(0L, 50L, 90L)
    }
}
