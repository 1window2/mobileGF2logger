package dev.gf2log.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipBoundaryDraftRestoreTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `an untouched value is not saved and stays exact`() {
        val instant = Instant.parse("2026-07-29T10:14:45.123456789Z")
        val draft = MembershipBoundaryDraft(instant, LocalDate.of(2026, 7, 29), true, zone)

        assertFalse(draft.changed)
        assertEquals(instant, draft.boundary?.instant)
    }

    @Test
    fun `a picked value restored into a fresh draft gives the same boundary`() {
        val typed = MembershipBoundaryDraft(null, null, false, zone).apply {
            selectDate(LocalDate.of(2026, 11, 6))
            selectTime(LocalTime.of(12, 34))
        }
        val recreated = MembershipBoundaryDraft(null, null, false, zone)

        recreated.restoreChanged(typed.date, typed.time)

        assertTrue(recreated.changed)
        assertEquals(typed.boundary, recreated.boundary)
    }

    @Test
    fun `a cleared original stays cleared after recreation`() {
        val original = Instant.parse("2026-07-29T10:14:45Z")
        val recreated = MembershipBoundaryDraft(original, null, true, zone)

        recreated.restoreChanged(date = null, time = null)

        assertNull(recreated.boundary)
        assertNull(recreated.date)
    }

    @Test
    fun `a time without a date is not restored`() {
        val recreated = MembershipBoundaryDraft(null, null, false, zone)

        recreated.restoreChanged(date = null, time = LocalTime.NOON)

        assertNull(recreated.time)
    }
}
