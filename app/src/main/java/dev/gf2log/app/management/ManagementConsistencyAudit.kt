package dev.gf2log.app.management

/** Deterministic post-condition checks for user-visible management projections. */
internal object ManagementConsistencyAudit {
    fun requireValid(revision: WeeklyTableRevision): WeeklyTableRevision = revision.also {
        val report = it.report
        require(report.periodEnd == report.periodStart.plusDays(6)) {
            "Weekly report period is not seven calendar days"
        }
        val expectedDays = List(7) { index -> report.periodStart.plusDays(index.toLong()) }
        require(report.days == expectedDays) { "Weekly report days are not contiguous" }
        require(report.members.map { member -> member.uid }.distinct().size == report.members.size) {
            "Weekly report contains duplicate member identities"
        }
        report.members.forEach { member ->
            require(member.uid > 0L) { "Weekly report contains an invalid member identity" }
            require(member.days.map { cell -> cell.gameDay } == expectedDays) {
                "Weekly member cells do not match the report period"
            }
            require(member.days.all { cell -> cell.isGunsmokeWeek == report.isGunsmokeWeek }) {
                "Weekly member cells disagree with the report mode"
            }
        }
        require(it.notes.all { note ->
            note.periodStart == report.periodStart && note.gameDay in expectedDays
        }) { "Weekly note lies outside the report period" }
        require(it.membershipEvents.all { event -> event.uid > 0L }) {
            "Weekly membership event contains an invalid member identity"
        }
        require(it.memberNamesByUid.keys.all { uid -> uid > 0L }) {
            "Weekly name context contains an invalid member identity"
        }
        require(it.memberPrivateNotesByUid.keys.all { uid ->
            report.members.any { member -> member.uid == uid }
        }) { "Weekly private-note context contains an unrelated member" }
    }
}
