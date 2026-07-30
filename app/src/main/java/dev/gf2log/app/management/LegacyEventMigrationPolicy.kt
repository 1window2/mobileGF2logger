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
        val compatibleTypes = when (expectedType) {
            MemberEventType.JOINED,
            MemberEventType.REJOINED,
            -> setOf(MemberEventType.JOINED, MemberEventType.REJOINED)
            MemberEventType.LEFT,
            MemberEventType.REMOVED,
            -> setOf(MemberEventType.LEFT, MemberEventType.REMOVED)
            MemberEventType.RENAMED -> setOf(MemberEventType.RENAMED)
        }
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

    private val SNAPSHOT_SOURCES = setOf(
        EvidenceSource.SNAPSHOT,
        EvidenceSource.LEGACY_IMPORT,
    )
}
