package dev.gf2log.app.capture

import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonProfileData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PendingPlatoonAdmissionStoreTest {
    @After
    fun tearDown() = PendingPlatoonAdmissionStore.clearForTests()

    @Test
    fun candidateIsMemoryOnlyBoundedAndClaimedAtomically() {
        val profile = profile(101817u, "Owls")
        val token = requireNotNull(
            PendingPlatoonAdmissionStore.begin(
                SupportedGamePackages.HAOPLAY,
                profile,
                flowId = 7L,
                observedAt = Instant.EPOCH,
            ).token,
        )
        assertEquals(
            PendingPlatoonAdmissionStore.OfferResult.Accepted,
            PendingPlatoonAdmissionStore.offer(token, 7L, payload(profile)),
        )
        PendingPlatoonAdmissionStore.markFlowEnded(token, 7L)

        val claim = PendingPlatoonAdmissionStore.claim(token)
        assertNotNull(claim)
        assertEquals(setOf(7L), claim?.flowIds)
        assertEquals(setOf(7L), claim?.endedFlowIds)
        assertEquals(1, claim?.payloads?.size)
        assertNull(PendingPlatoonAdmissionStore.claim(token))

        PendingPlatoonAdmissionStore.releaseClaim(token)
        assertNotNull(PendingPlatoonAdmissionStore.claim(token))
        assertEquals(setOf(7L), PendingPlatoonAdmissionStore.complete(token))
        assertFalse(PendingPlatoonAdmissionStore.contains(token))
    }

    @Test
    fun repeatedIdentitySharesOnePromptWithoutMixingOtherClients() {
        val first = requireNotNull(
            PendingPlatoonAdmissionStore.begin(
                SupportedGamePackages.HAOPLAY,
                profile(42u, "First"),
                flowId = 1L,
            ).token,
        )
        val repeated = requireNotNull(
            PendingPlatoonAdmissionStore.begin(
                SupportedGamePackages.HAOPLAY,
                profile(42u, "Updated"),
                flowId = 2L,
            ).token,
        )
        val otherClient = requireNotNull(
            PendingPlatoonAdmissionStore.begin(
                SupportedGamePackages.DARKWINTER,
                profile(42u, "Darkwinter"),
                flowId = 3L,
            ).token,
        )

        assertEquals(first, repeated)
        assertTrue(otherClient != first)
        assertEquals(2, PendingPlatoonAdmissionStore.summaries().size)
        assertEquals("Updated", PendingPlatoonAdmissionStore.summary(first)?.profile?.platoonName)
    }

    @Test
    fun overflowingCandidateIsDiscardedInsteadOfPartiallyAdmitted() {
        val profile = profile(99u, "Bounded")
        val token = requireNotNull(
            PendingPlatoonAdmissionStore.begin(
                SupportedGamePackages.HAOPLAY,
                profile,
                flowId = 9L,
            ).token,
        )
        repeat(PendingPlatoonAdmissionStore.MAX_PAYLOADS_PER_CANDIDATE) {
            assertEquals(
                PendingPlatoonAdmissionStore.OfferResult.Accepted,
                PendingPlatoonAdmissionStore.offer(token, 9L, payload(profile)),
            )
        }
        val overflow = PendingPlatoonAdmissionStore.offer(token, 9L, payload(profile))

        assertEquals(
            PendingPlatoonAdmissionStore.OfferResult.Overflow(setOf(9L)),
            overflow,
        )
        assertFalse(PendingPlatoonAdmissionStore.contains(token))
    }

    @Test
    fun candidateCountRejectsOnlyTheNewFlow() {
        repeat(PendingPlatoonAdmissionStore.MAX_CANDIDATES) { index ->
            assertNotNull(
                PendingPlatoonAdmissionStore.begin(
                    SupportedGamePackages.HAOPLAY,
                    profile((index + 1).toUInt(), "P$index"),
                    flowId = index.toLong(),
                ).token,
            )
        }
        val rejected = PendingPlatoonAdmissionStore.begin(
            SupportedGamePackages.HAOPLAY,
            profile(100u, "Extra"),
            flowId = 100L,
        )
        assertNull(rejected.token)
        assertEquals(setOf(100L), rejected.rejectedFlowIds)
    }

    private fun profile(id: UInt, name: String) = PlatoonProfileData(
        platoonId = id,
        platoonName = name,
        bannerFrameId = 1u,
        bannerMarkId = 2u,
    )

    private fun payload(profile: PlatoonProfileData) = ParsedPayload(
        messageId = 1,
        payloadType = 21905,
        isEndOfMessage = true,
        data = profile,
    )
}
