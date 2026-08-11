package dev.gf2log.app.management

import dev.gf2log.app.WeeklyPngPendingState

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WeeklyShareProjectionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun pendingWeeklyPngRestoresOnlyFromTheBoundedShareCache() {
        val cache = temporary.newFolder("cache")
        val shared = WeeklyPngPendingState.directory(cache).apply { mkdirs() }
        val published = java.io.File(shared, "GF2logger-week-20260809.png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val savedName = WeeklyPngPendingState.nameForState(cache, published)

        assertEquals(published.canonicalFile, WeeklyPngPendingState.restore(cache, savedName))
        assertEquals(null, WeeklyPngPendingState.restore(cache, "../outside.png"))
        assertEquals(null, WeeklyPngPendingState.restore(cache, "GF2logger-week-20260810.png"))
        val outside = temporary.newFile("GF2logger-week-20260811.png")
        assertEquals(null, WeeklyPngPendingState.nameForState(cache, outside))
    }

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
