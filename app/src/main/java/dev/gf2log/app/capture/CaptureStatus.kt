package dev.gf2log.app.capture

object CaptureStatus {
    private const val DEFAULT_MESSAGE = "Capture is stopped"

    @Volatile
    private var message = DEFAULT_MESSAGE

    @Volatile
    var isRunning: Boolean = false
        private set

    fun read(): String = message

    fun update(message: String) {
        this.message = message
    }

    fun markRunning(message: String) {
        isRunning = true
        this.message = message
    }

    fun markStopped(message: String = DEFAULT_MESSAGE) {
        isRunning = false
        this.message = message
    }
}
