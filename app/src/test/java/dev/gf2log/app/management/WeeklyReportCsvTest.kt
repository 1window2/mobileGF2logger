package dev.gf2log.app.management

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportCsvTest {
    @Test
    fun exportsStableLongFormWithCsvEscaping() {
        val day = LocalDate.of(2026, 7, 20)
        val inference = ActivityInference.infer(90, 0, gunsmokeActive = false)
        val report = WeeklyReportBuilder.Report(
            periodStart = day,
            periodEnd = day.plusDays(6),
            isGunsmokeWeek = false,
            days = listOf(day),
            members = listOf(
                WeeklyReportBuilder.MemberRow(
                    uid = 1,
                    name = "Leader, \"One\"",
                    days = listOf(
                        WeeklyReportBuilder.DayCell(
                            day,
                            90,
                            0,
                            inference,
                            DailyEvidence.ATTRIBUTED,
                        ),
                    ),
                    totalMerit = 90,
                    totalScore = 0,
                ),
            ),
        )

        val lines = WeeklyReportCsv.format(report).lines()

        assertEquals(WeeklyReportCsv.HEADER, lines.first())
        assertTrue(lines[1].contains("\"Leader, \"\"One\"\"\""))
        assertTrue(
            lines[1].endsWith(
                ",0,EXACT,EXACT,EXACT,true,true,INFERRED,ATTRIBUTED," +
                    "90,EXACT,0,EXACT,0,EXACT,1,EXACT,1,EXACT",
            ),
        )
    }

    @Test
    fun exportsManualOverrideValuesAndPrecision() {
        val day = LocalDate.of(2026, 7, 20)
        val override = WeeklyCellOverride(
            uid = 1,
            periodStart = day,
            gameDay = day,
            meritDelta = 50,
            scoreDelta = null,
            attempts = null,
            attended = true,
            dailyPatrol = true,
        )
        val report = WeeklyReportBuilder.Report(
            periodStart = day,
            periodEnd = day.plusDays(6),
            isGunsmokeWeek = false,
            days = listOf(day),
            members = listOf(
                WeeklyReportBuilder.MemberRow(
                    uid = 1,
                    name = "One",
                    days = listOf(
                        WeeklyReportBuilder.DayCell(
                            day,
                            50,
                            null,
                            null,
                            DailyEvidence.MANUAL,
                            override,
                        ),
                    ),
                    totalMerit = 50,
                    totalScore = 0,
                ),
            ),
        )

        assertTrue(
            WeeklyReportCsv.format(report).lines()[1]
                .contains(",50,,,EXACT,UNKNOWN,UNKNOWN,true,true,MANUAL,MANUAL,"),
        )
    }

    @Test
    fun exportsLowerBoundAndUnknownCertaintyWithoutPresentationMarkers() {
        val day = LocalDate.of(2026, 7, 19)
        val lowerBound = WeeklyReportBuilder.DayCell(
            gameDay = day,
            meritDelta = 2_258,
            scoreDelta = 21_090,
            inference = ActivityInference.infer(2_258, 21_090, gunsmokeActive = true),
            evidence = DailyEvidence.PARTIAL_DAY,
            isGunsmokeWeek = true,
        )
        val unknown = WeeklyReportBuilder.DayCell(
            gameDay = day.plusDays(1),
            meritDelta = null,
            scoreDelta = null,
            inference = null,
            evidence = DailyEvidence.NO_OBSERVATION,
            isGunsmokeWeek = true,
        )
        val report = WeeklyReportBuilder.Report(
            periodStart = day,
            periodEnd = day.plusDays(6),
            isGunsmokeWeek = true,
            days = listOf(day, day.plusDays(1)),
            members = listOf(
                WeeklyReportBuilder.MemberRow(
                    uid = 1,
                    name = "Member",
                    days = listOf(lowerBound, unknown),
                    totalMerit = 2_258,
                    totalScore = 21_090,
                    isGunsmokeWeek = true,
                ),
            ),
        )

        val rows = WeeklyReportCsv.format(report).lines()
        val header = rows.first().split(',')
        val first = rows[1].split(',')
        val second = rows[2].split(',')

        assertEquals("LOWER_BOUND", first[header.indexOf("meritCertainty")])
        assertEquals("LOWER_BOUND", first[header.indexOf("scoreCertainty")])
        assertEquals("LOWER_BOUND", first[header.indexOf("attemptsCertainty")])
        assertEquals("UNKNOWN", second[header.indexOf("meritCertainty")])
        assertEquals("", second[header.indexOf("meritDelta")])
        assertTrue(rows.drop(1).none { it.contains('≥') || it.contains('?') })
    }

    @Test
    fun `exports multiple weeks chronologically with one header`() {
        val later = reportFor(LocalDate.of(2026, 7, 26), uid = 2)
        val earlier = reportFor(LocalDate.of(2026, 7, 19), uid = 1)

        val lines = WeeklyReportCsv.formatAll(listOf(later, earlier)).lines()

        assertEquals(1, lines.count { it == WeeklyReportCsv.HEADER })
        assertTrue(lines[1].startsWith("2026-07-19,2026-07-25"))
        assertTrue(lines[2].startsWith("2026-07-26,2026-08-01"))
    }

    @Test
    fun neutralizesFormulaLikeMemberNames() {
        val original = reportFor(LocalDate.of(2026, 7, 19), uid = 1)
        val report = original.copy(
            members = listOf(original.members.single().copy(name = "-2+3")),
        )

        assertTrue(WeeklyReportCsv.format(report).contains(",1,'-2+3,"))
    }

    private fun reportFor(day: LocalDate, uid: Long) = WeeklyReportBuilder.Report(
        periodStart = day,
        periodEnd = day.plusDays(6),
        isGunsmokeWeek = false,
        days = listOf(day),
        members = listOf(
            WeeklyReportBuilder.MemberRow(
                uid = uid,
                name = "Member $uid",
                days = listOf(
                    WeeklyReportBuilder.DayCell(
                        gameDay = day,
                        meritDelta = 50,
                        scoreDelta = 0,
                        inference = ActivityInference.infer(50, 0, gunsmokeActive = false),
                        evidence = DailyEvidence.ATTRIBUTED,
                    ),
                ),
                totalMerit = 50,
                totalScore = 0,
            ),
        ),
    )
}
