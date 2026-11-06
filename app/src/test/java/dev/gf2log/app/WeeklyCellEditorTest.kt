package dev.gf2log.app

import dev.gf2log.app.WeeklyCellEditor.Field
import dev.gf2log.app.management.DailyEvidence
import dev.gf2log.app.management.MetricCertainty
import dev.gf2log.app.management.WeeklyCellOverride
import dev.gf2log.app.management.WeeklyOverrideEditing.CellEdit
import dev.gf2log.app.management.WeeklyReportBuilder.DayCell
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyCellEditorTest {
    private val day = LocalDate.of(2026, 10, 14)

    @Test
    fun `a minimum starts empty so typing the shown number confirms it`() {
        val cell = DayCell(day, meritDelta = 90, scoreDelta = null, inference = null, evidence = DailyEvidence.PARTIAL_DAY)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.meritCertainty)

        val start = WeeklyCellEditor.start(cell, null, Field.MERIT)

        assertEquals("", start.text)
        assertEquals("≥90", start.notation)
        assertEquals("90", WeeklyCellEditor.changed("90", start.text))
    }

    @Test
    fun `an unknown value starts empty with the question mark as its hint`() {
        val cell = DayCell(day, meritDelta = 40, scoreDelta = null, inference = null, evidence = DailyEvidence.SPARSE_INFERRED)
        assertEquals(MetricCertainty.UNKNOWN, cell.meritCertainty)

        val start = WeeklyCellEditor.start(cell, null, Field.MERIT)

        assertEquals(WeeklyCellEditor.Start("", "?"), start)
        assertEquals("40", WeeklyCellEditor.changed("40", start.text))
    }

    @Test
    fun `an exact value is prefilled and retyping it is no edit`() {
        val cell = DayCell(day, meritDelta = 90, scoreDelta = null, inference = null, evidence = DailyEvidence.ATTRIBUTED)

        val start = WeeklyCellEditor.start(cell, null, Field.MERIT)

        assertEquals(WeeklyCellEditor.Start("90"), start)
        assertNull(WeeklyCellEditor.changed("90", start.text))
        assertEquals("", WeeklyCellEditor.changed("", start.text))
    }

    @Test
    fun `a lower-bound score and attempt count start empty`() {
        val cell = DayCell(
            day,
            meritDelta = null,
            scoreDelta = 31_635,
            inference = null,
            evidence = DailyEvidence.PARTIAL_DAY,
            isGunsmokeWeek = true,
            solvedAttempts = 2,
            solvedScoreCertainty = MetricCertainty.LOWER_BOUND,
            solvedAttemptsCertainty = MetricCertainty.LOWER_BOUND,
        )

        assertEquals(WeeklyCellEditor.Start("", "≥31635"), WeeklyCellEditor.start(cell, null, Field.SCORE))
        assertEquals(WeeklyCellEditor.Start("", "≥2"), WeeklyCellEditor.start(cell, null, Field.ATTEMPTS))
    }

    @Test
    fun `a manual override is exact and prefilled`() {
        val override = WeeklyCellOverride(
            uid = 1,
            periodStart = day.minusDays(2),
            gameDay = day,
            meritDelta = 75,
            scoreDelta = null,
            attempts = null,
            attended = null,
            dailyPatrol = null,
        )
        val cell = DayCell(
            day,
            meritDelta = 75,
            scoreDelta = null,
            inference = null,
            evidence = DailyEvidence.MANUAL,
            manualOverride = override,
            isGunsmokeWeek = true,
        )

        assertEquals(WeeklyCellEditor.Start("75"), WeeklyCellEditor.start(cell, null, Field.MERIT))
        // A manual cell without attempts shows "-", as the table does.
        assertEquals(WeeklyCellEditor.Start("", "-"), WeeklyCellEditor.start(cell, null, Field.ATTEMPTS))
    }

    @Test
    fun `an unsaved draft value wins over the captured value`() {
        val cell = DayCell(day, meritDelta = 90, scoreDelta = null, inference = null, evidence = DailyEvidence.PARTIAL_DAY)
        val draft = CellEdit(uid = 1, gameDay = day, merit = "120")

        val start = WeeklyCellEditor.start(cell, draft, Field.MERIT)

        assertEquals(WeeklyCellEditor.Start("120"), start)
        assertNull(WeeklyCellEditor.changed("120", start.text))
    }
}
