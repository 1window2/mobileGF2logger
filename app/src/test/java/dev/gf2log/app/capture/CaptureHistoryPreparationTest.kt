package dev.gf2log.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHistoryPreparationTest {
    @Test
    fun everyScopeIsPreparedOnceRecoveryIsComplete() {
        val prepared = mutableListOf<String>()

        CaptureHistoryPreparation.run(listOf("a", "b"), recover = { 0 }) { prepared += it }

        assertEquals(listOf("a", "b"), prepared)
    }

    @Test
    fun anIncompleteRecoveryFailsTheStartBeforeAnythingIsCaptured() {
        val prepared = mutableListOf<String>()
        var recovered: List<String>? = null

        // A scope whose interrupted import could not be rolled back would later be restored over
        // whatever this capture ingested, so the start must fail rather than capture into it.
        val failure = runCatching {
            CaptureHistoryPreparation.run(listOf("a", "b"), recover = { scopes -> recovered = scopes; 1 }) {
                prepared += it
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("a", "b"), recovered)
        assertTrue(prepared.isEmpty())
    }
}
