package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ManagementConsistencyAuditTest {
    @Test
    fun acceptsAContiguousWeeklyRevision() {
        val revision = revision()

        assertSame(revision, ManagementConsistencyAudit.requireValid(revision))
    }

    @Test
    fun rejectsCellsOutsideTheirWeeklyPeriod() {
        val revision = revision()
        val invalidMember = revision.report.members.single().copy(
            days = revision.report.members.single().days.mapIndexed { index, cell ->
                if (index == 3) cell.copy(gameDay = cell.gameDay.plusDays(1)) else cell
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            ManagementConsistencyAudit.requireValid(
                revision.copy(report = revision.report.copy(members = listOf(invalidMember))),
            )
        }
    }

    private fun revision(): WeeklyTableRevision {
        val start = LocalDate.of(2026, 8, 16)
        val days = List(7) { index -> start.plusDays(index.toLong()) }
        val member = WeeklyReportBuilder.MemberRow(
            uid = 1L,
            name = "Member",
            days = days.map { day ->
                WeeklyReportBuilder.DayCell(
                    gameDay = day,
                    meritDelta = null,
                    scoreDelta = null,
                    inference = null,
                    evidence = DailyEvidence.NO_OBSERVATION,
                    hasDailyPatrolFact = false,
                    hasLoginFact = false,
                    hasFinalGunsmokeScore = false,
                    isGunsmokeWeek = true,
                )
            },
            totalMerit = 0L,
            totalScore = 0L,
            isGunsmokeWeek = true,
        )
        return WeeklyTableRevision(
            report = WeeklyReportBuilder.Report(start, start.plusDays(6), true, days, listOf(member)),
            membershipEvents = emptyList(),
            notes = emptyList(),
            memberNamesByUid = mapOf(1L to "Member"),
            memberPrivateNotesByUid = emptyMap(),
        )
    }
}
