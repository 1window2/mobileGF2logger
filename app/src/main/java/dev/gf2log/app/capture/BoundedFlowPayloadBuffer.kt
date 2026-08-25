package dev.gf2log.app.capture

/** Bounded pre-identity quarantine; overflow permanently rejects that flow until closure. */
internal class BoundedFlowPayloadBuffer<T>(
    private val maxItemsPerFlow: Int,
    private val maxTotalItems: Int,
) {
    private val pending = mutableMapOf<Long, ArrayDeque<T>>()
    private val rejected = mutableSetOf<Long>()
    private var totalItems = 0

    init {
        require(maxItemsPerFlow > 0)
        require(maxTotalItems > 0)
    }

    fun offer(flowId: Long, item: T): OfferResult {
        if (flowId in rejected) return OfferResult.REJECTED
        val items = pending.getOrPut(flowId, ::ArrayDeque)
        if (items.size >= maxItemsPerFlow || totalItems >= maxTotalItems) {
            reject(flowId)
            return OfferResult.OVERFLOW
        }
        items.addLast(item)
        totalItems += 1
        return OfferResult.ACCEPTED
    }

    fun take(flowId: Long): List<T> {
        val items = pending.remove(flowId) ?: return emptyList()
        totalItems -= items.size
        return items.toList()
    }

    fun isRejected(flowId: Long): Boolean = flowId in rejected

    fun reject(flowId: Long) {
        totalItems -= pending.remove(flowId)?.size ?: 0
        rejected += flowId
    }

    fun remove(flowId: Long) {
        totalItems -= pending.remove(flowId)?.size ?: 0
        rejected.remove(flowId)
    }

    fun clear() {
        pending.clear()
        rejected.clear()
        totalItems = 0
    }

    enum class OfferResult { ACCEPTED, OVERFLOW, REJECTED }
}
