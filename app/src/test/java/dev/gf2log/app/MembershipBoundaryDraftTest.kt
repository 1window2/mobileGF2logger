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

class MembershipBoundaryDraftTest {
    @Test
    fun `untouched boundary preserves exact instant`() {
        val instant = Instant.parse("2026-07-29T10:14:45.123456789Z")
        val draft = draft(instant, LocalDate.of(2026, 7, 29))

        assertEquals(instant, draft.boundary?.instant)
        assertTrue(draft.boundary!!.timeKnown)
    }

    @Test
    fun `untouched DST overlap preserves the original offset instant`() {
        val laterOffset = Instant.parse("2026-11-01T06:30:45Z")
        val draft = MembershipBoundaryDraft(
            initialValue = laterOffset,
            initialDate = LocalDate.of(2026, 11, 1),
            initialTimeKnown = true,
            zone = ZoneId.of("America/New_York"),
        )

        assertEquals(laterOffset, draft.boundary?.instant)
    }

    @Test
    fun `selecting the same date leaves original instant untouched`() {
        val instant = Instant.parse("2026-07-29T10:14:45Z")
        val date = LocalDate.of(2026, 7, 29)
        val draft = draft(instant, date)

        draft.selectDate(date)

        assertEquals(instant, draft.boundary?.instant)
    }

    @Test
    fun `explicit minute selection intentionally clears sub-minute precision`() {
        val draft = draft(
            Instant.parse("2026-07-29T10:14:45.123Z"),
            LocalDate.of(2026, 7, 29),
        )

        draft.selectTime(LocalTime.of(20, 5))

        assertEquals(
            Instant.parse("2026-07-29T11:05:00Z"),
            draft.boundary?.instant,
        )
    }

    @Test
    fun `clearing time preserves date and marks time unknown`() {
        val date = LocalDate.of(2026, 7, 29)
        val draft = draft(Instant.parse("2026-07-29T10:14:45Z"), date)

        draft.clearTime()

        assertEquals(date, draft.boundary?.date)
        assertFalse(draft.boundary!!.timeKnown)
        assertEquals(Instant.parse("2026-07-28T15:00:00Z"), draft.boundary?.instant)
    }

    @Test
    fun `clearing an unset time is stable`() {
        val date = LocalDate.of(2026, 7, 29)
        val midnight = Instant.parse("2026-07-28T15:00:00Z")
        val draft = MembershipBoundaryDraft(
            initialValue = midnight,
            initialDate = date,
            initialTimeKnown = false,
            zone = ZoneId.of("Asia/Seoul"),
        )

        draft.clearTime()

        assertEquals(midnight, draft.boundary?.instant)
        assertFalse(draft.boundary!!.timeKnown)
        assertNull(draft.time)
    }

    private fun draft(instant: Instant, date: LocalDate) = MembershipBoundaryDraft(
        initialValue = instant,
        initialDate = date,
        initialTimeKnown = true,
        zone = ZoneId.of("Asia/Seoul"),
    )
}
