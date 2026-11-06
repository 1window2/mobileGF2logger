package dev.gf2log.app.management

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonDatabaseDamageTest {
    @Test
    fun onlyAMarkerWithoutALiveDatabaseBlocksOpening() {
        // The marker protects against silently creating an empty database over lost data.
        assertTrue(PlatoonDatabaseDamage.blocksOpen(markerExists = true, databaseExists = false))
        // A restore installs a database before its commit clears the marker.
        assertFalse(PlatoonDatabaseDamage.blocksOpen(markerExists = true, databaseExists = true))
        assertFalse(PlatoonDatabaseDamage.blocksOpen(markerExists = false, databaseExists = false))
    }

    @Test
    fun quarantineMovesJournalsBeforeTheMainFileAndKeepsTheirPairing() {
        val database = File("/data/databases/platoon-1.db")

        val targets = PlatoonDatabaseDamage.quarantineTargets(database, stamp = 42)

        assertEquals(
            listOf(
                "platoon-1.db-wal" to "platoon-1.db.corrupt-42-wal",
                "platoon-1.db-journal" to "platoon-1.db.corrupt-42-journal",
                "platoon-1.db" to "platoon-1.db.corrupt-42",
            ),
            targets.map { (live, kept) -> live.name to kept.name },
        )
        assertTrue(targets.all { (live, kept) -> live.parentFile == kept.parentFile })
    }
}
