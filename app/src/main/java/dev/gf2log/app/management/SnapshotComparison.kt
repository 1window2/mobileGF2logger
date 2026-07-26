package dev.gf2log.app.management

object SnapshotComparison {
    data class MeritChange(
        val uid: Long,
        val name: String,
        val weeklyMeritDelta: Long,
        val totalMeritDelta: Long,
        val totalScoreDelta: Long,
        val lastLoginChanged: Boolean,
    )

    data class Result(
        val older: PlatoonSnapshot,
        val newer: PlatoonSnapshot,
        val joined: List<SnapshotMember>,
        val left: List<SnapshotMember>,
        val changes: List<MeritChange>,
    )

    fun compare(older: PlatoonSnapshot, newer: PlatoonSnapshot): Result {
        require(!newer.capturedAt.isBefore(older.capturedAt))
        val olderByUid = older.members.associateBy(SnapshotMember::uid)
        val newerByUid = newer.members.associateBy(SnapshotMember::uid)
        return Result(
            older = older,
            newer = newer,
            joined = newerByUid.filterKeys { it !in olderByUid }.values.sortedBy { it.name },
            left = olderByUid.filterKeys { it !in newerByUid }.values.sortedBy { it.name },
            changes = newerByUid.values.mapNotNull { current ->
                val previous = olderByUid[current.uid] ?: return@mapNotNull null
                MeritChange(
                    uid = current.uid,
                    name = current.name,
                    weeklyMeritDelta = counterDelta(current.weeklyMerit, previous.weeklyMerit),
                    totalMeritDelta = counterDelta(current.totalMerit, previous.totalMerit),
                    totalScoreDelta = counterDelta(current.totalScore, previous.totalScore),
                    lastLoginChanged = current.lastLogin != previous.lastLogin,
                )
            }.filter {
                it.weeklyMeritDelta != 0L ||
                    it.totalMeritDelta != 0L ||
                    it.totalScoreDelta != 0L ||
                    it.lastLoginChanged
            }.sortedByDescending(MeritChange::weeklyMeritDelta),
        )
    }

    private fun counterDelta(current: Long, previous: Long): Long =
        if (current >= previous) current - previous else current
}
