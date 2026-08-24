package dev.gf2log.app.management

import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GunsmokeWeekSolverPropertyTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val periodStart = LocalDate.of(2026, 7, 19)

    @Test(timeout = 60_000)
    fun sparseValidCaptureRoutinesRemainConservativeAndOrderIndependent() {
        val random = Random(0x2_3_3)
        var publishedMeritChecks = 0
        var publishedScoreChecks = 0
        repeat(128) { caseIndex ->
            val truth = List(7) {
                val attempts = random.nextInt(0, 4)
                val attemptScores = List(attempts) { random.nextInt(0, 10_001).toLong() }
                val score = attemptScores.sum()
                val attended = attempts > 0 || random.nextBoolean()
                val patrol = attended && random.nextBoolean()
                val merit = attempts * ActivityInference.MERIT_PER_ATTEMPT +
                    attemptScores.sumOf { attemptScore -> attemptScore / 10L } +
                    (if (attended) 50L else 0L) + (if (patrol) 40L else 0L)
                Truth(merit, score, attended, patrol)
            }
            val complete = snapshots(caseIndex, truth)
            val selected = complete.filterIndexed { index, _ ->
                index == 0 || index == complete.lastIndex || random.nextBoolean()
            }

            fun build(input: List<PlatoonSnapshot>) = WeeklyReportBuilder.build(
                referenceDay = periodStart.plusDays(3),
                zoneId = zone,
                snapshots = input,
                asOf = periodStart.plusDays(8).atTime(6, 0).atZone(zone).toInstant(),
            ).members.single().days

            fun assertConservative(label: String, cells: List<WeeklyReportBuilder.DayCell>) {
                cells.zip(truth).forEach { (cell, actual) ->
                    cell.meritDelta?.let { published ->
                        publishedMeritChecks += 1
                        assertTrue("$label merit floor case $caseIndex on ${cell.gameDay}", published <= actual.merit)
                    }
                    cell.scoreDelta?.let { published ->
                        publishedScoreChecks += 1
                        assertTrue("$label score floor case $caseIndex on ${cell.gameDay}", published <= actual.score)
                    }
                    if (cell.meritCertainty == MetricCertainty.EXACT) {
                        assertEquals("$label exact merit case $caseIndex on ${cell.gameDay}", actual.merit, cell.meritDelta)
                    }
                    if (cell.scoreCertainty == MetricCertainty.EXACT) {
                        assertEquals("$label exact score case $caseIndex on ${cell.gameDay}", actual.score, cell.scoreDelta)
                    }
                    cell.attended?.let { attended ->
                        assertEquals("$label attendance case $caseIndex on ${cell.gameDay}", actual.attended, attended)
                    }
                    cell.dailyPatrol?.let { patrol ->
                        assertEquals("$label patrol case $caseIndex on ${cell.gameDay}", actual.patrol, patrol)
                    }
                }
            }
            assertConservative("complete", build(complete))
            val sparseCells = build(selected)
            assertConservative("sparse", sparseCells)
            assertEquals(
                "order independence case $caseIndex",
                sparseCells.map { Triple(it.meritDelta, it.scoreDelta, it.meritCertainty to it.scoreCertainty) },
                build(selected.reversed()).map {
                    Triple(it.meritDelta, it.scoreDelta, it.meritCertainty to it.scoreCertainty)
                },
            )
        }
        assertTrue("generated cases must exercise merit publication", publishedMeritChecks > 0)
        assertTrue("generated cases must exercise score publication", publishedScoreChecks > 0)
    }

    private fun snapshots(caseIndex: Int, truth: List<Truth>): List<PlatoonSnapshot> {
        var totalMerit = 100_000L
        var totalScore = 0L
        var weeklyMerit = 6L * 90L
        val snapshots = mutableListOf(
            snapshot(
                caseIndex,
                0,
                periodStart.atTime(4, 59).atZone(zone).toInstant(),
                weeklyMerit,
                totalMerit,
                totalScore,
            ),
        )
        truth.forEachIndexed { index, day ->
            totalMerit += day.merit
            totalScore += day.score
            weeklyMerit = if (index == 1) day.merit else weeklyMerit + day.merit
            snapshots += snapshot(
                caseIndex,
                index + 1,
                periodStart.plusDays(index + 1L).atTime(4, 30).atZone(zone).toInstant(),
                weeklyMerit,
                totalMerit,
                totalScore,
            )
            if (index == 0) {
                weeklyMerit = 0L
                snapshots += snapshot(
                    caseIndex,
                    20,
                    periodStart.plusDays(1).atTime(5, 1).atZone(zone).toInstant(),
                    weeklyMerit,
                    totalMerit,
                    totalScore,
                )
            }
        }
        return snapshots
    }

    private fun snapshot(
        caseIndex: Int,
        index: Int,
        capturedAt: java.time.Instant,
        weeklyMerit: Long,
        totalMerit: Long,
        totalScore: Long,
    ) = PlatoonSnapshot(
        id = index.toLong(),
        capturedAt = capturedAt,
        sourceFile = "gunsmoke-property-$caseIndex-$index.csv",
        members = listOf(
            SnapshotMember(77L, "Property member", 60L, weeklyMerit, totalMerit, 10_000L, totalScore, 0L),
        ),
    )

    private data class Truth(
        val merit: Long,
        val score: Long,
        val attended: Boolean,
        val patrol: Boolean,
    )
}
