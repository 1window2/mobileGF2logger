package dev.gf2log.app.management

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SnapshotComparisonBaselineTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `an unchanged resend is skipped as the baseline`() {
        val a = snapshot(1, "2026-08-01T00:00:00Z", member(1, "One"), member(2, "Two"))
        val b = snapshot(2, "2026-08-01T06:00:00Z", member(1, "One"), member(3, "Three"))
        val resent = snapshot(3, "2026-08-01T06:00:05Z", member(1, "One"), member(3, "Three"))

        val (latest, baseline) = requireNotNull(
            SnapshotComparison.latestComparablePair(listOf(resent, b, a)),
        )

        assertSame(resent, latest)
        assertSame(a, baseline)
        val comparison = SnapshotComparison.compare(requireNotNull(baseline), latest, zone)
        assertEquals(listOf(3L), comparison.joined.map(SnapshotMember::uid))
        assertEquals(listOf(2L), comparison.left.map(SnapshotMember::uid))
    }

    @Test
    fun `identical rosters fall back to the oldest fetched one`() {
        val first = snapshot(1, "2026-08-01T00:00:00Z", member(1, "One"))
        val second = snapshot(2, "2026-08-01T01:00:00Z", member(1, "One"))
        val third = snapshot(3, "2026-08-01T02:00:00Z", member(1, "One"))

        val pair = requireNotNull(SnapshotComparison.latestComparablePair(listOf(third, second, first)))

        assertSame(third, pair.first)
        assertSame(first, pair.second)
    }

    @Test
    fun `a single roster has no baseline and no roster has no pair`() {
        val only = snapshot(1, "2026-08-01T00:00:00Z", member(1, "One"))

        assertNull(requireNotNull(SnapshotComparison.latestComparablePair(listOf(only))).second)
        assertNull(SnapshotComparison.latestComparablePair(emptyList()))
    }

    private fun snapshot(id: Long, time: String, vararg members: SnapshotMember) =
        PlatoonSnapshot(id = id, capturedAt = Instant.parse(time), members = members.toList(), sourceFile = "roster-$id.csv")

    private fun member(uid: Long, name: String) = SnapshotMember(
        uid = uid,
        name = name,
        level = 60,
        weeklyMerit = 100,
        totalMerit = 1_000,
        highScore = 0,
        totalScore = 0,
        lastLogin = 1_700_000_000,
    )
}
