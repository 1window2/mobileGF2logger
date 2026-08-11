package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyShareProjectionTest {
    @Test
    fun privateFieldsAreExcludedByDefaultAndCanBeIndividuallyEnabled() {
        val report = report()
        val defaultDocument = WeeklyShareProjection.build(
            report,
            report.members,
            privateNotesByUid = mapOf(123L to "private"),
            privacy = WeeklyShareProjection.Privacy(),
        )
        assertEquals("Alice", defaultDocument.rows.single().member)
        assertFalse(defaultDocument.includeNotes)
        assertEquals(null, defaultDocument.rows.single().privateNote)
        assertFalse(defaultDocument.rows.single().member.contains("123"))

        val privateDocument = WeeklyShareProjection.build(
            report,
            report.members,
            privateNotesByUid = mapOf(123L to "private"),
            privacy = WeeklyShareProjection.Privacy(
                includeMemberNames = false,
                includeUids = true,
                includePrivateNotes = true,
            ),
        )
        assertEquals("#123", privateDocument.rows.single().member)
        assertTrue(privateDocument.includeNotes)
        assertEquals("private", privateDocument.rows.single().privateNote)
    }

    @Test
    fun hidingNamesAndUidsUsesStableAnonymousRowLabels() {
        val report = report()
        val document = WeeklyShareProjection.build(
            report,
            report.members,
            emptyMap(),
            WeeklyShareProjection.Privacy(includeMemberNames = false),
        )
        assertEquals("Member 1", document.rows.single().member)
    }

    private fun report(): WeeklyReportBuilder.Report {
        val start = LocalDate.of(2026, 8, 10)
        val days = (0L..6L).map(start::plusDays)
        val cells = days.map { day ->
            WeeklyReportBuilder.DayCell(
                gameDay = day,
                meritDelta = null,
                scoreDelta = null,
                inference = null,
                evidence = DailyEvidence.NO_OBSERVATION,
            )
        }
        return WeeklyReportBuilder.Report(
            periodStart = start,
            periodEnd = start.plusDays(6),
            isGunsmokeWeek = false,
            days = days,
            members = listOf(
                WeeklyReportBuilder.MemberRow(
                    uid = 123,
                    name = "Alice",
                    days = cells,
                    totalMerit = 0,
                    totalScore = 0,
                ),
            ),
        )
    }
}
