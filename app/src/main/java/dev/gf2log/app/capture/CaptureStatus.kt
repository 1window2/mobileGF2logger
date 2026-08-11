package dev.gf2log.app.capture

object CaptureStatus {
    private const val DEFAULT_MESSAGE = "Capture is stopped"

    @Volatile
    private var message = DEFAULT_MESSAGE

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var guidedProgress: GuidedProgress? = null

    fun read(): String = message

    fun readGuidedProgress(): GuidedProgress? = guidedProgress

    fun update(message: String) {
        this.message = message
    }

    fun markRunning(message: String, guided: Boolean = guidedProgress != null) {
        isRunning = true
        this.message = message
        if (guided && guidedProgress == null) guidedProgress = GuidedProgress()
    }

    fun markStopped(message: String = DEFAULT_MESSAGE) {
        isRunning = false
        this.message = message
    }

    fun beginSession(guided: Boolean) {
        guidedProgress = GuidedProgress().takeIf { guided }
    }

    fun markUsefulPayload(payloadType: Int) {
        val current = guidedProgress ?: return
        guidedProgress = current.copy(capturedPayloadTypes = current.capturedPayloadTypes + payloadType)
    }

    fun clearSession() {
        guidedProgress = null
    }

    data class GuidedProgress(
        val capturedPayloadTypes: Set<Int> = emptySet(),
    )
}
