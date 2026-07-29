package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotReconcilerTest {
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
                    hasPriorTenure = false,
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
        SnapshotReconciler.KnownMember(uid, name, active, hasPriorTenure = true)
}
