package dev.gf2log.app.management

import java.time.format.DateTimeFormatter

/** Builds the exact privacy-filtered content that may leave the Android sandbox as an image. */
object WeeklyShareProjection {
    data class Privacy(
        val includeMemberNames: Boolean = true,
        val includeUids: Boolean = false,
        val includePrivateNotes: Boolean = false,
    )

    data class Document(
        val title: String,
        val subtitle: String,
        val headers: List<String>,
        val rows: List<Row>,
        val includeNotes: Boolean,
        val evidenceHealth: WeeklyEvidenceAnalyzer.Health,
    )

    data class Row(
        val member: String,
        val dailyCells: List<String>,
        val total: String,
        val privateNote: String?,
    )

    fun build(
        report: WeeklyReportBuilder.Report,
        displayedMembers: List<WeeklyReportBuilder.MemberRow>,
        privateNotesByUid: Map<Long, String>,
        privacy: Privacy,
    ): Document {
        val reportUids = report.members.mapTo(mutableSetOf()) { it.uid }
        require(displayedMembers.all { it.uid in reportUids })
        val headers = buildList {
            add("Member")
            addAll(report.days.map { it.format(DAY) })
            add("Total")
            if (privacy.includePrivateNotes) add("Private note")
        }
        val rows = displayedMembers.mapIndexed { index, member ->
            Row(
                member = memberLabel(member, index, privacy),
                dailyCells = member.days.map { formatDaily(it, report.isGunsmokeWeek) },
                total = formatTotal(member, report.isGunsmokeWeek),
                privateNote = privateNotesByUid[member.uid]
                    ?.take(MAX_PRIVATE_NOTE_CHARS)
                    ?.takeIf { privacy.includePrivateNotes && it.isNotBlank() },
            )
        }
        return Document(
            title = if (report.isGunsmokeWeek) "GF2logger \u2014 Gunsmoke week" else "GF2logger \u2014 Standard week",
            subtitle = report.periodStart.toString() + " \u2014 " + report.periodEnd.toString(),
            headers = headers,
            rows = rows,
            includeNotes = privacy.includePrivateNotes,
            evidenceHealth = WeeklyEvidenceAnalyzer.health(report),
        )
    }

    private fun memberLabel(
        member: WeeklyReportBuilder.MemberRow,
        index: Int,
        privacy: Privacy,
    ): String = when {
        privacy.includeMemberNames && privacy.includeUids ->
            member.name.take(MAX_MEMBER_LABEL_CHARS) + " (#" + member.uid + ")"
        privacy.includeMemberNames -> member.name.take(MAX_MEMBER_LABEL_CHARS)
        privacy.includeUids -> "#" + member.uid
        else -> "Member " + (index + 1)
    }

    private fun formatDaily(
        cell: WeeklyReportBuilder.DayCell,
        gunsmoke: Boolean,
    ): String = buildList {
        add("M " + WeeklyMetricPresentation.format(cell.meritDelta, cell.meritCertainty))
        if (gunsmoke) {
            add("S " + WeeklyMetricPresentation.format(cell.scoreDelta, cell.scoreCertainty))
            add("A " + WeeklyMetricPresentation.format(cell.attempts, cell.attemptsCertainty))
        }
        add("L " + activityMark(cell.attended))
        add("P " + activityMark(cell.dailyPatrol))
    }.joinToString("\n")

    private fun formatTotal(
        member: WeeklyReportBuilder.MemberRow,
        gunsmoke: Boolean,
    ): String = buildList {
        add("M " + WeeklyMetricPresentation.format(member.totalMerit, member.totalMeritCertainty))
        if (gunsmoke) {
            add("S " + WeeklyMetricPresentation.format(member.totalScore, member.totalScoreCertainty))
            add("A " + WeeklyMetricPresentation.format(member.totalAttempts, member.totalAttemptsCertainty))
        }
        add("L " + WeeklyMetricPresentation.format(member.loginDays, member.loginDaysCertainty))
        add("P " + WeeklyMetricPresentation.format(member.patrolDays, member.patrolDaysCertainty))
    }.joinToString("\n")

    private fun activityMark(value: Boolean?): String = when (value) {
        true -> "\u2713"
        false -> "\u2715"
        null -> "?"
    }

    private val DAY = DateTimeFormatter.ofPattern("MM/dd")
    private const val MAX_MEMBER_LABEL_CHARS = 128
    private const val MAX_PRIVATE_NOTE_CHARS = 512
}
