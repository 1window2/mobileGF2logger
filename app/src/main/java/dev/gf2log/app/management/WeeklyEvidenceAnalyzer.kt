package dev.gf2log.app.management

import java.time.LocalDate

/** Builds user-facing evidence explanations without depending on Android UI classes. */
object WeeklyEvidenceAnalyzer {
    enum class Metric {
        MERIT,
        SCORE,
        ATTEMPTS,
        LOGIN,
        DAILY_PATROL,
    }

    enum class Fact {
        MANUAL_OVERRIDE,
        EXACT_CLOSING_BOUNDARY,
        FINAL_GUNSMOKE_SCORE,
        EXACT_DAILY_PATROL_EVENT,
        LOGIN_TIMESTAMP,
        SOLVER_CONSENSUS,
        DAILY_CAP_REACHED,
        WEEKLY_CAP_REACHED,
        ALL_DAYS_EXACT,
        CONFIRMED_LOWER_BOUND,
        NO_OBSERVATION,
        INCOMPLETE_BOUNDARY,
        PARTIAL_DAY,
        SPARSE_INFERENCE,
        AMBIGUOUS_ALLOCATION,
    }

    data class Explanation(
        val metric: Metric,
        val gameDay: LocalDate?,
        val certainty: MetricCertainty,
        val facts: List<Fact>,
    )

    data class Health(
        val observedDays: Int,
        val totalDays: Int,
        val exactMetrics: Int,
        val lowerBoundMetrics: Int,
        val unknownMetrics: Int,
        val directLoginDays: Int,
        val directPatrolDays: Int,
        val closingBoundaries: Int,
    ) {
        val isComplete: Boolean
            get() = unknownMetrics == 0 && lowerBoundMetrics == 0
    }

    fun explainDaily(
        cell: WeeklyReportBuilder.DayCell,
        metric: Metric,
    ): Explanation {
        val certainty = dailyCertainty(cell, metric)
        val facts = linkedSetOf<Fact>()
        if (cell.manualOverride?.hasMetric(metric) == true) facts += Fact.MANUAL_OVERRIDE
        if (cell.hasClosingBoundary) facts += Fact.EXACT_CLOSING_BOUNDARY
        if (cell.hasFinalGunsmokeScore && metric == Metric.SCORE) facts += Fact.FINAL_GUNSMOKE_SCORE
        if (cell.hasDailyPatrolFact && metric in setOf(Metric.MERIT, Metric.LOGIN, Metric.DAILY_PATROL)) {
            facts += Fact.EXACT_DAILY_PATROL_EVENT
        }
        if (cell.hasLoginFact && metric in setOf(Metric.MERIT, Metric.LOGIN)) {
            facts += Fact.LOGIN_TIMESTAMP
        }
        if (cell.hasSolverResult(metric)) facts += Fact.SOLVER_CONSENSUS
        if (
            metric == Metric.ATTEMPTS &&
            cell.attempts == ActivityInference.MAX_DAILY_ATTEMPTS &&
            cell.attemptsCertainty == MetricCertainty.EXACT
        ) {
            facts += Fact.DAILY_CAP_REACHED
        }
        if (certainty == MetricCertainty.LOWER_BOUND) facts += Fact.CONFIRMED_LOWER_BOUND
        when (cell.evidence) {
            DailyEvidence.NO_OBSERVATION -> facts += Fact.NO_OBSERVATION
            DailyEvidence.INCOMPLETE_BOUNDARY -> facts += Fact.INCOMPLETE_BOUNDARY
            DailyEvidence.PARTIAL_DAY -> facts += Fact.PARTIAL_DAY
            DailyEvidence.SPARSE_INFERRED -> facts += Fact.SPARSE_INFERENCE
            DailyEvidence.ATTRIBUTED, DailyEvidence.MANUAL -> Unit
        }
        if (certainty == MetricCertainty.UNKNOWN && cell.inference?.precision == EvidencePrecision.AMBIGUOUS) {
            facts += Fact.AMBIGUOUS_ALLOCATION
        }
        return Explanation(metric, cell.gameDay, certainty, facts.toList())
    }

