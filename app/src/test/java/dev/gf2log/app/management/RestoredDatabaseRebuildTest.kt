package dev.gf2log.app.management

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RestoredDatabaseRebuildTest {
    @Test
    fun rowsAreCopiedThroughTheAppColumnListOnly() {
        val statements = RestoredDatabaseRebuild.copyStatements(
            mapOf(
                "snapshots" to listOf("id", "captured_at"),
                "members" to listOf("uid", "note"),
            ),
        )

        assertEquals(
            listOf(
                "INSERT INTO main.\"members\" (\"uid\", \"note\") " +
                    "SELECT \"uid\", \"note\" FROM restored_source.\"members\"",
                "INSERT INTO main.\"snapshots\" (\"id\", \"captured_at\") " +
                    "SELECT \"id\", \"captured_at\" FROM restored_source.\"snapshots\"",
            ),
            statements,
        )
    }

    @Test
    fun identifiersAreQuotedSoNoNameCanEscapeTheStatement() {
        assertEquals("\"a\"\"b\"", RestoredDatabaseRebuild.identifier("a\"b"))
        assertThrows(IllegalArgumentException::class.java) {
            RestoredDatabaseRebuild.copyStatements(mapOf("empty" to emptyList()))
        }
    }

    @Test
    fun everySqliteFileOfADatabaseIsListedForCleanup() {
        assertEquals(
            listOf("db", "db-wal", "db-shm", "db-journal"),
            RestoredDatabaseRebuild.sqliteFiles(File("db")).map(File::getName),
        )
    }
}
