package dev.gf2log.app.capture

import dev.gf2log.app.management.CsvImportInProgressException
import dev.gf2log.app.management.PlatoonDatabaseDamagedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(CaptureNotice.HISTORY_IMPORT_FAILED to emptyList<String>(), CaptureHistoryPreparation.stopNotice(failure))
    }

    @Test
    fun aRunningImportFailsTheStartWithItsOwnNoticeBeforeRecoveryRuns() {
        var recovered = false

        val failure = runCatching {
            CaptureHistoryPreparation.run(
                listOf("a", "b"),
                busy = { it == "b" },
                recover = { recovered = true; 0 },
            ) {}
        }.exceptionOrNull()

        assertTrue(failure is CsvImportInProgressException)
        assertFalse(recovered)
        assertEquals(CaptureNotice.CSV_IMPORT_IN_PROGRESS to emptyList<String>(), CaptureHistoryPreparation.stopNotice(failure))
    }

    @Test
    fun aDamagedScopeFailsTheStartNamingItsPlatoon() {
        val prepared = mutableListOf<String>()

        val failure = runCatching {
            CaptureHistoryPreparation.run(listOf("a", "b", "c"), name = { "Platoon $it" }, recover = { 0 }) { scope ->
                if (scope == "b") throw PlatoonDatabaseDamagedException("quarantined")
                prepared += scope
            }
        }.exceptionOrNull()

        // Fail closed: nothing after the damaged scope is prepared, and capture never establishes.
        assertEquals(listOf("a"), prepared)
        assertEquals(CaptureNotice.DATABASE_DAMAGED to listOf("Platoon b"), CaptureHistoryPreparation.stopNotice(failure))
        // A scope failure for any other reason keeps the generic notice.
        val other = CaptureHistoryPreparation.ScopeFailure("Platoon a", IllegalStateException("disk full"))
        assertEquals(CaptureNotice.HISTORY_IMPORT_FAILED to emptyList<String>(), CaptureHistoryPreparation.stopNotice(other))
        // Damage the notice cannot attribute to a Platoon still fails, under the generic notice.
        assertEquals(
            CaptureNotice.HISTORY_IMPORT_FAILED to emptyList<String>(),
            CaptureHistoryPreparation.stopNotice(PlatoonDatabaseDamagedException("unattributed")),
        )
    }
}
