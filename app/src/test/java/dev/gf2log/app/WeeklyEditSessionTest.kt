package dev.gf2log.app

import dev.gf2log.app.management.WeeklyOverrideEditing.CellEdit
import dev.gf2log.app.management.WeeklyOverrideEditing.Choice
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyEditSessionTest {
    private val week = LocalDate.of(2026, 10, 12)
    private val nextWeek = week.plusDays(7)

    @Test
    fun `edits are ignored outside edit mode`() {
        val session = WeeklyEditSession()

        assertFalse(session.put(CellEdit(uid = 1, gameDay = week, merit = "90")))
        assertTrue(session.isEmpty())
    }

    @Test
    fun `an empty edit restores the cell instead of being kept`() {
        val session = WeeklyEditSession().apply { start(week) }
        session.put(CellEdit(uid = 1, gameDay = week, merit = "90"))
        session.put(CellEdit(uid = 1, gameDay = week))

        assertNull(session[1, week])
        assertEquals(0, session.size)
    }

    @Test
    fun `a later edit of the same cell replaces the earlier one`() {
        val session = WeeklyEditSession().apply { start(week) }
        session.put(CellEdit(uid = 1, gameDay = week, merit = "50"))
        session.put(CellEdit(uid = 1, gameDay = week, merit = "90", attended = Choice.YES))

        assertEquals(1, session.size)
        assertEquals("90", session[1, week]?.merit)
    }

    @Test
    fun `member edits are keyed by game day`() {
        val session = WeeklyEditSession().apply { start(week) }
        session.put(CellEdit(uid = 1, gameDay = week, merit = "10"))
        session.put(CellEdit(uid = 1, gameDay = week.plusDays(1), merit = "20"))
        session.put(CellEdit(uid = 2, gameDay = week, merit = "30"))

        assertEquals(setOf(week, week.plusDays(1)), session.forMember(1).keys)
        assertEquals("30", session.forMember(2).getValue(week).merit)
    }

    @Test
    fun `rendering another week discards the draft`() {
        val session = WeeklyEditSession().apply { start(week) }
        session.put(CellEdit(uid = 1, gameDay = week, merit = "90"))

        session.retainFor(week)
        assertTrue(session.isEditing(week))
        assertEquals(1, session.size)

        session.retainFor(nextWeek)
        assertNull(session.period)
        assertTrue(session.isEmpty())
    }

    @Test
    fun `starting again clears the previous draft`() {
        val session = WeeklyEditSession().apply { start(week) }
        session.put(CellEdit(uid = 1, gameDay = week, merit = "90"))

        session.start(week)

        assertTrue(session.isEditing(week))
        assertTrue(session.isEmpty())
    }
}
