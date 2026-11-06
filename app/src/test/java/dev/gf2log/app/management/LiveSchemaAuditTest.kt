package dev.gf2log.app.management

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSchemaAuditTest {
    private val reference = mapOf(
        "members" to "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY, NOTE TEXT NOT NULL DEFAULT '' )",
        "snapshot_members" to "CREATE TABLE SNAPSHOT_MEMBERS ( SNAPSHOT_ID INTEGER NOT NULL, UID INTEGER NOT NULL )",
    )

    @Test
    fun theAppsOwnDdlNeedsNoRebuild() {
        assertFalse(LiveSchemaAudit.carriesForeignDdl(reference, reference))
    }

    @Test
    fun anUpgradeAppendingColumnsToAnAlteredTableNeedsNoRebuild() {
        val upgraded = reference + ("members" to
            "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY, NOTE TEXT NOT NULL DEFAULT '' , CUSTOM_NAME TEXT)")

        assertFalse(LiveSchemaAudit.carriesForeignDdl(upgraded, reference))
    }

    @Test
    fun clausesTheAppNeverWritesNeedARebuild() {
        listOf(
            "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY, NOTE TEXT NOT NULL CHECK (LENGTH(NOTE) < 5) )",
            "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY ON CONFLICT IGNORE, NOTE TEXT )",
            "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY, NOTE TEXT COLLATE NOCASE )",
            "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY, NOTE TEXT ) STRICT",
            "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY, NOTE TEXT ) WITHOUT ROWID",
            "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY, NOTE TEXT GENERATED ALWAYS AS (UID) )",
            "CREATE TABLE MEMBERS ( UID INTEGER PRIMARY KEY, NOTE TEXT AS(UID) )",
            "create table members ( uid integer primary key, note text check (note != '') )",
        ).forEach { sql ->
            assertTrue(sql, LiveSchemaAudit.carriesForeignDdl(reference + ("members" to sql), reference))
        }
    }

    @Test
    fun anyDifferenceInATableNoMigrationAltersNeedsARebuild() {
        val changed = reference + ("snapshot_members" to
            "CREATE TABLE SNAPSHOT_MEMBERS ( SNAPSHOT_ID INTEGER NOT NULL, UID INTEGER NOT NULL, PRIMARY KEY(UID) )")

        assertTrue(LiveSchemaAudit.carriesForeignDdl(changed, reference))
    }
}
