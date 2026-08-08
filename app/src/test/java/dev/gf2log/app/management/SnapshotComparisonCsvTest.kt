package dev.gf2log.app.management

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotComparisonCsvTest {
    @Test
    fun formatsJoinedDepartedAndChangedRows() {
        val older = snapshot(
            "2026-07-20T00:00:00Z",
            member(1, "Old", 100),
            member(2, "Member, Two", 100),
        )
        val newer = snapshot(
            "2026-07-21T00:00:00Z",
            member(2, "Member, Two", 190),
            member(3, "New", 50),
        )

        val csv = SnapshotComparisonCsv.format(SnapshotComparison.compare(older, newer))

        assertEquals(SnapshotComparisonCsv.HEADER, csv.lineSequence().first())
        assertTrue(csv.contains(",JOINED,3,New,"))
        assertTrue(csv.contains(",WITHDREW,1,Old,"))
        assertTrue(csv.contains(",CHANGED,2,\"Member, Two\",90,90,0,false"))
    }

    @Test
    fun neutralizesFormulaLikeMemberNames() {
        val older = snapshot("2026-07-20T00:00:00Z")
        val newer = snapshot("2026-07-21T00:00:00Z", member(3, "@SUM(A1)", 50))

        val csv = SnapshotComparisonCsv.format(SnapshotComparison.compare(older, newer))

        assertTrue(csv.contains(",JOINED,3,'@SUM(A1),"))
    }

    private fun snapshot(time: String, vararg members: SnapshotMember) =
        PlatoonSnapshot(0, Instant.parse(time), members.toList())

    private fun member(uid: Long, name: String, merit: Long) =
        SnapshotMember(uid, name, 60, merit, merit, 0, 0, 0)
}
