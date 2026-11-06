package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonEvidenceDigestTest {
    @Test
    fun derivedHistoryAndSqliteBookkeepingAreNotEvidence() {
        assertFalse(PlatoonEvidenceDigest.covers("weekly_report_history"))
        assertFalse(PlatoonEvidenceDigest.covers("weekly_report_history_state"))
        assertFalse(PlatoonEvidenceDigest.covers("sqlite_sequence"))
        assertFalse(PlatoonEvidenceDigest.covers("android_metadata"))
        listOf(
            "snapshots",
            "snapshot_members",
            "members",
            "membership_periods",
            "member_events",
            "platoon_activity",
            "weekly_notes",
            "weekly_overrides",
        ).forEach { assertTrue(it, PlatoonEvidenceDigest.covers(it)) }
    }

    @Test
    fun everyValueKeepsItsTypeAndBoundary() {
        fun digest(vararg rows: List<Any?>) = PlatoonEvidenceDigest.Hasher(13).apply {
            table("members", listOf("a", "b"))
            rows.forEach(::row)
        }.hex()

        assertEquals(digest(listOf(1L, "x")), digest(listOf(1L, "x")))
        assertNotEquals(digest(listOf(1L, "x")), digest(listOf("1", "x")))
        assertNotEquals(digest(listOf("ab", "c")), digest(listOf("a", "bc")))
        assertNotEquals(digest(listOf(null, "")), digest(listOf("", null)))
        assertNotEquals(digest(listOf(1L, byteArrayOf(1))), digest(listOf(1L, byteArrayOf(1, 0))))
        assertNotEquals(digest(listOf(1L, "x")), digest(listOf(1L, "x"), listOf(1L, "x")))
    }

    @Test
    fun theSchemaVersionAndTableNamesArePartOfTheDigest() {
        fun digest(version: Int, table: String) = PlatoonEvidenceDigest.Hasher(version).apply {
            table(table, listOf("uid"))
            row(listOf(7L))
        }.hex()

        assertNotEquals(digest(12, "members"), digest(13, "members"))
        assertNotEquals(digest(13, "members"), digest(13, "weekly_notes"))
    }
}
