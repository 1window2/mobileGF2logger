package dev.gf2log.app.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serialized off-main-thread work bound to one screen's lifecycle.
 *
 * Every task runs on one worker so repository calls from a screen keep their submission order.
 * Results are delivered on the main thread only while the owner is not destroyed, and a keyed
 * request supersedes earlier requests with the same key, so a stale load can never overwrite a
 * newer one. Running tasks are allowed to finish after destruction because they may be committing
 * data; only their results are dropped. Effects that do not need the screen, such as a toast through
 * [Feedback], temp-file cleanup, or refreshing whichever screen is showing, go in `always`, which runs
 * once for every finished task even when the screen was recreated meanwhile.
 */
internal class BackgroundWork(owner: LifecycleOwner, threadName: String) {
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName)
    }
    private val generations = HashMap<String, Int>()
    private val running = HashMap<String, Int>()
    private var destroyed = false

    init {
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                destroyed = true
                executor.shutdown()
            }
        })
    }

    /** True while a request for [key] has been submitted and its result is not yet delivered. */
    fun isBusy(key: String): Boolean = (running[key] ?: 0) > 0

    fun <T> run(
        key: String? = null,
        task: () -> T,
        always: (Result<T>) -> Unit = {},
        onResult: (Result<T>) -> Unit = {},
    ) {
        if (destroyed) return
        val generation = key?.let { generations[it] = (generations[it] ?: 0) + 1; generations[it] }
        key?.let { running[it] = (running[it] ?: 0) + 1 }
        executor.execute {
            val result = runCatching(task)
            // Only the type: a message can quote user input, such as a pasted webhook token.
            result.exceptionOrNull()?.let { Log.w(TAG, "Background task failed: ${it.javaClass.name}") }
            main.post {
                key?.let { running[it] = ((running[it] ?: 1) - 1).coerceAtLeast(0) }
                val current = !destroyed && (key == null || generations[key] == generation)
                deliverResult(result, current, always, onResult)
            }
        }
    }

    private companion object {
        const val TAG = "GF2Work"
    }
}

/**
 * Delivers one finished task: [always] runs exactly once, and [onResult] only while the owning
 * screen is alive and the request is still the newest for its key.
 */
internal fun <T> deliverResult(
    result: Result<T>,
    current: Boolean,
    always: (Result<T>) -> Unit,
    onResult: (Result<T>) -> Unit,
) {
    always(result)
    if (current) onResult(result)
}
