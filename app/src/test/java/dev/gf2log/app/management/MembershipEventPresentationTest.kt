package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MembershipEventPresentationTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `date-only manual event shows unknown clock time`() {
        val event = event(
            id = 1,
            source = EvidenceSource.MANUAL,
            occurredAt = Instant.parse("2026-07-28T15:00:00Z"),
            eventDate = LocalDate.of(2026, 7, 29),
            timeKnown = false,
        )

        assertEquals("??:??", MembershipEventPresentation.timePrefix(event, zone))
    }

    @Test
    fun `exact event uses device-zone clock time`() {
        val event = event(
            id = 1,
            source = EvidenceSource.GAME_UPDATES,
            occurredAt = Instant.parse("2026-07-29T00:30:00Z"),
            eventDate = null,
            timeKnown = true,
        )

        assertEquals("09:30", MembershipEventPresentation.timePrefix(event, zone))
    }

    @Test
    fun `membership events use calendar date rather than five oclock game day`() {
        val event = event(
            id = 1,
            source = EvidenceSource.GAME_UPDATES,
            occurredAt = Instant.parse("2026-07-28T18:30:00Z"),
            eventDate = null,
            timeKnown = true,
        )

        assertEquals(
            LocalDate.of(2026, 7, 29),
            MembershipEventPresentation.calendarDate(event, zone),
        )
    }

    @Test
    fun `exact packet evidence replaces nearby snapshot presentation`() {
        val snapshot = event(
            id = 1,
            source = EvidenceSource.SNAPSHOT,
            occurredAt = Instant.parse("2026-07-28T00:00:00Z"),
            eventDate = null,
            timeKnown = true,
        )
        val exact = event(
            id = 2,
            source = EvidenceSource.GAME_UPDATES,
            occurredAt = Instant.parse("2026-07-29T00:00:00Z"),
            eventDate = null,
            timeKnown = true,
        )

        assertEquals(listOf(exact), MembershipEventPresentation.deduplicate(listOf(snapshot, exact)))
    }

    @Test
    fun `opposite boundary preserves two nearby same-side events`() {
        val firstJoin = event(
            id = 1,
            source = EvidenceSource.SNAPSHOT,
            occurredAt = Instant.parse("2026-07-27T00:00:00Z"),
            eventDate = null,
            timeKnown = true,
            type = MemberEventType.JOINED,
        )
        val withdrawal = event(
            id = 2,
            source = EvidenceSource.GAME_UPDATES,
            occurredAt = Instant.parse("2026-07-27T12:00:00Z"),
            eventDate = null,
            timeKnown = true,
            type = MemberEventType.LEFT,
        )
        val rejoin = event(
            id = 3,
            source = EvidenceSource.GAME_UPDATES,
            occurredAt = Instant.parse("2026-07-28T00:00:00Z"),
            eventDate = null,
            timeKnown = true,
            type = MemberEventType.REJOINED,
        )

        assertEquals(
            listOf(firstJoin, withdrawal, rejoin),
            MembershipEventPresentation.deduplicate(listOf(firstJoin, withdrawal, rejoin)),
        )
    }

    private fun event(
        id: Long,
        source: EvidenceSource,
        occurredAt: Instant?,
        eventDate: LocalDate?,
        timeKnown: Boolean,
        type: MemberEventType = MemberEventType.LEFT,
    ) = MemberEvent(
        id = id,
        uid = 42,
        type = type,
        occurredAt = occurredAt,
        eventDate = eventDate,
        timeKnown = timeKnown,
        observedAt = occurredAt ?: Instant.parse("2026-07-29T00:00:00Z"),
        precision = if (source == EvidenceSource.GAME_UPDATES) {
            EvidencePrecision.EXACT
        } else {
            EvidencePrecision.INFERRED
        },
        source = source,
        note = "member",
    )
}
