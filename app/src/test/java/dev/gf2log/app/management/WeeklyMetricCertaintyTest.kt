package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyMetricCertaintyTest {
    private val sunday = LocalDate.of(2026, 7, 19)

    @Test
    fun dailyThreeAttemptsAndUniquePositiveComponentsFinalizeTheCell() {
        val cell = gunsmokeCell(
            day = sunday,
            merit = 3_342,
            score = 31_635,
        )

        assertEquals(3, cell.attempts)
        assertEquals(MetricCertainty.EXACT, cell.attemptsCertainty)
        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(true, cell.attended)
        assertEquals(true, cell.dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cell.meritCertainty)
        assertEquals("3", WeeklyMetricPresentation.format(cell.attempts, cell.attemptsCertainty))
        assertEquals("31635", WeeklyMetricPresentation.format(cell.scoreDelta, cell.scoreCertainty))
        assertEquals("3342", WeeklyMetricPresentation.format(cell.meritDelta, cell.meritCertainty))
    }

    @Test
    fun exactGunsmokeComponentsFinalizeMerit() {
        val cell = gunsmokeCell(
            day = sunday,
            merit = 3_342,
            score = 31_635,
            hasDailyPatrolFact = true,
            metricObservedAt = "2026-07-19T19:59:00Z",
        )

        assertEquals(3, cell.attempts)
        assertEquals(MetricCertainty.EXACT, cell.attemptsCertainty)
        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(true, cell.attended)
        assertEquals(true, cell.dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cell.meritCertainty)
        assertEquals("3342", WeeklyMetricPresentation.format(cell.meritDelta, cell.meritCertainty))
    }

    @Test
    fun aggregateScoreWithMultipleCompatibleMeritTotalsDoesNotFinalizeMerit() {
        val cell = WeeklyReportBuilder.DayCell(
            gameDay = sunday,
            meritDelta = 151,
            scoreDelta = 20,
            inference = ActivityInference.infer(151, 20, gunsmokeActive = true),
            evidence = DailyEvidence.PARTIAL_DAY,
            hasDailyPatrolFact = true,
            isGunsmokeWeek = true,
            metricObservedAt = java.time.Instant.parse("2026-07-19T19:59:00Z"),
        )

        assertEquals(2, cell.attempts)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.attemptsCertainty)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.meritCertainty)
    }

    @Test
    fun uniquePositiveArithmeticCanConfirmPatrolWithoutAnUpdatePacket() {
        val cell = gunsmokeCell(
            day = sunday,
            merit = 3_342,
            score = 31_635,
        )

        assertEquals(MetricCertainty.EXACT, cell.attemptsCertainty)
        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(true, cell.attended)
        assertEquals(true, cell.dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cell.meritCertainty)
        assertEquals("3342", WeeklyMetricPresentation.format(cell.meritDelta, cell.meritCertainty))
    }

    @Test
    fun negativePatrolArithmeticRemainsUnknownUntilTheDayIsFinal() {
        val cell = gunsmokeCell(
            day = sunday,
            merit = 3_302,
            score = 31_635,
        )

        assertEquals(3, cell.attempts)
        assertEquals(MetricCertainty.EXACT, cell.attemptsCertainty)
        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(true, cell.attended)
        assertNull(cell.dailyPatrol)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.meritCertainty)
    }

    @Test
    fun finalScoreCaptureDoesNotFinalizeSubCapAttempts() {
        val cell = WeeklyReportBuilder.DayCell(
            gameDay = sunday.plusDays(6),
            meritDelta = 2_258,
            scoreDelta = 21_090,
            inference = ActivityInference.infer(2_258, 21_090, gunsmokeActive = true),
            evidence = DailyEvidence.PARTIAL_DAY,
            hasFinalGunsmokeScore = true,
            isGunsmokeWeek = true,
        )

        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(2, cell.attempts)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.attemptsCertainty)
    }

    @Test
    fun exactClosingBoundaryFinalizesAllCounterDerivedMetrics() {
        val cell = WeeklyReportBuilder.DayCell(
            gameDay = sunday,
            meritDelta = 2_258,
            scoreDelta = 21_090,
            inference = ActivityInference.infer(2_258, 21_090, gunsmokeActive = true),
            evidence = DailyEvidence.PARTIAL_DAY,
            isGunsmokeWeek = true,
            hasClosingBoundary = true,
        )

        assertEquals(MetricCertainty.EXACT, cell.meritCertainty)
        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(MetricCertainty.EXACT, cell.attemptsCertainty)
    }

    @Test
    fun dailyTwoAttemptsRemainALowerBound() {
        val cell = gunsmokeCell(
            day = sunday,
            merit = 2_258,
            score = 21_090,
        )

        assertEquals(2, cell.attempts)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.attemptsCertainty)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.scoreCertainty)
        assertEquals("\u22652", WeeklyMetricPresentation.format(cell.attempts, cell.attemptsCertainty))
    }

    @Test
    fun weeklyTwentyOneAttemptsAreExactEvenWhenCellsArePartial() {
        val days = (0L..6L).map { offset ->
            gunsmokeCell(sunday.plusDays(offset), 3_342, 31_635)
        }
        val row = row(days)

        assertEquals(21, row.totalAttempts)
        assertEquals(MetricCertainty.EXACT, row.totalAttemptsCertainty)
        assertEquals("21", WeeklyMetricPresentation.format(row.totalAttempts, row.totalAttemptsCertainty))
    }

    @Test
    fun incompleteWeeklyAttemptSubtotalRemainsALowerBound() {
        val known = (0L..5L).map { offset ->
            gunsmokeCell(sunday.plusDays(offset), 3_342, 31_635)
        }
        val unknown = WeeklyReportBuilder.DayCell(
            gameDay = sunday.plusDays(6),
            meritDelta = null,
            scoreDelta = null,
            inference = null,
            evidence = DailyEvidence.NO_OBSERVATION,
            isGunsmokeWeek = true,
        )
        val row = row(known + unknown)

        assertEquals(18, row.totalAttempts)
        assertEquals(MetricCertainty.LOWER_BOUND, row.totalAttemptsCertainty)
        assertEquals("\u226518", WeeklyMetricPresentation.format(row.totalAttempts, row.totalAttemptsCertainty))
    }

    @Test
    fun positiveScoreProvesAttendanceEvenWithoutAUsableInference() {
        val cell = WeeklyReportBuilder.DayCell(
            gameDay = sunday,
            meritDelta = 1,
            scoreDelta = 1_000,
            inference = ActivityInference.infer(1, 1_000, gunsmokeActive = true),
            evidence = DailyEvidence.PARTIAL_DAY,
            isGunsmokeWeek = true,
        )

        assertTrue(cell.inference?.candidates?.isEmpty() == true)
        assertEquals(true, cell.attended)
        assertNull(cell.dailyPatrol)
    }

    @Test
    fun attributedStandardAndGunsmokeNinetyBothProvePatrol() {
        val standard = WeeklyReportBuilder.DayCell(
            gameDay = sunday,
            meritDelta = 90,
            scoreDelta = 0,
            inference = ActivityInference.infer(90, 0, gunsmokeActive = false),
            evidence = DailyEvidence.ATTRIBUTED,
        )
        val gunsmoke = WeeklyReportBuilder.DayCell(
            gameDay = sunday,
            meritDelta = 90,
            scoreDelta = 0,
            inference = ActivityInference.infer(90, 0, gunsmokeActive = true),
            evidence = DailyEvidence.ATTRIBUTED,
            isGunsmokeWeek = true,
        )

        assertEquals(true, standard.dailyPatrol)
        assertEquals(true, gunsmoke.dailyPatrol)
    }

    private fun gunsmokeCell(
        day: LocalDate,
        merit: Long,
        score: Long,
        hasDailyPatrolFact: Boolean = false,
        metricObservedAt: String? = null,
        inferenceMerit: Long = merit,
    ) = WeeklyReportBuilder.DayCell(
        gameDay = day,
        meritDelta = merit,
        scoreDelta = score,
        inference = ActivityInference.infer(inferenceMerit, score, gunsmokeActive = true),
        evidence = DailyEvidence.PARTIAL_DAY,
        hasDailyPatrolFact = hasDailyPatrolFact,
        isGunsmokeWeek = true,
        metricObservedAt = metricObservedAt?.let(java.time.Instant::parse),
    )

    private fun row(days: List<WeeklyReportBuilder.DayCell>) =
        WeeklyReportBuilder.MemberRow(
            uid = 1,
            name = "Member",
            days = days,
            totalMerit = days.sumOf { it.meritDelta ?: 0L },
            totalScore = days.sumOf { it.scoreDelta ?: 0L },
            isGunsmokeWeek = true,
        )
}
