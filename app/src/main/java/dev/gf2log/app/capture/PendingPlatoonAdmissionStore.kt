package dev.gf2log.app.capture

import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonProfileData
import java.time.Instant
import java.util.UUID

/**
 * Holds unconfirmed Platoon evidence only in process memory.
 *
 * Nothing in this store is serialized. A force-stop or process death therefore discards every
 * candidate, which prevents an unconfirmed client/server association from becoming durable.
 */
internal object PendingPlatoonAdmissionStore {
    data class Summary(
        val token: String,
        val ownerPackage: String,
        val profile: PlatoonProfileData,
        val firstObservedAt: Instant,
        val payloadCount: Int,
    )

    data class BufferedPayload(
        val flowId: Long,
        val payload: ParsedPayload,
    )

    data class Claim(
        val summary: Summary,
        val payloads: List<BufferedPayload>,
        val flowIds: Set<Long>,
        val endedFlowIds: Set<Long>,
    )

    data class BeginResult(
        val token: String?,
        val rejectedFlowIds: Set<Long> = emptySet(),
    )

    sealed interface OfferResult {
        data object Accepted : OfferResult
        data object Missing : OfferResult
        data object Claimed : OfferResult
        data class Overflow(val rejectedFlowIds: Set<Long>) : OfferResult
    }

    private data class Candidate(
        val token: String,
        val ownerPackage: String,
        var profile: PlatoonProfileData,
        val firstObservedAt: Instant,
        val payloads: MutableList<BufferedPayload> = mutableListOf(),
        val flowIds: MutableSet<Long> = linkedSetOf(),
        val endedFlowIds: MutableSet<Long> = linkedSetOf(),
        var claimed: Boolean = false,
    )

    private data class IdentityKey(val ownerPackage: String, val platoonId: UInt)

    private val candidates = linkedMapOf<String, Candidate>()
    private val tokenByIdentity = mutableMapOf<IdentityKey, String>()

    @Synchronized
    fun begin(
        ownerPackage: String,
        profile: PlatoonProfileData,
        flowId: Long,
        observedAt: Instant = Instant.now(),
    ): BeginResult {
        require(ownerPackage in SupportedGamePackages.all)
        require(PlatoonProfilePolicy.isValid(profile))
        val key = IdentityKey(ownerPackage, profile.platoonId)
        tokenByIdentity[key]?.let { token ->
            val existing = candidates[token]
            if (existing != null && !existing.claimed) {
                existing.profile = profile
                existing.flowIds += flowId
                return BeginResult(token)
            }
            tokenByIdentity.remove(key)
        }
        if (candidates.size >= MAX_CANDIDATES) {
            return BeginResult(token = null, rejectedFlowIds = setOf(flowId))
        }
        val token = UUID.randomUUID().toString()
        candidates[token] = Candidate(
            token = token,
            ownerPackage = ownerPackage,
            profile = profile,
            firstObservedAt = observedAt,
            flowIds = linkedSetOf(flowId),
        )
        tokenByIdentity[key] = token
        return BeginResult(token)
    }

    @Synchronized
    fun offer(token: String, flowId: Long, payload: ParsedPayload): OfferResult {
        val candidate = candidates[token] ?: return OfferResult.Missing
        if (candidate.claimed) return OfferResult.Claimed
        if (
            candidate.payloads.size >= MAX_PAYLOADS_PER_CANDIDATE ||
            candidates.values.sumOf { it.payloads.size } >= MAX_TOTAL_PAYLOADS
        ) {
            return OfferResult.Overflow(removeLocked(token)?.flowIds.orEmpty())
        }
        candidate.flowIds += flowId
        candidate.payloads += BufferedPayload(flowId, payload)
        return OfferResult.Accepted
    }

    @Synchronized
    fun markFlowEnded(token: String, flowId: Long) {
        candidates[token]?.let { candidate ->
            candidate.flowIds += flowId
            candidate.endedFlowIds += flowId
        }
    }

    @Synchronized
    fun summaries(): List<Summary> = candidates.values
        .asSequence()
        .filterNot(Candidate::claimed)
        .sortedBy(Candidate::firstObservedAt)
        .map { it.summary() }
        .toList()

    @Synchronized
    fun summary(token: String): Summary? = candidates[token]
        ?.takeUnless(Candidate::claimed)
        ?.summary()

    @Synchronized
    fun claim(token: String): Claim? {
        val candidate = candidates[token] ?: return null
        if (candidate.claimed) return null
        candidate.claimed = true
        return Claim(
            summary = candidate.summary(),
            payloads = candidate.payloads.toList(),
            flowIds = candidate.flowIds.toSet(),
            endedFlowIds = candidate.endedFlowIds.toSet(),
        )
    }

    @Synchronized
    fun releaseClaim(token: String) {
        candidates[token]?.claimed = false
    }

    @Synchronized
    fun complete(token: String): Set<Long> = removeLocked(token)?.flowIds.orEmpty()

    @Synchronized
    fun discard(token: String): Set<Long> = removeLocked(token)?.flowIds.orEmpty()

    @Synchronized
    fun contains(token: String): Boolean = token in candidates

    @Synchronized
    internal fun clearForTests() {
        candidates.clear()
        tokenByIdentity.clear()
    }

    private fun Candidate.summary() = Summary(
        token = token,
        ownerPackage = ownerPackage,
        profile = profile,
        firstObservedAt = firstObservedAt,
        payloadCount = payloads.size,
    )

    private fun removeLocked(token: String): Candidate? {
        val removed = candidates.remove(token) ?: return null
        tokenByIdentity.remove(IdentityKey(removed.ownerPackage, removed.profile.platoonId), token)
        return removed
    }

    internal const val MAX_CANDIDATES = 4
    internal const val MAX_PAYLOADS_PER_CANDIDATE = 64
    internal const val MAX_TOTAL_PAYLOADS = 128
}
