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
                    days = listOf(WeeklyReportBuilder.DayCell(day, 90, 0, inference)),
                    totalMerit = 90,
                    totalScore = 0,
                ),
            ),
        )

        val lines = WeeklyReportCsv.format(report).lines()

        assertEquals(WeeklyReportCsv.HEADER, lines.first())
        assertTrue(lines[1].contains("\"Leader, \"\"One\"\"\""))
        assertTrue(lines[1].endsWith(",0,true,true,INFERRED"))
    }
}
