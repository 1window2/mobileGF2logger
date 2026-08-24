package dev.gf2log.app.capture

/** Bounded pre-identity quarantine; overflow permanently rejects that flow until closure. */
internal class BoundedFlowPayloadBuffer<T>(private val maxItemsPerFlow: Int) {
    private val pending = mutableMapOf<Long, ArrayDeque<T>>()
    private val rejected = mutableSetOf<Long>()

    init {
        require(maxItemsPerFlow > 0)
    }

    fun offer(flowId: Long, item: T): OfferResult {
        if (flowId in rejected) return OfferResult.REJECTED
        val items = pending.getOrPut(flowId, ::ArrayDeque)
        if (items.size >= maxItemsPerFlow) {
            pending.remove(flowId)
            rejected += flowId
            return OfferResult.OVERFLOW
        }
        items.addLast(item)
        return OfferResult.ACCEPTED
    }

    fun take(flowId: Long): List<T> = pending.remove(flowId)?.toList().orEmpty()

    fun reject(flowId: Long) {
        pending.remove(flowId)
        rejected += flowId
    }

    fun remove(flowId: Long) {
        pending.remove(flowId)
        rejected.remove(flowId)
    }

    fun clear() {
        pending.clear()
        rejected.clear()
    }

    enum class OfferResult { ACCEPTED, OVERFLOW, REJECTED }
}