    fun explainTotal(
        member: WeeklyReportBuilder.MemberRow,
        metric: Metric,
    ): Explanation {
        val certainty = totalCertainty(member, metric)
        val facts = linkedSetOf<Fact>()
        if (member.days.any { it.manualOverride?.hasMetric(metric) == true }) {
            facts += Fact.MANUAL_OVERRIDE
        }
        if (certainty == MetricCertainty.EXACT && member.days.all { dailyCertainty(it, metric) == MetricCertainty.EXACT }) {
            facts += Fact.ALL_DAYS_EXACT
        }
        if (
            metric == Metric.ATTEMPTS &&
            member.totalAttempts == ActivityInference.MAX_WEEKLY_ATTEMPTS &&
            member.totalAttemptsCertainty == MetricCertainty.EXACT
        ) {
            facts += Fact.WEEKLY_CAP_REACHED
        }
        if (member.resolvedGunsmokeTotals != null && certainty == MetricCertainty.EXACT) {
            facts += Fact.SOLVER_CONSENSUS
        }
        if (member.hasFinalGunsmokeScore && metric == Metric.SCORE) facts += Fact.FINAL_GUNSMOKE_SCORE
        if (certainty == MetricCertainty.LOWER_BOUND) facts += Fact.CONFIRMED_LOWER_BOUND
        if (certainty == MetricCertainty.UNKNOWN) {
            facts += if (member.days.any { it.evidence == DailyEvidence.SPARSE_INFERRED }) {
                Fact.SPARSE_INFERENCE
            } else {
                Fact.NO_OBSERVATION
            }
        }
        return Explanation(metric, gameDay = null, certainty, facts.toList())
    }

    fun health(report: WeeklyReportBuilder.Report): Health {
        val cells = report.members.flatMap(WeeklyReportBuilder.MemberRow::days)
        val certainties = cells.flatMap { cell ->
            buildList {
                add(cell.meritCertainty)
                add(cell.attended.toCertainty(cell.observed))
                add(cell.dailyPatrol.toCertainty(cell.observed))
                if (report.isGunsmokeWeek) {
                    add(cell.scoreCertainty)
                    add(cell.attemptsCertainty)
                }
            }
        }
        return Health(
            observedDays = report.days.count { day -> cells.any { it.gameDay == day && it.observed } },
            totalDays = report.days.size,
            exactMetrics = certainties.count { it == MetricCertainty.EXACT },
            lowerBoundMetrics = certainties.count { it == MetricCertainty.LOWER_BOUND },
            unknownMetrics = certainties.count { it == MetricCertainty.UNKNOWN },
            directLoginDays = cells.filter(WeeklyReportBuilder.DayCell::hasLoginFact)
                .map(WeeklyReportBuilder.DayCell::gameDay)
                .distinct()
                .size,
            directPatrolDays = cells.filter(WeeklyReportBuilder.DayCell::hasDailyPatrolFact)
                .map(WeeklyReportBuilder.DayCell::gameDay)
                .distinct()
                .size,
            closingBoundaries = report.days.count { day ->
                cells.any { it.gameDay == day && it.hasClosingBoundary }
            },
        )
    }

    private fun dailyCertainty(
        cell: WeeklyReportBuilder.DayCell,
        metric: Metric,
    ): MetricCertainty = when (metric) {
        Metric.MERIT -> cell.meritCertainty
        Metric.SCORE -> cell.scoreCertainty
        Metric.ATTEMPTS -> cell.attemptsCertainty
        Metric.LOGIN -> cell.attended.toCertainty(cell.observed)
        Metric.DAILY_PATROL -> cell.dailyPatrol.toCertainty(cell.observed)
    }

    private fun totalCertainty(
        member: WeeklyReportBuilder.MemberRow,
        metric: Metric,
    ): MetricCertainty = when (metric) {
        Metric.MERIT -> member.totalMeritCertainty
        Metric.SCORE -> member.totalScoreCertainty
        Metric.ATTEMPTS -> member.totalAttemptsCertainty
        Metric.LOGIN -> member.loginDaysCertainty
        Metric.DAILY_PATROL -> member.patrolDaysCertainty
    }

    private fun WeeklyReportBuilder.DayCell.hasSolverResult(metric: Metric): Boolean = when (metric) {
        Metric.MERIT -> solvedMeritCertainty != null
        Metric.SCORE -> solvedScoreCertainty != null
        Metric.ATTEMPTS -> solvedAttemptsCertainty != null
        Metric.LOGIN -> solvedAttended != null
        Metric.DAILY_PATROL -> solvedDailyPatrol != null
    }

    private fun WeeklyCellOverride.hasMetric(metric: Metric): Boolean = when (metric) {
        Metric.MERIT -> meritDelta != null
        Metric.SCORE -> scoreDelta != null
        Metric.ATTEMPTS -> attempts != null
        Metric.LOGIN -> attended != null
        Metric.DAILY_PATROL -> dailyPatrol != null
    }

    private fun Boolean?.toCertainty(observed: Boolean): MetricCertainty = when {
        this != null -> MetricCertainty.EXACT
        observed -> MetricCertainty.UNKNOWN
        else -> MetricCertainty.UNKNOWN
    }
}
