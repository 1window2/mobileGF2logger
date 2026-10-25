package dev.gf2log.app.capture

import androidx.annotation.VisibleForTesting
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonProfileData
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

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
        /** True while a confirmation holds the claim; the prompt stays open to report its outcome. */
        val admitting: Boolean = false,
        /** Why the latest confirmation failed; cleared when the next one claims the candidate. */
        val failure: CaptureNotice? = null,
        /** Confirmations that have claimed this candidate so far, including the running one. */
        val attempts: Int = 0,
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
        var failure: CaptureNotice? = null,
        var attempts: Int = 0,
    )

    private data class IdentityKey(val ownerPackage: String, val platoonId: UInt)

    private val candidates = linkedMapOf<String, Candidate>()
    private val tokenByIdentity = mutableMapOf<IdentityKey, String>()
    private var totalPayloads = 0
    private val listeners = CopyOnWriteArraySet<(List<Summary>) -> Unit>()

    /**
     * [listener] receives every candidate on the main looper: once immediately, then whenever a
     * candidate appears, changes identity, is claimed or released, or is removed. A claimed
     * candidate stays listed as [Summary.admitting], so a slow confirmation does not close its
     * prompt, and a failed one is released with its [Summary.failure] for that prompt to show.
     */
    fun addListener(listener: (List<Summary>) -> Unit) {
        listeners += listener
        MainThread.runOrPost { if (listener in listeners) listener(summaries()) }
    }

    fun removeListener(listener: (List<Summary>) -> Unit) {
        listeners -= listener
    }

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
                if (existing.profile != profile) {
                    existing.profile = profile
                    notifyChanged()
                }
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
        notifyChanged()
        return BeginResult(token)
    }

    @Synchronized
    fun offer(token: String, flowId: Long, payload: ParsedPayload): OfferResult {
        val candidate = candidates[token] ?: return OfferResult.Missing
        if (candidate.claimed) return OfferResult.Claimed
        if (
            candidate.payloads.size >= MAX_PAYLOADS_PER_CANDIDATE ||
            totalPayloads >= MAX_TOTAL_PAYLOADS
        ) {
            return OfferResult.Overflow(removeLocked(token)?.flowIds.orEmpty())
        }
        candidate.flowIds += flowId
        candidate.payloads += BufferedPayload(flowId, payload)
        totalPayloads += 1
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
        candidate.failure = null
        candidate.attempts += 1
        notifyChanged()
        return Claim(
            summary = candidate.summary(),
            payloads = candidate.payloads.toList(),
            flowIds = candidate.flowIds.toSet(),
            endedFlowIds = candidate.endedFlowIds.toSet(),
        )
    }

    @Synchronized
    fun releaseClaim(token: String, failure: CaptureNotice? = null) {
        val candidate = candidates[token] ?: return
        if (!candidate.claimed) return
        candidate.claimed = false
        candidate.failure = failure
        notifyChanged()
    }

    @Synchronized
    fun complete(token: String): Set<Long> = removeLocked(token)?.flowIds.orEmpty()

    /**
     * Claims [token] and runs [admit] outside the lock. [admit] returns null once the candidate is
     * admitted, which completes it, or the failure to show, which releases it for another
     * decision. A throwing [admit] releases it with [CaptureNotice.ADMISSION_INVALID], so a failed
     * confirmation can never leave a candidate claimed forever or its prompt without an outcome.
     * Returns the completed candidate's flow IDs, or null when nothing was completed.
     */
    fun withClaim(token: String, admit: (Claim) -> CaptureNotice?): Set<Long>? {
        val claim = claim(token) ?: return null
        var failure: CaptureNotice? = CaptureNotice.ADMISSION_INVALID
        try {
            failure = admit(claim)
        } finally {
            failure?.let { releaseClaim(token, it) }
        }
        return if (failure == null) complete(token) else null
    }

    @Synchronized
    fun discard(token: String): Set<Long> = removeLocked(token)?.flowIds.orEmpty()

    @VisibleForTesting
    @Synchronized
    internal fun clearForTests() {
        candidates.clear()
        tokenByIdentity.clear()
        totalPayloads = 0
        listeners.clear()
    }

    private fun Candidate.summary() = Summary(
        token = token,
        ownerPackage = ownerPackage,
        profile = profile,
        firstObservedAt = firstObservedAt,
        payloadCount = payloads.size,
        admitting = claimed,
        failure = failure,
        attempts = attempts,
    )

    private fun removeLocked(token: String): Candidate? {
        val removed = candidates.remove(token) ?: return null
        tokenByIdentity.remove(IdentityKey(removed.ownerPackage, removed.profile.platoonId), token)
        totalPayloads -= removed.payloads.size
        notifyChanged()
        return removed
    }

    /** Snapshots are taken on the main thread so listeners never run under this lock. */
    private fun notifyChanged() {
        if (listeners.isEmpty()) return
        MainThread.post {
            val current = summaries()
            listeners.forEach { it(current) }
        }
    }

    internal const val MAX_CANDIDATES = 4
    internal const val MAX_PAYLOADS_PER_CANDIDATE = 64
    internal const val MAX_TOTAL_PAYLOADS = 128
}
