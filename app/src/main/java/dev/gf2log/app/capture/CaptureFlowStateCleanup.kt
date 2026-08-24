package dev.gf2log.app.capture

/** Removes every piece of per-flow state even when no stream parser was ever created. */
internal object CaptureFlowStateCleanup {
    /**
     * Publishes metadata unless an out-of-band close rejection already quarantined the flow.
     * The post-write check covers both possible races: close-before-write and close-after-write.
     */
    fun registerUnlessQuarantined(
        flowId: Long,
        value: CaptureFlowMetadata,
        metadata: MutableMap<Long, CaptureFlowMetadata>,
        quarantinedFlows: Set<Long>,
    ): Boolean {
        metadata[flowId] = value
        if (flowId !in quarantinedFlows) return true
        metadata.remove(flowId)
        return false
    }

    fun <T> remove(
        flowId: Long,
        parsers: MutableMap<Long, T>,
        metadata: MutableMap<Long, CaptureFlowMetadata>,
    ): T? {
        metadata.remove(flowId)
        return parsers.remove(flowId)
    }
}
