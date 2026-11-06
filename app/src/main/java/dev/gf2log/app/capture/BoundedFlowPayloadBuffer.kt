package dev.gf2log.app.capture

/**
 * Bounded pre-identity quarantine; overflow permanently rejects that flow until closure.
 * Capture confines it to the worker thread; access stays serialized so sharing it remains safe.
 * Besides counting items, it sums each item's [weigh] against [maxTotalWeight], because one
 * decoded payload can hold far more elements than another.
 */
internal class BoundedFlowPayloadBuffer<T>(
    private val maxItemsPerFlow: Int,
    private val maxTotalItems: Int,
    private val maxTotalWeight: Long = Long.MAX_VALUE,
    private val weigh: (T) -> Int = { 1 },
) {
    private val pending = mutableMapOf<Long, ArrayDeque<T>>()
    private val pendingWeight = mutableMapOf<Long, Long>()
    private val rejected = mutableSetOf<Long>()
    private var totalItems = 0
    private var totalWeight = 0L

    init {
        require(maxItemsPerFlow > 0)
        require(maxTotalItems > 0)
        require(maxTotalWeight > 0)
    }

    @Synchronized
    fun offer(flowId: Long, item: T): OfferResult {
        if (flowId in rejected) return OfferResult.REJECTED
        val items = pending.getOrPut(flowId, ::ArrayDeque)
        val weight = weigh(item).toLong().coerceAtLeast(1)
        if (
            items.size >= maxItemsPerFlow ||
            totalItems >= maxTotalItems ||
            weight > maxTotalWeight - totalWeight
        ) {
            reject(flowId)
            return OfferResult.OVERFLOW
        }
        items.addLast(item)
        totalItems += 1
        totalWeight += weight
        pendingWeight[flowId] = (pendingWeight[flowId] ?: 0L) + weight
        return OfferResult.ACCEPTED
    }

    @Synchronized
    fun take(flowId: Long): List<T> {
        val items = discardPending(flowId) ?: return emptyList()
        return items.toList()
    }

    @Synchronized
    fun isRejected(flowId: Long): Boolean = flowId in rejected

    @Synchronized
    fun reject(flowId: Long) {
        discardPending(flowId)
        rejected += flowId
    }

    @Synchronized
    fun remove(flowId: Long) {
        discardPending(flowId)
        rejected.remove(flowId)
    }

    @Synchronized
    fun clear() {
        pending.clear()
        pendingWeight.clear()
        rejected.clear()
        totalItems = 0
        totalWeight = 0
    }

    private fun discardPending(flowId: Long): ArrayDeque<T>? {
        totalWeight -= pendingWeight.remove(flowId) ?: 0L
        val items = pending.remove(flowId) ?: return null
        totalItems -= items.size
        return items
    }

    enum class OfferResult { ACCEPTED, OVERFLOW, REJECTED }
}
