package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyEvidenceAnalyzerTest {
    @Test
    fun explainsClosingBoundaryAndPartialLowerBound() {
        val day = LocalDate.of(2026, 8, 9)
        val exact = cell(day, merit = 40, DailyEvidence.ATTRIBUTED, closing = true)
        val partial = cell(day, merit = 40, DailyEvidence.PARTIAL_DAY)

        val exactExplanation = WeeklyEvidenceAnalyzer.explainDaily(
            exact,
            WeeklyEvidenceAnalyzer.Metric.MERIT,
        )
        assertEquals(MetricCertainty.EXACT, exactExplanation.certainty)
        assertTrue(
            WeeklyEvidenceAnalyzer.Fact.EXACT_CLOSING_BOUNDARY in exactExplanation.facts,
        )

        val partialExplanation = WeeklyEvidenceAnalyzer.explainDaily(
            partial,
            WeeklyEvidenceAnalyzer.Metric.MERIT,
        )
        assertEquals(MetricCertainty.LOWER_BOUND, partialExplanation.certainty)
        assertTrue(
            WeeklyEvidenceAnalyzer.Fact.CONFIRMED_LOWER_BOUND in partialExplanation.facts,
        )
    }

    @Test
    fun healthCountsEvidenceWithoutInventingUnknownAnswers() {
        val start = LocalDate.of(2026, 8, 3)
        val days = (0L..6L).map(start::plusDays)
        val cells = days.mapIndexed { index, day ->
            if (index == 0) {
                cell(day, merit = 40, DailyEvidence.PARTIAL_DAY)
            } else {
                cell(day, merit = null, DailyEvidence.NO_OBSERVATION)
            }
        }
        val report = WeeklyReportBuilder.Report(
            periodStart = start,
            periodEnd = start.plusDays(6),
            isGunsmokeWeek = false,
            days = days,
            members = listOf(
                WeeklyReportBuilder.MemberRow(1, "Member", cells, totalMerit = 40, totalScore = 0),
            ),
        )

        val health = WeeklyEvidenceAnalyzer.health(report)
        assertEquals(1, health.observedDays)
        assertEquals(0, health.exactMetrics)
        assertEquals(1, health.lowerBoundMetrics)
        assertEquals(20, health.unknownMetrics)
    }

    private fun cell(
        day: LocalDate,
        merit: Long?,
        evidence: DailyEvidence,
        closing: Boolean = false,
    ) = WeeklyReportBuilder.DayCell(
        gameDay = day,
        meritDelta = merit,
        scoreDelta = null,
        inference = null,
        evidence = evidence,
        hasClosingBoundary = closing,
    )
}
