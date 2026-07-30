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
        assertTrue(lines[1].endsWith(",0,true,true,INFERRED,ATTRIBUTED,90,0,0,1,1"))
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
            attended = false,
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
                .contains(",50,,,false,true,MANUAL,MANUAL,"),
        )
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
