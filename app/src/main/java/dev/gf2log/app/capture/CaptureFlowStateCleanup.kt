package dev.gf2log.app.capture

/** Removes every piece of per-flow state even when no stream parser was ever created. */
internal object CaptureFlowStateCleanup {
    fun <T> remove(
        flowId: Long,
        parsers: MutableMap<Long, T>,
        metadata: MutableMap<Long, CaptureFlowMetadata>,
    ): T? {
        metadata.remove(flowId)
        return parsers.remove(flowId)
    }
}
