package dev.gf2log.app.management

import dev.gf2log.protocol.CsvCell

object WeeklyReportCsv {
    const val HEADER =
        "periodStart,periodEnd,gunsmokeWeek,gameDay,uid,name,meritDelta,scoreDelta," +
            "attempts,meritCertainty,scoreCertainty,attemptsCertainty,attendance,dailyPatrol," +
            "precision,dailyEvidence,weeklyMeritTotal,weeklyMeritCertainty,weeklyScoreTotal," +
            "weeklyScoreCertainty,weeklyAttemptsTotal,weeklyAttemptsCertainty,weeklyLoginDays," +
            "weeklyLoginCertainty,weeklyPatrolDays,weeklyPatrolCertainty"

    fun format(report: WeeklyReportBuilder.Report): String = formatAll(listOf(report))

    fun formatAll(reports: List<WeeklyReportBuilder.Report>): String = buildString {
        appendLine(HEADER)
        reports.sortedBy { it.periodStart }.forEach { report ->
            report.members.forEach { member ->
                member.days.forEach { cell ->
                    appendLine(
                        listOf(
                            report.periodStart,
                            report.periodEnd,
                            report.isGunsmokeWeek,
                            cell.gameDay,
                            member.uid,
                            member.name,
                            cell.meritDelta,
                            cell.scoreDelta,
                            cell.attempts,
                            cell.meritCertainty,
                            cell.scoreCertainty,
                            cell.attemptsCertainty,
                            cell.attended,
                            cell.dailyPatrol,
                            cell.precision,
                            cell.evidence,
                            member.totalMerit,
                            member.totalMeritCertainty,
                            member.totalScore,
                            member.totalScoreCertainty,
                            member.totalAttempts,
                            member.totalAttemptsCertainty,
                            member.loginDays,
                            member.loginDaysCertainty,
                            member.patrolDays,
                            member.patrolDaysCertainty,
                        ).joinToString(",") { CsvCell.escape(it) },
                    )
                }
            }
        }
    }.trimEnd() + "\n"
}
