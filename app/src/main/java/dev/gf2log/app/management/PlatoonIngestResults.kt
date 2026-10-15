package dev.gf2log.app.management

data class SnapshotIngestResult(
    val snapshotId: Long?,
    val duplicate: Boolean,
    val initialRoster: Boolean,
    val joined: Int,
    val rejoined: Int,
    val left: Int,
    val renamed: Int,
    /** True when the roster predated current data and was replayed into history instead. */
    val historical: Boolean = false,
) {
    companion object {
        fun duplicate() = SnapshotIngestResult(null, true, false, 0, 0, 0, 0)
        fun historical(snapshotId: Long) =
            SnapshotIngestResult(snapshotId, false, false, 0, 0, 0, 0, historical = true)
    }
}

data class ActivityIngestResult(
    val acceptedObservations: Int,
    val inserted: Int,
    val resolved: Int,
)

data class UpdatesIngestResult(
    val acceptedObservations: Int,
    val membershipEvents: Int,
    val patrolFacts: Int,
)
