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
    fun tearDown() {
        PendingPlatoonAdmissionStore.clearForTests()
        MainThread.testExecutor = null
    }

    @Test
    fun listenersSeeVisibleCandidatesChangeWithoutPolling() {
        MainThread.testExecutor = { it() }
        val observed = mutableListOf<List<String>>()
        PendingPlatoonAdmissionStore.addListener { summaries ->
            observed += summaries.map { it.profile.platoonName }
        }
        val token = requireNotNull(
            PendingPlatoonAdmissionStore.begin(
                SupportedGamePackages.HAOPLAY,
                profile(5u, "Owls"),
                flowId = 1L,
            ).token,
        )
        PendingPlatoonAdmissionStore.offer(token, 1L, payload(profile(5u, "Owls")))
        PendingPlatoonAdmissionStore.claim(token)
        PendingPlatoonAdmissionStore.releaseClaim(token)
        PendingPlatoonAdmissionStore.discard(token)

        assertEquals(
            listOf(emptyList(), listOf("Owls"), emptyList(), listOf("Owls"), emptyList()),
            observed,
        )
    }

    @Test
    fun totalPayloadBoundIsSharedAcrossCandidatesAndReleasedOnRemoval() {
        val tokens = (1..3).map { index ->
            requireNotNull(
                PendingPlatoonAdmissionStore.begin(
                    SupportedGamePackages.HAOPLAY,
                    profile(index.toUInt(), "P$index"),
                    flowId = index.toLong(),
                ).token,
            )
        }
        repeat(PendingPlatoonAdmissionStore.MAX_PAYLOADS_PER_CANDIDATE) {
            PendingPlatoonAdmissionStore.offer(tokens[0], 1L, payload(profile(1u, "P1")))
            PendingPlatoonAdmissionStore.offer(tokens[1], 2L, payload(profile(2u, "P2")))
        }
        assertTrue(
            PendingPlatoonAdmissionStore.offer(tokens[2], 3L, payload(profile(3u, "P3"))) is
                PendingPlatoonAdmissionStore.OfferResult.Overflow,
        )

        PendingPlatoonAdmissionStore.discard(tokens[0])
        val retry = requireNotNull(
            PendingPlatoonAdmissionStore.begin(
                SupportedGamePackages.HAOPLAY,
                profile(3u, "P3"),
                flowId = 3L,
            ).token,
        )
        assertEquals(
            PendingPlatoonAdmissionStore.OfferResult.Accepted,
            PendingPlatoonAdmissionStore.offer(retry, 3L, payload(profile(3u, "P3"))),
        )
    }

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
    fun failedOrThrowingAdmissionReleasesTheClaimForAnotherDecision() {
        val token = requireNotNull(
            PendingPlatoonAdmissionStore.begin(
                SupportedGamePackages.HAOPLAY,
                profile(5u, "Owls"),
                flowId = 4L,
            ).token,
        )

        assertNull(PendingPlatoonAdmissionStore.withClaim(token) { false })
        assertNotNull(PendingPlatoonAdmissionStore.summary(token))

        assertTrue(
            runCatching {
                PendingPlatoonAdmissionStore.withClaim(token) { error("registry unavailable") }
            }.isFailure,
        )
        assertNotNull(PendingPlatoonAdmissionStore.summary(token))

        assertEquals(setOf(4L), PendingPlatoonAdmissionStore.withClaim(token) { claim ->
            assertNull(PendingPlatoonAdmissionStore.summary(claim.summary.token))
            true
        })
        assertFalse(PendingPlatoonAdmissionStore.contains(token))
        assertNull(PendingPlatoonAdmissionStore.withClaim(token) { true })
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
