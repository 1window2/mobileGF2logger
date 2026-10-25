package dev.gf2log.app.management

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotComparisonTest {
    private val zone = ZoneId.of("Asia/Seoul")

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

        val result = SnapshotComparison.compare(older, newer, zone)

        assertEquals(listOf(3L), result.joined.map { it.uid })
        assertEquals(listOf(1L), result.left.map { it.uid })
        assertEquals(90, result.changes.single().weeklyMeritDelta)
        assertEquals(MetricCertainty.EXACT, result.changes.single().weeklyMeritDeltaCertainty)
    }

    @Test
    fun aCounterThatComesBackHigherAfterItsResetIsALowerBound() {
        // Sunday noon to Monday noon crosses the Monday 05:00 weekly reset.
        val older = snapshot("2026-07-26T03:00:00Z", member(1, "Member", 540, 10_000, 0))
        val newer = snapshot("2026-07-27T03:00:00Z", member(1, "Member", 600, 10_690, 0))

        val change = SnapshotComparison.compare(older, newer, zone).changes.single()

        assertEquals(600, change.weeklyMeritDelta)
        assertEquals(MetricCertainty.LOWER_BOUND, change.weeklyMeritDeltaCertainty)
        assertEquals(690, change.totalMeritDelta)
    }

    @Test
    fun gunsmokeScoreRestartsWithEachEvent() {
        // The 2026-07-19 event ends; the next one starts on 2026-08-09 at 05:00.
        val older = snapshot("2026-07-25T03:00:00Z", member(1, "Member", 0, 0, 30_000))
        val newer = snapshot("2026-08-09T03:00:00Z", member(1, "Member", 0, 0, 40_000))

        val change = SnapshotComparison.compare(older, newer, zone).changes.single()

        assertEquals(40_000, change.totalScoreDelta)
        assertEquals(MetricCertainty.LOWER_BOUND, change.totalScoreDeltaCertainty)
    }

    private fun snapshot(time: String, vararg members: SnapshotMember) =
        PlatoonSnapshot(0, Instant.parse(time), members.toList())

    private fun member(uid: Long, name: String, weekly: Long, total: Long, score: Long) =
        SnapshotMember(uid, name, 60, weekly, total, 0, score, 0)
}
