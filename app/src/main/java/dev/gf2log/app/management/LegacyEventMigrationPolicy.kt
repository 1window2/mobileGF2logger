package dev.gf2log.app.management

internal data class LegacyEventCandidate(
    val id: Long,
    val type: MemberEventType,
    val occurredAt: Long?,
    val observedAt: Long,
    val source: EvidenceSource,
)

internal object LegacyEventMigrationPolicy {
    fun selectCandidate(
        expectedType: MemberEventType,
        boundaryAt: Long,
        candidates: List<LegacyEventCandidate>,
    ): Long? {
        val compatibleTypes = compatibleTypes(expectedType)
        return candidates
            .asSequence()
            .filter { candidate ->
                candidate.occurredAt == boundaryAt ||
                    (
                        candidate.occurredAt == null &&
                            candidate.source in SNAPSHOT_SOURCES &&
                            candidate.observedAt == boundaryAt
                        )
            }
            .filter { it.type in compatibleTypes }
            .sortedWith(
                compareBy<LegacyEventCandidate> { it.type != expectedType }
                    .thenBy(LegacyEventCandidate::id),
            )
            .firstOrNull()
            ?.id
    }

    /**
     * The unlinked roster event that a 2.0.0 manual tenure edit superseded. That version rewrote
     * the boundary's source to MANUAL without touching its events, so the original roster event
     * survives at its own time. Only a compatible roster event between the member's previous
     * period's withdrawal ([lowerBound]) and next period's join ([upperBound]), both exclusive
     * and unbounded when null, can be that event; the one nearest [boundaryAt] is chosen.
     */
    fun selectSupersededSnapshotCandidate(
        expectedType: MemberEventType,
        boundaryAt: Long,
        lowerBound: Long?,
        upperBound: Long?,
        candidates: List<LegacyEventCandidate>,
    ): Long? {
        val compatibleTypes = compatibleTypes(expectedType)
        return candidates
            .asSequence()
            .filter { it.source in SNAPSHOT_SOURCES && it.type in compatibleTypes }
            .filter { candidate ->
                val at = candidate.occurredAt ?: candidate.observedAt
                (lowerBound == null || at > lowerBound) && (upperBound == null || at < upperBound)
            }
            .sortedWith(
                compareBy<LegacyEventCandidate> {
                    kotlin.math.abs((it.occurredAt ?: it.observedAt) - boundaryAt)
                }.thenBy(LegacyEventCandidate::id),
            )
            .firstOrNull()
            ?.id
    }

    private fun compatibleTypes(expectedType: MemberEventType): Set<MemberEventType> =
        when (expectedType) {
            MemberEventType.JOINED,
            MemberEventType.REJOINED,
            -> setOf(MemberEventType.JOINED, MemberEventType.REJOINED)
            MemberEventType.LEFT,
            MemberEventType.REMOVED,
            -> setOf(MemberEventType.LEFT, MemberEventType.REMOVED)
            MemberEventType.RENAMED -> setOf(MemberEventType.RENAMED)
        }

    private val SNAPSHOT_SOURCES = setOf(
        EvidenceSource.SNAPSHOT,
        EvidenceSource.LEGACY_IMPORT,
    )
}
