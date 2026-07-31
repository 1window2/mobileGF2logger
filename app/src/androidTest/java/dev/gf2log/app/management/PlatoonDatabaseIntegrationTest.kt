package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatoonDatabaseIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: PlatoonDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
        database = PlatoonDatabase(context, TEST_DATABASE)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun deletionRemovesMemberFootprintAndKeepsSourceIdentity() {
        val baselineAt = Instant.parse("2026-07-30T00:00:00Z")
        val capturedAt = Instant.parse("2026-07-31T00:00:00Z")
        val sourceFile = "gf2log_platoonmembers_20260731T000000Z.csv"
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = baselineAt,
                sourceFile = "gf2log_platoonmembers_20260730T000000Z.csv",
                members = listOf(member(OTHER_UID, "Other")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt,
                sourceFile = sourceFile,
                members = listOf(member(TARGET_UID, "Target"), member(OTHER_UID, "Other")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        val writable = database.writableDatabase
        val targetEventId = queryLong(
            "SELECT id FROM member_events WHERE uid = ? LIMIT 1",
            TARGET_UID,
        )
        writable.insertOrThrow(
            "weekly_overrides",
            null,
            ContentValues().apply {
                put("uid", TARGET_UID)
                put("period_start", 20665L)
                put("game_day", 20665L)
            },
        )
        writable.insertOrThrow(
            "weekly_notes",
            null,
            ContentValues().apply {
                put("period_start", 20665L)
                put("game_day", 20665L)
                put("text", "Target joined")
                put("event_id", targetEventId)
                put("is_automatic", 1)
            },
        )
        writable.insertOrThrow(
            "platoon_activity",
            null,
            ContentValues().apply {
                put("occurred_at", capturedAt.toEpochMilli())
                put("action_id", 802001L)
                put("kind", 1L)
                put("member_name", "Target")
                put("captured_at", capturedAt.toEpochMilli())
                put("resolved_uid", TARGET_UID)
                put("resolution", ActivityResolution.EXACT_UPDATE.name)
                put("member_event_id", targetEventId)
            },
        )

        assertTrue(database.deleteMember(TARGET_UID))
        assertFalse(database.deleteMember(TARGET_UID))

        assertEquals(0L, count("members", "uid = ?", TARGET_UID))
        assertEquals(0L, count("tenures", "uid = ?", TARGET_UID))
        assertEquals(0L, count("member_events", "uid = ?", TARGET_UID))
        assertEquals(0L, count("snapshot_members", "uid = ?", TARGET_UID))
        assertEquals(0L, count("weekly_overrides", "uid = ?", TARGET_UID))
        assertEquals(0L, count("platoon_activity", "resolved_uid = ?", TARGET_UID))
        assertEquals(0L, count("weekly_notes", "text = ?", "Target joined"))
        assertEquals(1L, count("members", "uid = ?", OTHER_UID))
        assertEquals(1L, count("snapshots", "source_file = ?", sourceFile))
        assertTrue(sourceFile in database.snapshotSourceFiles())
        assertForeignKeysValid()

        val duplicate = database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt,
                sourceFile = sourceFile,
                members = listOf(member(TARGET_UID, "Target"), member(OTHER_UID, "Other")),
            ),
            EvidenceSource.LEGACY_IMPORT,
        )
        assertTrue(duplicate.duplicate)
        assertEquals(0L, count("members", "uid = ?", TARGET_UID))
    }

    @Test
    fun historicalSnapshotAddsWeeklyEvidenceWithoutReplacingCurrentMembers() {
        val currentTime = Instant.parse("2026-07-31T00:00:00Z")
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime,
                sourceFile = "current.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )

        val historical = database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime.minusSeconds(86_400),
                sourceFile = "historical.csv",
                members = listOf(member(TARGET_UID, "Historical")),
            ),
            EvidenceSource.LEGACY_IMPORT,
            historicalOnly = true,
        )

        assertFalse(historical.duplicate)
        assertEquals(1L, count("snapshots", "source_file = ?", "historical.csv"))
        assertEquals(1L, count("snapshot_members", "uid = ?", TARGET_UID))
        assertEquals(0L, count("members", "uid = ?", TARGET_UID))
        assertEquals(1L, count("members", "uid = ?", OTHER_UID))
        assertForeignKeysValid()
    }

    private fun member(uid: Long, name: String) = SnapshotMember(
        uid = uid,
        name = name,
        level = 60,
        weeklyMerit = 120,
        totalMerit = 4_560,
        highScore = 789,
        totalScore = 1_234,
        lastLogin = 1_700_000_000,
    )

    private fun count(table: String, selection: String, value: Any): Long =
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE $selection",
            arrayOf(value.toString()),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun queryLong(sql: String, value: Long): Long =
        database.readableDatabase.rawQuery(sql, arrayOf(value.toString())).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun assertForeignKeysValid() {
        database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }

    private companion object {
        const val TEST_DATABASE = "platoon-integration-test.db"
        const val TARGET_UID = 1001L
        const val OTHER_UID = 2002L
    }
}
