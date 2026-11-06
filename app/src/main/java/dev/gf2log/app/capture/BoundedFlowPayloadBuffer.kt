package dev.gf2log.app.capture

/**
 * Bounded pre-identity quarantine; overflow permanently rejects that flow until closure.
 * Optional items have budgets of their own and are only dropped at their limit, so they can
 * neither reject a flow nor take room from the items that would.
 * Capture confines it to the worker thread; access stays serialized so sharing it remains safe.
 */
internal class BoundedFlowPayloadBuffer<T>(
    private val maxItemsPerFlow: Int,
    private val maxTotalItems: Int,
) {
    private val pending = mutableMapOf<Long, Pending<T>>()
    private val rejected = mutableSetOf<Long>()
    private var totalItems = 0
    private var totalOptionalItems = 0

    init {
        require(maxItemsPerFlow > 0)
        require(maxTotalItems > 0)
    }

    @Synchronized
    fun offer(flowId: Long, item: T): OfferResult {
        if (flowId in rejected) return OfferResult.REJECTED
        val items = pending.getOrPut(flowId, ::Pending)
        if (items.required >= maxItemsPerFlow || totalItems >= maxTotalItems) {
            reject(flowId)
            return OfferResult.OVERFLOW
        }
        items.items.addLast(item)
        items.required += 1
        totalItems += 1
        return OfferResult.ACCEPTED
    }

    /** Holds [item] in captured order when there is room, without ever rejecting the flow. */
    @Synchronized
    fun offerOptional(flowId: Long, item: T): OfferResult {
        if (flowId in rejected) return OfferResult.REJECTED
        val optional = pending[flowId]?.optional ?: 0
        if (optional >= maxItemsPerFlow || totalOptionalItems >= maxTotalItems) return OfferResult.DROPPED
        val items = pending.getOrPut(flowId, ::Pending)
        items.items.addLast(item)
        totalOptionalItems += 1
        return OfferResult.ACCEPTED
    }

    @Synchronized
    fun take(flowId: Long): List<T> {
        val items = pending.remove(flowId)?.also(::release) ?: return emptyList()
        return items.items.toList()
    }

    @Synchronized
    fun isRejected(flowId: Long): Boolean = flowId in rejected

    @Synchronized
    fun reject(flowId: Long) {
        pending.remove(flowId)?.let(::release)
        rejected += flowId
    }

    @Synchronized
    fun remove(flowId: Long) {
        pending.remove(flowId)?.let(::release)
        rejected.remove(flowId)
    }

    @Synchronized
    fun clear() {
        pending.clear()
        rejected.clear()
        totalItems = 0
        totalOptionalItems = 0
    }

    /** Items currently held across every flow, required and optional. */
    @Synchronized
    fun size(): Int = totalItems + totalOptionalItems

    private fun release(items: Pending<T>) {
        totalItems -= items.required
        totalOptionalItems -= items.optional
    }

    private class Pending<T> {
        val items = ArrayDeque<T>()
        var required = 0
        val optional: Int get() = items.size - required
    }

    enum class OfferResult { ACCEPTED, OVERFLOW, REJECTED, DROPPED }
}
