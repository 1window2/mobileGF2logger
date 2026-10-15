package dev.gf2log.app.capture

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting

/** Delivers capture listener callbacks on the main looper. */
internal object MainThread {
    /** JVM unit tests have no Android looper; they install a direct executor instead. */
    @VisibleForTesting
    @Volatile
    var testExecutor: ((() -> Unit) -> Unit)? = null

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun post(block: () -> Unit) {
        testExecutor?.invoke(block) ?: handler.post(block)
    }

    /** Runs [block] synchronously when already on the main thread, otherwise posts it. */
    fun runOrPost(block: () -> Unit) {
        val executor = testExecutor
        when {
            executor != null -> executor(block)
            Looper.myLooper() == Looper.getMainLooper() -> block()
            else -> handler.post(block)
        }
    }
}
