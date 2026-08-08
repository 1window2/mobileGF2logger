package dev.gf2log.app.management

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberOrderSorterTest {
    @Test
    fun `sorts all supported fields in both directions`() {
        val early = member(uid = 1, name = "Early", joinedAt = "2026-01-01T00:00:00Z")
        val late = member(uid = 2, name = "Late", joinedAt = "2026-02-01T00:00:00Z")
        val snapshots = mapOf(
            1L to snapshot(uid = 1, weekly = 300, total = 900),
            2L to snapshot(uid = 2, weekly = 100, total = 1200),
        )

        assertEquals(
            listOf(1L, 2L),
            sortedUids(listOf(late, early), snapshots, MemberSortField.JOIN_DATE, true),
        )
        assertEquals(
            listOf(2L, 1L),
            sortedUids(listOf(early, late), snapshots, MemberSortField.JOIN_DATE, false),
        )
        assertEquals(
            listOf(2L, 1L),
            sortedUids(listOf(early, late), snapshots, MemberSortField.WEEKLY_MERIT, true),
        )
        assertEquals(
            listOf(1L, 2L),
            sortedUids(listOf(early, late), snapshots, MemberSortField.WEEKLY_MERIT, false),
        )
        assertEquals(
            listOf(1L, 2L),
            sortedUids(listOf(late, early), snapshots, MemberSortField.TOTAL_MERIT, true),
        )
        assertEquals(
            listOf(2L, 1L),
            sortedUids(listOf(early, late), snapshots, MemberSortField.TOTAL_MERIT, false),
        )
    }

    @Test
    fun `members missing from latest snapshot remain at the end`() {
        val known = member(uid = 1, name = "Known", joinedAt = "2026-01-01T00:00:00Z")
        val missing = member(uid = 2, name = "Missing", joinedAt = "2026-02-01T00:00:00Z")
        val snapshots = mapOf(1L to snapshot(uid = 1, weekly = 10, total = 10))

        assertEquals(
            listOf(1L, 2L),
            sortedUids(
                listOf(missing, known),
                snapshots,
                MemberSortField.WEEKLY_MERIT,
                ascending = false,
            ),
        )
    }

    private fun sortedUids(
        members: List<MemberStatus>,
        snapshots: Map<Long, SnapshotMember>,
        field: MemberSortField,
        ascending: Boolean,
    ) = MemberOrderSorter.sort(
        members,
        snapshots,
        field,
        if (ascending) MemberSortDirection.ASCENDING else MemberSortDirection.DESCENDING,
    ).map(MemberStatus::uid)

    private fun member(uid: Long, name: String, joinedAt: String) = MemberStatus(
        uid = uid,
        name = name,
        level = 60,
        isActive = true,
        firstSeenAt = Instant.parse(joinedAt),
        lastSeenAt = Instant.parse(joinedAt),
        note = "",
        membershipPeriods = emptyList(),
    )

    private fun snapshot(uid: Long, weekly: Long, total: Long) = SnapshotMember(
        uid = uid,
        name = "Member $uid",
        level = 60,
        weeklyMerit = weekly,
        totalMerit = total,
        highScore = 0,
        totalScore = 0,
        lastLogin = 0,
    )
}
