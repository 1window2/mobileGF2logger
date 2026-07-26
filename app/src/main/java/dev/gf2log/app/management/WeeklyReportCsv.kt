package dev.gf2log.app.management

object WeeklyReportCsv {
    const val HEADER =
        "periodStart,periodEnd,gunsmokeWeek,gameDay,uid,name,meritDelta,scoreDelta," +
            "attempts,attendance,dailyPatrol,precision"

    fun format(report: WeeklyReportBuilder.Report): String = buildString {
        appendLine(HEADER)
        report.members.forEach { member ->
            member.days.forEach { cell ->
                val selected = cell.inference?.selected
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
                        selected?.attempts,
                        selected?.attended,
                        selected?.dailyPatrol,
                        cell.inference?.precision,
                    ).joinToString(",", transform = ::escape),
                )
            }
        }
    }.trimEnd() + "\n"

    private fun escape(value: Any?): String {
        val text = value?.toString().orEmpty()
        if (text.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return text
        return "\"${text.replace("\"", "\"\"")}\""
    }
}
