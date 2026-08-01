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
