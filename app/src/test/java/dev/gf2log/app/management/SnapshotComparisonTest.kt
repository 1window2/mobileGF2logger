package dev.gf2log.app.management

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotComparisonTest {
    @Test
    fun comparesRosterAndCounterChangesByUid() {
        val older = snapshot(
            "2026-07-20T00:00:00Z",
            member(1, "Left", 100, 500, 1000),
            member(2, "Stayed", 100, 500, 1000),
        )
        val newer = snapshot(
            "2026-07-21T00:00:00Z",
            member(2, "Stayed", 190, 590, 1090),
            member(3, "Joined", 0, 0, 0),
        )

        val result = SnapshotComparison.compare(older, newer)

        assertEquals(listOf(3L), result.joined.map { it.uid })
        assertEquals(listOf(1L), result.left.map { it.uid })
        assertEquals(90, result.changes.single().weeklyMeritDelta)
    }

    private fun snapshot(time: String, vararg members: SnapshotMember) =
        PlatoonSnapshot(0, Instant.parse(time), members.toList())

    private fun member(uid: Long, name: String, weekly: Long, total: Long, score: Long) =
        SnapshotMember(uid, name, 60, weekly, total, 0, score, 0)
}
