package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyEventMigrationPolicyTest {
    @Test
    fun `join boundaries prefer the expected variant then the oldest event`() {
        val candidates = listOf(
            candidate(40, MemberEventType.RENAMED),
            candidate(30, MemberEventType.REJOINED),
            candidate(20, MemberEventType.JOINED),
        )

        assertEquals(
            30L,
            LegacyEventMigrationPolicy.selectCandidate(
                MemberEventType.REJOINED,
                BOUNDARY_AT,
                candidates,
            ),
        )
    }

    @Test
    fun `join boundaries accept the other legacy variant as a fallback`() {
        assertEquals(
            20L,
            LegacyEventMigrationPolicy.selectCandidate(
                MemberEventType.REJOINED,
                BOUNDARY_AT,
                listOf(candidate(20, MemberEventType.JOINED)),
            ),
        )
    }

    @Test
    fun `withdraw boundaries accept left or removed but not join events`() {
        val candidates = listOf(
            candidate(10, MemberEventType.JOINED),
            candidate(12, MemberEventType.REMOVED),
            candidate(14, MemberEventType.LEFT),
        )

        assertEquals(
            14L,
            LegacyEventMigrationPolicy.selectCandidate(
                MemberEventType.LEFT,
                BOUNDARY_AT,
                candidates,
            ),
        )
    }

    @Test
    fun `migration creates a boundary event when no compatible candidate exists`() {
        assertNull(
            LegacyEventMigrationPolicy.selectCandidate(
                MemberEventType.LEFT,
                BOUNDARY_AT,
                listOf(candidate(8, MemberEventType.RENAMED)),
            ),
        )
    }

    @Test
    fun `snapshot boundaries can use observed time when occurred time is unknown`() {
        assertEquals(
            7L,
            LegacyEventMigrationPolicy.selectCandidate(
                MemberEventType.JOINED,
                BOUNDARY_AT,
                listOf(
                    candidate(
                        id = 7,
                        type = MemberEventType.JOINED,
                        occurredAt = null,
                        observedAt = BOUNDARY_AT,
                        source = EvidenceSource.SNAPSHOT,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `manual boundaries never substitute observation time for occurrence time`() {
        assertNull(
            LegacyEventMigrationPolicy.selectCandidate(
                MemberEventType.JOINED,
                BOUNDARY_AT,
                listOf(
                    candidate(
                        id = 7,
                        type = MemberEventType.JOINED,
                        occurredAt = null,
                        observedAt = BOUNDARY_AT,
                        source = EvidenceSource.MANUAL,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a manual legacy boundary supersedes the nearest roster event in its gap`() {
        val candidates = listOf(
            candidate(1, MemberEventType.JOINED, occurredAt = null, observedAt = BOUNDARY_AT - 5, source = EvidenceSource.SNAPSHOT),
            candidate(2, MemberEventType.JOINED, occurredAt = null, observedAt = BOUNDARY_AT + 50, source = EvidenceSource.SNAPSHOT),
            candidate(3, MemberEventType.LEFT, occurredAt = null, observedAt = BOUNDARY_AT, source = EvidenceSource.SNAPSHOT),
        )

        assertEquals(
            1L,
            LegacyEventMigrationPolicy.selectSupersededSnapshotCandidate(
                MemberEventType.REJOINED,
                BOUNDARY_AT,
                lowerBound = null,
                upperBound = null,
                candidates = candidates,
            ),
        )
    }

    @Test
    fun `a roster event outside the period's gap or of the other side is never superseded`() {
        val outside = candidate(1, MemberEventType.JOINED, occurredAt = null, observedAt = BOUNDARY_AT + 100, source = EvidenceSource.SNAPSHOT)
        val withdrawal = candidate(2, MemberEventType.LEFT, occurredAt = null, observedAt = BOUNDARY_AT, source = EvidenceSource.SNAPSHOT)
        val manual = candidate(3, MemberEventType.JOINED, source = EvidenceSource.MANUAL)

        assertNull(
            LegacyEventMigrationPolicy.selectSupersededSnapshotCandidate(
                MemberEventType.JOINED,
                BOUNDARY_AT,
                lowerBound = BOUNDARY_AT - 10,
                upperBound = BOUNDARY_AT + 100,
                candidates = listOf(outside, withdrawal, manual),
            ),
        )
    }

    private fun candidate(
        id: Long,
        type: MemberEventType,
        occurredAt: Long? = BOUNDARY_AT,
        observedAt: Long = BOUNDARY_AT,
        source: EvidenceSource = EvidenceSource.MANUAL,
    ) = LegacyEventCandidate(id, type, occurredAt, observedAt, source)

    private companion object {
        const val BOUNDARY_AT = 1_800_000_000_000L
    }
}
