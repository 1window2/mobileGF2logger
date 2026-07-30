package dev.gf2log.app.management

object WeeklyReportCsv {
    const val HEADER =
        "periodStart,periodEnd,gunsmokeWeek,gameDay,uid,name,meritDelta,scoreDelta," +
            "attempts,attendance,dailyPatrol,precision,dailyEvidence,weeklyMeritTotal,weeklyScoreTotal," +
            "weeklyAttemptsTotal,weeklyLoginDays,weeklyPatrolDays"

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
                            cell.attended,
                            cell.dailyPatrol,
                            cell.precision,
                            cell.evidence,
                            member.totalMerit,
                            member.totalScore,
                            member.totalAttempts,
                            member.loginDays,
                            member.patrolDays,
                        ).joinToString(",", transform = ::escape),
                    )
                }
            }
        }
    }.trimEnd() + "\n"

    private fun escape(value: Any?): String {
        val text = value?.toString().orEmpty()
        if (text.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return text
        return "\"${text.replace("\"", "\"\"")}\""
    }
}
