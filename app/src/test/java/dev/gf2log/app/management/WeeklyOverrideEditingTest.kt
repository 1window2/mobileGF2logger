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

    @Test
    fun anEditedActivityChoiceResolvesAContradictionWithAStoredOne() {
        // A completed Daily Patrol requires attendance, and storage rejects the contradiction.
        assertEquals(
            listOf(stored(attended = false, patrol = false)),
            valid(
                WeeklyOverrideEditing.merge(
                    week,
                    false,
                    listOf(stored(patrol = true)),
                    listOf(CellEdit(7, day, attended = Choice.NO)),
                ),
            ),
        )
        assertEquals(
            listOf(stored(attended = true, patrol = true)),
            valid(
                WeeklyOverrideEditing.merge(
                    week,
                    false,
                    listOf(stored(attended = false)),
                    listOf(CellEdit(7, day, dailyPatrol = Choice.YES)),
                ),
            ),
        )
    }

    @Test
    fun contradictoryActivityChoicesInOneEditAreInvalid() {
        assertEquals(
            Result.Invalid(7, day, Field.ACTIVITY),
            WeeklyOverrideEditing.merge(
                week,
                false,
                emptyList(),
                listOf(CellEdit(7, day, attended = Choice.NO, dailyPatrol = Choice.YES)),
            ),
        )
    }

    @Test
    fun everyValidMergeIsAcceptedByStorageRules() {
        val choices = listOf<Choice?>(null) + Choice.entries
        val storedValues = listOf<Boolean?>(null, true, false)
        storedValues.forEach { storedAttended ->
            storedValues.forEach { storedPatrol ->
                if (storedAttended == false && storedPatrol == true) return@forEach
                choices.forEach { attended ->
                    choices.forEach { patrol ->
                        val result = WeeklyOverrideEditing.merge(
                            week,
                            false,
                            listOf(stored(merit = 1, attended = storedAttended, patrol = storedPatrol)),
                            listOf(CellEdit(7, day, attended = attended, dailyPatrol = patrol)),
                        )
                        if (result is Result.Valid) {
                            assertTrue(result.overrides.none { it.attended == false && it.dailyPatrol == true })
                        }
                    }
                }
            }
        }
    }

    @Test
    fun manualCountersStayWithinTheDaysDomain() {
        fun merge(gunsmoke: Boolean, edit: CellEdit) =
            WeeklyOverrideEditing.merge(week, gunsmoke, emptyList(), listOf(edit))

        // A Standard day caps at its login and Daily Patrol.
        assertEquals(Result.Invalid(7, day, Field.MERIT), merge(false, CellEdit(7, day, merit = "91")))
        assertEquals(
            Result.Invalid(7, day, Field.MERIT),
            merge(false, CellEdit(7, day, merit = Long.MAX_VALUE.toString())),
        )
        assertEquals(listOf(stored(merit = 90)), valid(merge(false, CellEdit(7, day, merit = "90"))))
        assertTrue(valid(merge(false, CellEdit(7, day, merit = ""))).isEmpty())
        // A Gunsmoke day also holds its attempts, but no sum can overflow.
        assertEquals(listOf(stored(merit = 1_000)), valid(merge(true, CellEdit(7, day, merit = "1000"))))
        assertEquals(
            Result.Invalid(7, day, Field.MERIT),
            merge(true, CellEdit(7, day, merit = Long.MAX_VALUE.toString())),
        )
        assertEquals(
            Result.Invalid(7, day, Field.SCORE),
            merge(true, CellEdit(7, day, score = (WeeklyOverrideEditing.MAX_DAILY_SCORE + 1).toString())),
        )
        assertEquals(
            listOf(stored(score = WeeklyOverrideEditing.MAX_DAILY_SCORE)),
            valid(merge(true, CellEdit(7, day, score = WeeklyOverrideEditing.MAX_DAILY_SCORE.toString()))),
        )
    }

    @Test
    fun aNoThatRecordedActivityDisprovesIsAContradiction() {
        fun cell(patrolFact: Boolean = false, loginFact: Boolean = false, score: Long? = null) =
            WeeklyReportBuilder.DayCell(
                gameDay = day,
                meritDelta = null,
                scoreDelta = score,
                inference = null,
                evidence = DailyEvidence.PARTIAL_DAY,
                hasDailyPatrolFact = patrolFact,
                hasLoginFact = loginFact,
                isGunsmokeWeek = true,
            )
        val noPatrol = CellEdit(7, day, dailyPatrol = Choice.NO)
        val noLogin = CellEdit(7, day, attended = Choice.NO)

        assertTrue(WeeklyOverrideEditing.contradictsEvidence(cell(patrolFact = true), noPatrol))
        assertTrue(WeeklyOverrideEditing.contradictsEvidence(cell(patrolFact = true), noLogin))
        assertTrue(WeeklyOverrideEditing.contradictsEvidence(cell(loginFact = true), noLogin))
        assertTrue(WeeklyOverrideEditing.contradictsEvidence(cell(score = 1_000), noLogin))
        assertTrue(WeeklyOverrideEditing.contradictsEvidence(cell(), noLogin.copy(attempts = "1")))
        // Correcting the score and attempts to 0 in the same edit removes the evidence they supplied.
        assertTrue(
            !WeeklyOverrideEditing.contradictsEvidence(cell(score = 1_000), noLogin.copy(score = "0", attempts = "0")),
        )
        assertTrue(!WeeklyOverrideEditing.contradictsEvidence(cell(loginFact = true), noPatrol))
        assertTrue(!WeeklyOverrideEditing.contradictsEvidence(cell(), noLogin))
        assertTrue(!WeeklyOverrideEditing.contradictsEvidence(cell(patrolFact = true), CellEdit(7, day, attended = Choice.UNSET)))
    }

    @Test
    fun savingAnotherCellCleansAStoredValueOutsideTheDaysDomain() {
        val other = day.plusDays(1)
        val existing = listOf(
            stored(merit = Long.MAX_VALUE, attended = true),
            WeeklyCellOverride(7, week, other, 1_000, null, null, null, null),
        )

        val merged = valid(
            WeeklyOverrideEditing.merge(week, false, existing, listOf(CellEdit(8, day, merit = "50"))),
        )

        // The out-of-range merit is dropped, the rest of its cell kept, and a cell left empty goes.
        assertEquals(
            listOf(
                stored(attended = true),
                WeeklyCellOverride(8, week, day, 50, null, null, null, null),
            ),
            merged,
        )
    }
}
