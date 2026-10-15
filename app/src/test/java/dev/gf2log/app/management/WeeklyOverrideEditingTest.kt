package dev.gf2log.app.management

import dev.gf2log.app.management.WeeklyOverrideEditing.CellEdit
import dev.gf2log.app.management.WeeklyOverrideEditing.Choice
import dev.gf2log.app.management.WeeklyOverrideEditing.Field
import dev.gf2log.app.management.WeeklyOverrideEditing.Result
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyOverrideEditingTest {
    private val week = LocalDate.of(2026, 10, 11)
    private val day = week.plusDays(2)

    private fun stored(
        merit: Long? = null,
        score: Long? = null,
        attempts: Int? = null,
        attended: Boolean? = null,
        patrol: Boolean? = null,
    ) = WeeklyCellOverride(7, week, day, merit, score, attempts, attended, patrol)

    private fun valid(result: Result) = (result as Result.Valid).overrides

    @Test
    fun untouchedStoredOverridesArePreserved() {
        val existing = listOf(stored(merit = 90, attended = true))
        assertEquals(existing, valid(WeeklyOverrideEditing.merge(week, false, existing, emptyList())))
    }

    @Test
    fun editedFieldsReplaceOnlyThemselves() {
        val result = WeeklyOverrideEditing.merge(
            week,
            isGunsmokeWeek = true,
            existing = listOf(stored(merit = 90, score = 100, attended = true)),
            edits = listOf(CellEdit(7, day, score = "250", dailyPatrol = Choice.NO)),
        )
        assertEquals(listOf(stored(merit = 90, score = 250, attended = true, patrol = false)), valid(result))
    }

    @Test
    fun clearingEveryFieldRemovesTheOverride() {
        val result = WeeklyOverrideEditing.merge(
            week,
            isGunsmokeWeek = false,
            existing = listOf(stored(merit = 40, attended = true)),
            edits = listOf(CellEdit(7, day, merit = " ", attended = Choice.UNSET)),
        )
        assertTrue(valid(result).isEmpty())
    }

    @Test
    fun standardWeeksNeverStoreGunsmokeFields() {
        val result = WeeklyOverrideEditing.merge(
            week,
            isGunsmokeWeek = false,
            existing = emptyList(),
            edits = listOf(CellEdit(7, day, merit = "50", score = "300", attempts = "2")),
        )
        assertEquals(listOf(stored(merit = 50)), valid(result))
    }

    @Test
    fun invalidValuesIdentifyTheirField() {
        assertEquals(
            Result.Invalid(7, day, Field.ATTEMPTS),
            WeeklyOverrideEditing.merge(week, true, emptyList(), listOf(CellEdit(7, day, attempts = "4"))),
        )
        assertEquals(
            Result.Invalid(7, day, Field.MERIT),
            WeeklyOverrideEditing.merge(week, true, emptyList(), listOf(CellEdit(7, day, merit = "-1"))),
        )
    }

    @Test
    fun emptyEditsDoNotCreateOverrides() {
        assertTrue(valid(WeeklyOverrideEditing.merge(week, true, emptyList(), listOf(CellEdit(7, day)))).isEmpty())
    }
}
