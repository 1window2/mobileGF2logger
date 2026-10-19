package dev.gf2log.app.capture

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tells listeners, on the main thread, which saved Platoon just had a captured roster persisted.
 * Unlike [CaptureStatus] notices these are never coalesced, so every persisted roster is seen.
 */
internal object RosterEvents {
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun addListener(listener: (storageId: String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (storageId: String) -> Unit) {
        listeners -= listener
    }

    /** Called from the capture worker once a roster for [storageId] is stored or recovered. */
    fun persisted(storageId: String) {
        if (listeners.isEmpty()) return
        MainThread.post { listeners.forEach { it(storageId) } }
    }
}
