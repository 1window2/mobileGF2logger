package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GunsmokeWeekSolverTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val start = LocalDate.of(2026, 7, 19)

    @Test
    fun residualFiftyProvesPatrolAbsence() {
        val cells = solveTransition(patrolCredits = 0)

        assertEquals(false, cells[0].dailyPatrol)
        assertNull(cells[1].dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cells[0].meritCertainty)
        assertEquals(MetricCertainty.LOWER_BOUND, cells[1].meritCertainty)
    }

    @Test
    fun residualNinetyUsesTheWeeklyCheckpointToResolveTheSplit() {
        val cells = solveTransition(patrolCredits = 1)

        assertEquals(false, cells[0].dailyPatrol)
        assertEquals(true, cells[1].dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cells[0].meritCertainty)
        assertEquals(MetricCertainty.EXACT, cells[1].meritCertainty)
    }

    @Test
    fun residualOneThirtyProvesBothPatrols() {
        val cells = solveTransition(patrolCredits = 2)

        assertEquals(true, cells[0].dailyPatrol)
        assertEquals(true, cells[1].dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cells[0].meritCertainty)
        assertEquals(MetricCertainty.EXACT, cells[1].meritCertainty)
    }

    @Test
    fun exactPatrolTimestampDisambiguatesResidualNinety() {
        val cells = solveTransition(
            patrolCredits = 1,
            weeklyPatrolCredits = 0,
            facts = listOf(DailyPatrolFact(UID, Instant.parse("2026-07-19T19:30:00Z"))),
        )

        assertEquals(true, cells[0].dailyPatrol)
        assertNull(cells[1].dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cells[0].meritCertainty)
        assertEquals(MetricCertainty.LOWER_BOUND, cells[1].meritCertainty)
    }

    @Test
    fun aMissingEarlierCheckpointDoesNotSuppressALaterContiguousRun() {
        val cells = solveTransition(
            patrolCredits = 2,
            startIndex = 3,
            weeklyBase = 900,
            totalMeritBase = 100_000,
            totalScoreBase = 500_000,
        )

        assertNull(cells[0].dailyPatrol)
        assertNull(cells[1].dailyPatrol)
        assertEquals(true, cells[3].dailyPatrol)
        assertEquals(true, cells[4].dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cells[3].meritCertainty)
        assertEquals(MetricCertainty.EXACT, cells[4].meritCertainty)
    }

    // Function Name: restoredSevenDayHistoryCompletesWithinTheNavigationBudget
    // Description:
    // - Reproduces the seven counter checkpoints that previously made week navigation hang.
    // - Guards the complete solver path as well as the exact three-attempt daily projections.
    // Returns:
    // - Completes within three seconds with seven valid projected cells.
    @Test(timeout = 3_000)
    fun restoredSevenDayHistoryCompletesWithinTheNavigationBudget() {
        val totals = listOf(
            Triple(265_908L, 22_112L, 2_930L),
            Triple(268_221L, 43_457L, 2_313L),
            Triple(270_373L, 63_189L, 4_465L),
            Triple(272_749L, 85_160L, 6_841L),
            Triple(274_489L, 101_065L, 8_581L),
            Triple(276_054L, 115_226L, 10_146L),
            Triple(277_656L, 129_752L, 11_748L),
        )
        val dailyMetrics = listOf(
            2_390L to 22_112L,
            2_313L to 21_345L,
            2_152L to 19_732L,
            2_376L to 21_971L,
            1_740L to 15_905L,
            1_565L to 14_161L,
            1_602L to 14_526L,
        )
        val days = (0L..6L).map(start::plusDays)
        val periodStart = PlatoonPeriods.periodStartInstant(start, zone)
        val anchor = PlatoonSnapshot(
            id = 0,
            capturedAt = periodStart.minusSeconds(5 * 60L),
            members = listOf(
                member(
                    totalMerit = 263_518L,
                    totalScore = 147_777L,
                    weeklyMerit = 540L,
                ),
            ),
        )
        val checkpoints = totals.mapIndexed { index, (totalMerit, totalScore, weeklyMerit) ->
            PlatoonSnapshot(
                id = 0,
                capturedAt = days[index]
                    .plusDays(1)
                    .atTime(4, 0)
                    .atZone(zone)
                    .toInstant(),
                members = listOf(member(totalMerit, totalScore, weeklyMerit)),
            )
        }
        val cells = days.mapIndexed { index, day ->
            val (merit, score) = dailyMetrics[index]
            WeeklyReportBuilder.DayCell(
                gameDay = day,
                meritDelta = merit,
                scoreDelta = score,
                inference = ActivityInference.infer(merit, score, gunsmokeActive = true),
                evidence = DailyEvidence.PARTIAL_DAY,
                isGunsmokeWeek = true,
                metricObservedAt = checkpoints[index].capturedAt,
            )
        }

        val resolved = GunsmokeWeekSolver.solve(
            uid = UID,
            days = days,
            zoneId = zone,
            snapshots = listOf(anchor) + checkpoints,
            cells = cells,
            dailyPatrolFacts = emptyList(),
        )

        assertEquals(7, resolved.size)
        resolved.forEach { cell ->
            require(cell.meritDelta == null || cell.meritDelta >= 0L)
            require(cell.scoreDelta == null || cell.scoreDelta >= 0L)
            require(cell.attempts == null || cell.attempts in 0..3)
        }
        repeat(4) { index -> assertEquals(true, resolved[index].dailyPatrol) }
    }

    private fun solveTransition(
        patrolCredits: Int,
        weeklyPatrolCredits: Int = patrolCredits.coerceAtMost(1),
        facts: List<DailyPatrolFact> = emptyList(),
        startIndex: Int = 0,
        weeklyBase: Long = 0,
        totalMeritBase: Long = 0,
        totalScoreBase: Long = 0,
    ): List<WeeklyReportBuilder.DayCell> {
        require(startIndex in 0..5)
        val score = 30_000L
        val activityMerit = 3 * ActivityInference.MERIT_PER_ATTEMPT + score / 10
        val firstMerit = activityMerit + LOGIN_MERIT
        val transitionMerit = activityMerit + LOGIN_MERIT + patrolCredits * PATROL_MERIT
        val firstWeeklyMerit = if (startIndex == 0) 0 else weeklyBase + firstMerit
        val first = member(
            totalMerit = totalMeritBase + firstMerit,
            totalScore = totalScoreBase + score,
            weeklyMerit = firstWeeklyMerit,
        )
        val second = member(
            totalMerit = totalMeritBase + firstMerit + transitionMerit,
            totalScore = totalScoreBase + score * 2,
            weeklyMerit = if (startIndex == 0) {
                activityMerit + LOGIN_MERIT + weeklyPatrolCredits * PATROL_MERIT
            } else {
                firstWeeklyMerit + transitionMerit
            },
        )
        val days = (0L..6L).map(start::plusDays)
        val firstCapturedAt = days[startIndex]
            .plusDays(1)
            .atTime(4, 0)
            .atZone(zone)
            .toInstant()
        val snapshots = listOf(
            PlatoonSnapshot(0, firstCapturedAt, listOf(first)),
            PlatoonSnapshot(0, firstCapturedAt.plusSeconds(24 * 60 * 60), listOf(second)),
        )
        val cells = days.mapIndexed { index, day ->
            if (index in startIndex..startIndex + 1) {
                val merit = if (index == startIndex) firstMerit else transitionMerit
                WeeklyReportBuilder.DayCell(
                    gameDay = day,
                    meritDelta = merit,
                    scoreDelta = score,
                    inference = ActivityInference.infer(merit, score, gunsmokeActive = true),
                    evidence = DailyEvidence.PARTIAL_DAY,
                    hasDailyPatrolFact = facts.any { fact ->
                        fact.uid == UID && PlatoonPeriods.gameDay(fact.occurredAt, zone) == day
                    },
                    isGunsmokeWeek = true,
                    metricObservedAt = snapshots[index - startIndex].capturedAt,
                )
            } else {
                WeeklyReportBuilder.DayCell(day, null, null, null, DailyEvidence.NO_OBSERVATION)
            }
        }

        return GunsmokeWeekSolver.solve(UID, days, zone, snapshots, cells, facts)
    }

    private fun member(totalMerit: Long, totalScore: Long, weeklyMerit: Long) = SnapshotMember(
        uid = UID,
        name = "Solver member",
        level = 60,
        weeklyMerit = weeklyMerit,
        totalMerit = totalMerit,
        highScore = 10_000,
        totalScore = totalScore,
        lastLogin = 0,
    )

    private companion object {
        const val UID = 1L
        const val LOGIN_MERIT = 50L
        const val PATROL_MERIT = 40L
    }
}
