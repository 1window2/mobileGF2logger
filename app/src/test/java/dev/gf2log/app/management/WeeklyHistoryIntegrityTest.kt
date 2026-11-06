package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyHistoryIntegrityTest {
    @Test
    fun aRevisionReferencesAMemberThroughAnyPartThatCanRenderThem() {
        val empty = revision()

        assertFalse(WeeklyHistoryIntegrity.references(empty, UID))
        assertTrue(WeeklyHistoryIntegrity.references(empty.copy(report = reportWith(UID)), UID))
        assertTrue(WeeklyHistoryIntegrity.references(empty.copy(membershipEvents = listOf(event(UID))), UID))
        assertTrue(WeeklyHistoryIntegrity.references(empty.copy(memberNamesByUid = mapOf(UID to "Gone")), UID))
        assertTrue(
            WeeklyHistoryIntegrity.references(empty.copy(memberPrivateNotesByUid = mapOf(UID to "note")), UID),
        )
    }

    @Test
    fun otherMembersDoNotMakeARevisionReferenceTheDeletedOne() {
        val other = revision().copy(
            report = reportWith(OTHER_UID),
            membershipEvents = listOf(event(OTHER_UID)),
            memberNamesByUid = mapOf(OTHER_UID to "Stays"),
            memberPrivateNotesByUid = mapOf(OTHER_UID to "note"),
        )

        assertFalse(WeeklyHistoryIntegrity.references(other, UID))
    }

    @Test
    fun payloadChunksCoverEveryByteExactlyOnce() {
        assertEquals(listOf(1L..1L), WeeklyHistoryIntegrity.chunkRanges(1, chunkBytes = 4))
        assertEquals(listOf(1L..4L), WeeklyHistoryIntegrity.chunkRanges(4, chunkBytes = 4))
        assertEquals(listOf(1L..4L, 5L..8L, 9L..9L), WeeklyHistoryIntegrity.chunkRanges(9, chunkBytes = 4))
        val large = WeeklyHistoryIntegrity.chunkRanges(WeeklyReportHistoryCodec.MAX_PAYLOAD_BYTES.toLong())
        assertEquals(WeeklyReportHistoryCodec.MAX_PAYLOAD_BYTES.toLong(), large.sumOf { it.last - it.first + 1 })
        assertTrue(large.all { it.last - it.first + 1 <= 256 * 1024 })
    }

    private fun revision() = WeeklyTableRevision(
        report = reportWith(),
        membershipEvents = emptyList(),
        notes = emptyList(),
        memberNamesByUid = emptyMap(),
        memberPrivateNotesByUid = emptyMap(),
    )

    private fun reportWith(vararg uids: Long) = WeeklyReportBuilder.Report(
        periodStart = WEEK,
        periodEnd = WEEK.plusDays(6),
        isGunsmokeWeek = false,
        days = List(7) { WEEK.plusDays(it.toLong()) },
        members = uids.map { uid ->
            WeeklyReportBuilder.MemberRow(
                uid = uid,
                name = "Member $uid",
                days = emptyList(),
                totalMerit = 0,
                totalScore = 0,
            )
        },
    )

    private fun event(uid: Long) = MemberEvent(
        id = 1,
        uid = uid,
        type = MemberEventType.JOINED,
        occurredAt = Instant.parse("2026-08-03T12:00:00Z"),
        eventDate = null,
        timeKnown = true,
        observedAt = Instant.parse("2026-08-03T12:00:00Z"),
        precision = EvidencePrecision.EXACT,
        source = EvidenceSource.GAME_UPDATES,
        note = "",
    )

    private companion object {
        const val UID = 4242L
        const val OTHER_UID = 4343L
        val WEEK: LocalDate = LocalDate.of(2026, 8, 2)
    }
}
