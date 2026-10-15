package dev.gf2log.app.management

import java.time.format.DateTimeFormatter

/** Builds the exact privacy-filtered content that may leave the Android sandbox as an image. */
object WeeklyShareProjection {
    data class Privacy(
        val includeMemberNames: Boolean = true,
        val includeUids: Boolean = false,
        val includePrivateNotes: Boolean = false,
    )

    /** Every word that appears in the image; the app passes its active language. */
    data class Labels(
        val standardTitle: String = "GF2logger \u2014 Standard week",
        val gunsmokeTitle: String = "GF2logger \u2014 Gunsmoke week",
        val member: String = "Member",
        val total: String = "Total",
        val privateNote: String = "Private note",
        val anonymousMember: (Int) -> String = { "Member $it" },
        val merit: String = "M",
        val score: String = "S",
        val attempts: String = "A",
        val login: String = "L",
        val patrol: String = "P",
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
        labels: Labels = Labels(),
    ): Document {
        val reportUids = report.members.mapTo(mutableSetOf()) { it.uid }
        require(displayedMembers.all { it.uid in reportUids })
        val headers = buildList {
            add(labels.member)
            addAll(report.days.map { it.format(DAY) })
            add(labels.total)
            if (privacy.includePrivateNotes) add(labels.privateNote)
        }
        val rows = displayedMembers.mapIndexed { index, member ->
            Row(
                member = memberLabel(member, index, privacy, labels),
                dailyCells = member.days.map { formatDaily(it, report.isGunsmokeWeek, labels) },
                total = formatTotal(member, report.isGunsmokeWeek, labels),
                privateNote = privateNotesByUid[member.uid]
                    ?.takeWhole(MAX_PRIVATE_NOTE_CHARS)
                    ?.takeIf { privacy.includePrivateNotes && it.isNotBlank() },
            )
        }
        return Document(
            title = if (report.isGunsmokeWeek) labels.gunsmokeTitle else labels.standardTitle,
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
        labels: Labels,
    ): String = when {
        privacy.includeMemberNames && privacy.includeUids ->
            member.name.takeWhole(MAX_MEMBER_LABEL_CHARS) + " (#" + member.uid + ")"
        privacy.includeMemberNames -> member.name.takeWhole(MAX_MEMBER_LABEL_CHARS)
        privacy.includeUids -> "#" + member.uid
        else -> labels.anonymousMember(index + 1)
    }

    private fun formatDaily(
        cell: WeeklyReportBuilder.DayCell,
        gunsmoke: Boolean,
        labels: Labels,
    ): String = buildList {
        add(labels.merit + " " + WeeklyMetricPresentation.format(cell.meritDelta, cell.meritCertainty))
        if (gunsmoke) {
            add(labels.score + " " + WeeklyMetricPresentation.format(cell.scoreDelta, cell.scoreCertainty))
            add(labels.attempts + " " + WeeklyMetricPresentation.format(cell.attempts, cell.attemptsCertainty))
        }
        add(labels.login + " " + activityMark(cell.attended))
        add(labels.patrol + " " + activityMark(cell.dailyPatrol))
    }.joinToString("\n")

    private fun formatTotal(
        member: WeeklyReportBuilder.MemberRow,
        gunsmoke: Boolean,
        labels: Labels,
    ): String = buildList {
        add(labels.merit + " " + WeeklyMetricPresentation.format(member.totalMerit, member.totalMeritCertainty))
        if (gunsmoke) {
            add(labels.score + " " + WeeklyMetricPresentation.format(member.totalScore, member.totalScoreCertainty))
            add(labels.attempts + " " + WeeklyMetricPresentation.format(member.totalAttempts, member.totalAttemptsCertainty))
        }
        add(labels.login + " " + WeeklyMetricPresentation.format(member.loginDays, member.loginDaysCertainty))
        add(labels.patrol + " " + WeeklyMetricPresentation.format(member.patrolDays, member.patrolDaysCertainty))
    }.joinToString("\n")

    private fun activityMark(value: Boolean?): String = when (value) {
        true -> "\u2713"
        false -> "\u2715"
        null -> "?"
    }

    private val DAY = DateTimeFormatter.ofPattern("MM/dd")
    /** At most [maximum] UTF-16 units, never ending on the high half of a surrogate pair. */
    private fun String.takeWhole(maximum: Int): String {
        if (length <= maximum) return this
        val end = if (Character.isHighSurrogate(this[maximum - 1])) maximum - 1 else maximum
        return substring(0, end)
    }

    private const val MAX_MEMBER_LABEL_CHARS = 128
    private const val MAX_PRIVATE_NOTE_CHARS = 512
}
