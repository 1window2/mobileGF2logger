package dev.gf2log.app.management

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotReconcilerTest {
    @Test
    fun historicalOrderingComparesCaptureTimeThenSourceFileName() {
        val latestAt = Instant.parse("2026-08-01T00:00:00Z")
        val latestFile = "gf2log_platoonmembers_b.csv"

        assertTrue(
            SnapshotReconciler.isHistorical(latestAt.minusMillis(1), "z.csv", latestAt, latestFile),
        )
        assertFalse(
            SnapshotReconciler.isHistorical(latestAt.plusMillis(1), "a.csv", latestAt, latestFile),
        )
        assertTrue(SnapshotReconciler.isHistorical(latestAt, latestFile, latestAt, latestFile))
        assertTrue(
            SnapshotReconciler.isHistorical(latestAt, "gf2log_platoonmembers_a.csv", latestAt, latestFile),
        )
        assertFalse(
            SnapshotReconciler.isHistorical(latestAt, "gf2log_platoonmembers_c.csv", latestAt, latestFile),
        )
        // A latest roster without a source file sorts before every named file at its instant.
        assertFalse(SnapshotReconciler.isHistorical(latestAt, "a.csv", latestAt, null))
    }

    @Test
    fun firstSnapshotIsAnInitialRosterWithoutJoinEvents() {
        val result = SnapshotReconciler.reconcile(
            known = emptyList(),
            incoming = listOf(member(1, "One"), member(2, "Two")),
            hasPriorSnapshot = false,
        )

        assertTrue(result.initialRoster)
        assertTrue(result.joined.isEmpty())
        assertTrue(result.left.isEmpty())
    }

    @Test
    fun detectsJoinLeaveRejoinAndRenameByUid() {
        val result = SnapshotReconciler.reconcile(
            known = listOf(
                known(1, "Leaving", active = true),
                known(2, "Old name", active = true),
                known(3, "Returning", active = false),
            ),
            incoming = listOf(
                member(2, "New name"),
                member(3, "Returning"),
                member(4, "Joining"),
            ),
            hasPriorSnapshot = true,
        )

        assertEquals(listOf(4L), result.joined.map { it.uid })
        assertEquals(listOf(3L), result.rejoined.map { it.uid })
        assertEquals(listOf(1L), result.left.map { it.uid })
        assertEquals(
            SnapshotReconciler.Rename(2, "Old name", "New name"),
            result.renamed.single(),
        )
    }

    @Test
    fun treatsAnUpdateOnlyPlaceholderAsJoinedWhenItFirstAppearsInRoster() {
        val result = SnapshotReconciler.reconcile(
            known = listOf(
                SnapshotReconciler.KnownMember(
                    uid = 4,
                    name = "Joining",
                    isActive = false,
                    hasPriorMembershipPeriod = false,
                ),
            ),
            incoming = listOf(member(4, "Joining")),
            hasPriorSnapshot = true,
        )

        assertEquals(listOf(4L), result.joined.map { it.uid })
        assertTrue(result.rejoined.isEmpty())
    }

    private fun member(uid: Long, name: String) =
        SnapshotMember(uid, name, 60, 0, 0, 0, 0, 0)

    private fun known(uid: Long, name: String, active: Boolean) =
        SnapshotReconciler.KnownMember(uid, name, active, hasPriorMembershipPeriod = true)
}
