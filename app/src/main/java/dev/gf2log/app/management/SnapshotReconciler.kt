package dev.gf2log.app.management

import java.time.Instant

object SnapshotReconciler {
    data class KnownMember(
        val uid: Long,
        val name: String,
        val isActive: Boolean,
        val hasPriorMembershipPeriod: Boolean,
    )

    data class Result(
        val initialRoster: Boolean,
        val joined: List<SnapshotMember>,
        val rejoined: List<SnapshotMember>,
        val left: List<KnownMember>,
        val renamed: List<Rename>,
    )

    data class Rename(
        val uid: Long,
        val oldName: String,
        val newName: String,
    )

    /**
     * Whether a roster file captured at [capturedAt] as [sourceFile] sorts at or before the
     * newest stored roster, so it must be replayed as history instead of becoming the current
     * roster. Equal capture instants are ordered by source file name.
     */
    fun isHistorical(
        capturedAt: Instant,
        sourceFile: String,
        latestCapturedAt: Instant,
        latestSourceFile: String?,
    ): Boolean = capturedAt.isBefore(latestCapturedAt) ||
        (capturedAt == latestCapturedAt && sourceFile <= latestSourceFile.orEmpty())

    fun reconcile(
        known: Collection<KnownMember>,
        incoming: Collection<SnapshotMember>,
        hasPriorSnapshot: Boolean,
    ): Result {
        val knownByUid = known.associateBy(KnownMember::uid)
        val incomingByUid = incoming.associateBy(SnapshotMember::uid)
        val joined = mutableListOf<SnapshotMember>()
        val rejoined = mutableListOf<SnapshotMember>()
        val renamed = mutableListOf<Rename>()

        if (hasPriorSnapshot) {
            incomingByUid.values.forEach { member ->
                val previous = knownByUid[member.uid]
                when {
                    previous == null -> joined += member
                    !previous.isActive && previous.hasPriorMembershipPeriod -> rejoined += member
                    !previous.isActive -> joined += member
                }
                if (previous != null && previous.name != member.name) {
                    renamed += Rename(member.uid, previous.name, member.name)
                }
            }
        }

        val left = if (hasPriorSnapshot) {
            knownByUid.values.filter { it.isActive && it.uid !in incomingByUid }
        } else {
            emptyList()
        }
        return Result(
            initialRoster = !hasPriorSnapshot,
            joined = joined,
            rejoined = rejoined,
            left = left,
            renamed = renamed,
        )
    }
}
