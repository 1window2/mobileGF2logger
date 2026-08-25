package dev.gf2log.app.capture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Keeps guided-capture evidence isolated so payloads from two clients cannot complete one run. */
internal class ScopedCaptureChecklist(private val requiredTypes: Set<Int>) {
    private val capturedByScope = ConcurrentHashMap<String, MutableSet<Int>>()
    private val targetScope = AtomicReference<String?>(null)

    fun mark(scopeId: String, payloadType: Int, chooseTarget: Boolean): Set<Int> {
        require(scopeId.isNotBlank())
        if (chooseTarget) targetScope.compareAndSet(null, scopeId)
        val captured = capturedByScope.computeIfAbsent(scopeId) {
            ConcurrentHashMap.newKeySet()
        }
        captured += payloadType
        return captured.toSet()
    }

    fun targetScopeId(): String? = targetScope.get()

    fun targetCaptured(): Set<Int> = targetScope.get()
        ?.let(capturedByScope::get)
        ?.toSet()
        .orEmpty()

    fun targetComplete(): Boolean = targetCaptured().containsAll(requiredTypes)

    fun clear() {
        capturedByScope.clear()
        targetScope.set(null)
    }
}
