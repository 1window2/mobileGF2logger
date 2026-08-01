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
        val capturedAt = listOf(
            "2026-07-19T19:29:33Z",
            "2026-07-20T19:53:20Z",
            "2026-07-21T19:11:09Z",
            "2026-07-22T15:06:39Z",
            "2026-07-23T15:46:30Z",
            "2026-07-24T15:59:38Z",
            "2026-07-25T17:02:17Z",
        ).map(Instant::parse)
        val highScores = listOf(
            7_921L,
            7_982L,
            7_982L,
            7_982L,
            7_985L,
            7_985L,
            7_985L,
        )
        val days = (0L..6L).map(start::plusDays)
        val checkpoints = totals.mapIndexed { index, (totalMerit, totalScore, weeklyMerit) ->
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt[index],
                members = listOf(
                    member(
                        totalMerit = totalMerit,
                        totalScore = totalScore,
                        weeklyMerit = weeklyMerit,
                        highScore = highScores[index],
                    ),
                ),
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
                hasFinalGunsmokeScore = index == days.lastIndex,
                isGunsmokeWeek = true,
                metricObservedAt = checkpoints[index].capturedAt,
            )
        }

        val resolution = GunsmokeWeekSolver.resolve(
            uid = UID,
            days = days,
            zoneId = zone,
            snapshots = checkpoints,
            cells = cells,
            dailyPatrolFacts = emptyList(),
        )
        val resolved = resolution.cells

        assertEquals(7, resolved.size)
        resolved.forEach { cell ->
            require(cell.meritDelta == null || cell.meritDelta >= 0L)
            require(cell.scoreDelta == null || cell.scoreDelta >= 0L)
            require(cell.attempts == null || cell.attempts in 0..3)
        }
        repeat(4) { index -> assertEquals(true, resolved[index].dailyPatrol) }
        assertEquals(18, resolution.totals!!.attempts)
        assertEquals(MetricCertainty.LOWER_BOUND, resolution.totals!!.attemptsCertainty)

        val contradictoryCheckpoints = checkpoints.toMutableList().apply {
            val contradictory = this[3].members.single().copy(weeklyMerit = 1L)
            this[3] = this[3].copy(members = listOf(contradictory))
        }
        val contradictory = GunsmokeWeekSolver.resolve(
            uid = UID,
            days = days,
            zoneId = zone,
            snapshots = contradictoryCheckpoints,
            cells = cells,
            dailyPatrolFacts = emptyList(),
        )
        assertNull(contradictory.totals)
    }

    // Function Name: finalEventPacketCanConfirmWeeklyAttemptsWithoutFixingTheirDailyPlacement
    // Description:
    // - Models three adjacent days whose two-attempt shortfall can move between days.
    // - Proves the final event packet still fixes the whole-week total at twenty attempts.
    // Returns:
    // - Keeps the affected daily cells as lower bounds while publishing an exact aggregate.
    @Test
    fun finalEventPacketCanConfirmWeeklyAttemptsWithoutFixingTheirDailyPlacement() {
        val days = (0L..6L).map(start::plusDays)
        val totalScores = listOf(30_000L, 60_000L, 90_000L, 120_000L, 140_000L, 170_000L, 200_000L)
        val totalMerits = listOf(3_180L, 6_360L, 9_540L, 12_720L, 14_870L, 18_050L, 21_230L)
        val weeklyMerits = listOf(3_180L, 3_180L, 6_360L, 9_540L, 11_690L, 14_870L, 18_050L)
        val checkpoints = days.mapIndexed { index, day ->
            val capturedAt = if (index == days.lastIndex) {
                day.plusDays(1).atTime(2, 2).atZone(zone).toInstant()
            } else {
                day.plusDays(1).atTime(4, 0).atZone(zone).toInstant()
            }
            PlatoonSnapshot(
                id = index.toLong(),
                capturedAt = capturedAt,
                members = listOf(
                    member(
                        totalMerit = totalMerits[index],
                        totalScore = totalScores[index],
                        weeklyMerit = weeklyMerits[index],
                    ),
                ),
            )
        }
        val cells = days.mapIndexed { index, day ->
            val attempts = if (index in 4..6) 2 else 3
            val score = attempts * 10_000L
            val merit = 90L + attempts * ActivityInference.MERIT_PER_ATTEMPT + score / 10L
            WeeklyReportBuilder.DayCell(
                gameDay = day,
                meritDelta = merit,
                scoreDelta = score,
                inference = ActivityInference.infer(merit, score, gunsmokeActive = true),
                evidence = DailyEvidence.PARTIAL_DAY,
                hasFinalGunsmokeScore = index == days.lastIndex,
                isGunsmokeWeek = true,
                metricObservedAt = checkpoints[index].capturedAt,
            )
        }

        val resolution = GunsmokeWeekSolver.resolve(
            uid = UID,
            days = days,
            zoneId = zone,
            snapshots = checkpoints,
            cells = cells,
            dailyPatrolFacts = emptyList(),
        )

        val totals = requireNotNull(resolution.totals) { resolution.cells.joinToString() }
        assertEquals(20, totals.attempts)
        assertEquals(MetricCertainty.EXACT, totals.attemptsCertainty)
        assertEquals(List(3) { 2 }, resolution.cells.takeLast(3).map { it.attempts })
        assertEquals(
            List(3) { MetricCertainty.LOWER_BOUND },
            resolution.cells.takeLast(3).map { it.attemptsCertainty },
        )
    }

    @Test
    fun invalidNegativeCountersRemainConservative() {
        val days = (0L..6L).map(start::plusDays)
        val checkpoint = PlatoonSnapshot(
            id = 0,
            capturedAt = days.first().plusDays(1).atTime(4, 0).atZone(zone).toInstant(),
            members = listOf(
                member(totalMerit = 1_000L, totalScore = -1L, weeklyMerit = 1_000L),
            ),
        )
        val cells = days.map { day ->
            WeeklyReportBuilder.DayCell(
                gameDay = day,
                meritDelta = null,
                scoreDelta = null,
                inference = null,
                evidence = DailyEvidence.INCOMPLETE_BOUNDARY,
                isGunsmokeWeek = true,
            )
        }

        val resolution = GunsmokeWeekSolver.resolve(
            uid = UID,
            days = days,
            zoneId = zone,
            snapshots = listOf(checkpoint),
            cells = cells,
            dailyPatrolFacts = emptyList(),
        )

        assertEquals(cells, resolution.cells)
        assertNull(resolution.totals)
    }

    @Test
    fun realMondayResetTransitionPreservesBothObservedScorePrefixes() {
        val days = (0L..6L).map(start::plusDays)
        val checkpoints = listOf(
            PlatoonSnapshot(
                id = 1,
                capturedAt = Instant.parse("2026-07-19T19:29:33Z"),
                members = listOf(member(260_754L, 21_090L, 2_798L, 10_545L)),
            ),
            PlatoonSnapshot(
                id = 2,
                capturedAt = Instant.parse("2026-07-20T19:53:20Z"),
                members = listOf(member(262_992L, 41_980L, 2_238L, 10_545L)),
            ),
        )
        val cells = days.mapIndexed { index, day ->
            WeeklyReportBuilder.DayCell(
                gameDay = day,
                meritDelta = if (index < 2) 50L else null,
                scoreDelta = null,
                inference = null,
                evidence = if (index < 2) DailyEvidence.PARTIAL_DAY else DailyEvidence.NO_OBSERVATION,
                isGunsmokeWeek = true,
                hasLoginFact = index < 2,
            )
        }

        val resolution = GunsmokeWeekSolver.resolve(
            uid = UID,
            days = days,
            zoneId = zone,
            snapshots = checkpoints,
            cells = cells,
            dailyPatrolFacts = emptyList(),
        )

        assertEquals(21_090L, resolution.cells[0].scoreDelta)
        assertEquals(2, resolution.cells[0].attempts)
        assertEquals(20_890L, resolution.cells[1].scoreDelta)
        assertEquals(2, resolution.cells[1].attempts)
    }

    @Test
    fun sundayActivityCannotExceedTheCapturedWeeklyMeritCounter() {
        val days = (0L..6L).map(start::plusDays)
        val checkpoint = PlatoonSnapshot(
            id = 0,
            capturedAt = Instant.parse("2026-07-19T19:29:33Z"),
            members = listOf(member(10_000L, 31_635L, 100L, 10_545L)),
        )
        val cells = days.map { day ->
            WeeklyReportBuilder.DayCell(
                gameDay = day,
                meritDelta = null,
                scoreDelta = null,
                inference = null,
                evidence = DailyEvidence.INCOMPLETE_BOUNDARY,
                isGunsmokeWeek = true,
            )
        }

        val resolution = GunsmokeWeekSolver.resolve(
            uid = UID,
            days = days,
            zoneId = zone,
            snapshots = listOf(checkpoint),
            cells = cells,
            dailyPatrolFacts = emptyList(),
        )

        assertEquals(cells, resolution.cells)
        assertNull(resolution.totals)
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
        val firstWeeklyMerit = if (startIndex == 0) firstMerit else weeklyBase + firstMerit
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

    private fun member(
        totalMerit: Long,
        totalScore: Long,
        weeklyMerit: Long,
        highScore: Long = 10_000,
    ) = SnapshotMember(
        uid = UID,
        name = "Solver member",
        level = 60,
        weeklyMerit = weeklyMerit,
        totalMerit = totalMerit,
        highScore = highScore,
        totalScore = totalScore,
        lastLogin = 0,
    )

    private companion object {
        const val UID = 1L
        const val LOGIN_MERIT = 50L
        const val PATROL_MERIT = 40L
    }
}
