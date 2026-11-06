package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.protocol.model.PlatoonProfileData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The v14 and restore repair of members stored without a real UID (UID 0 or below). */
@RunWith(AndroidJUnit4::class)
class InvalidMemberIdentityRepairIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var profile: PlatoonProfile
    private lateinit var scope: PlatoonStorageScope

    @Before
    fun setUp() {
        profile = PlatoonProfileRegistry(context).upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(Random.nextInt(1_000_000, 9_000_000).toUInt(), "Repair Platoon", 0u, 0u),
        )
        check(PlatoonProfileRegistry(context).setActive(profile.storageId))
        scope = PlatoonStorageScope(profile.storageId)
        GameTimeZonePreferences.set(context, ZoneOffset.UTC, scope.storageId)
    }

    @After
    fun tearDown() {
        runCatching { PlatoonProfileAdministration(context).deleteProfile(profile.storageId) }
        PlatoonRepository.withExclusiveDatabase(scope) { context.deleteDatabase(scope.databaseName) }
        scope.rootDirectory(context).deleteRecursively()
        File(context.cacheDir, "platoon-restore/${scope.storageId}").deleteRecursively()
    }

    @Test
    fun aVersion13DatabaseWithAUidZeroMemberUpgradesIntoAWorkingWeek() {
        PlatoonDatabase(context, scope.databaseName).use { database ->
            database.ingestSnapshot(snapshot(CAPTURED_AT, "roster.csv", 1, 2), EvidenceSource.SNAPSHOT)
        }
        writable(context.getDatabasePath(scope.databaseName)) { db ->
            insertInvalidIdentityRows(db)
            // The v13 snapshots table, before the clock column.
            db.execSQL("PRAGMA legacy_alter_table = ON")
            db.execSQL("ALTER TABLE snapshots RENAME TO snapshots_v14")
            db.execSQL(
                "CREATE TABLE snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "captured_at INTEGER NOT NULL, source_file TEXT UNIQUE, game_version TEXT)",
            )
            db.execSQL(
                "INSERT INTO snapshots (id, captured_at, source_file, game_version) " +
                    "SELECT id, captured_at, source_file, game_version FROM snapshots_v14",
            )
            db.execSQL("DROP TABLE snapshots_v14")
            db.execSQL("CREATE INDEX snapshots_captured_at ON snapshots(captured_at DESC)")
            db.version = 13
        }

        val repository = PlatoonRepository(context, scope)
        val revision = repository.buildWeeklyTableRevision(WEEK, ZoneOffset.UTC)

        assertEquals(listOf(1L, 2L), revision.report.members.map { it.uid }.sorted())
        assertEquals(listOf(CAPTURED_AT), repository.listSnapshots().map { it.capturedAt })
        assertTrue(repository.listMemberStatuses().all { it.isActive })
        PlatoonRepository.withExclusiveDatabase(scope) {
            readOnly(context.getDatabasePath(scope.databaseName)) { db ->
                assertEquals(PlatoonSchema.CURRENT_VERSION, db.version)
                assertTrue("clock_suspect" in columns(db, "snapshots"))
                assertNoInvalidIdentity(db)
                assertRepairedActivity(db)
            }
        }
        assertTrue(repository.deleteMember(1))
    }

    @Test
    fun aCurrentVersionArchiveCannotBringAUidZeroMemberBack() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1), member(2)), "roster.csv")
        val archive = File(context.cacheDir, "repair-${System.nanoTime()}.db")
        PlatoonRepository.withExclusiveDatabase(scope) {
            context.getDatabasePath(scope.databaseName).copyTo(archive, overwrite = true)
        }
        writable(archive) { db -> insertInvalidIdentityRows(db) }
        val bytes = ByteArrayOutputStream().also { output ->
            BackupArchive.write(output, archive, null, profile)
            archive.delete()
        }.toByteArray()

        PlatoonBackupManager.restoreSelected(context, ByteArrayInputStream(bytes))

        val restored = PlatoonRepository(context, scope)
        assertEquals(
            listOf(1L, 2L),
            restored.buildWeeklyTableRevision(WEEK, ZoneOffset.UTC).report.members.map { it.uid }.sorted(),
        )
        assertEquals(1, restored.listSnapshots().size)
        PlatoonRepository.withExclusiveDatabase(scope) {
            readOnly(context.getDatabasePath(scope.databaseName)) { db ->
                assertNoInvalidIdentity(db)
                assertRepairedActivity(db)
            }
        }
    }

    /**
     * A UID 0 member beside two real ones in the first roster, a later roster holding only that
     * member, and every row an older version could have derived for it.
     */
    private fun insertInvalidIdentityRows(db: SQLiteDatabase) {
        val rosterId = db.rawQuery("SELECT id FROM snapshots", null).use { it.moveToFirst(); it.getLong(0) }
        insertSnapshotMember(db, rosterId, 0)
        val emptiedId = db.insertOrThrow(
            "snapshots",
            null,
            ContentValues().apply {
                put("captured_at", CAPTURED_AT.plusSeconds(3_600).toEpochMilli())
                put("source_file", "zero.csv")
            },
        )
        insertSnapshotMember(db, emptiedId, 0)
        db.execSQL(
            "INSERT INTO members (uid, current_name, current_level, is_active, first_seen_at, last_seen_at) " +
                "VALUES (0, 'Zero', 1, 1, ?, ?)",
            arrayOf<Any>(CAPTURED_AT.toEpochMilli(), CAPTURED_AT.toEpochMilli()),
        )
        val periodId = db.insertOrThrow(
            "membership_periods",
            null,
            ContentValues().apply {
                put("uid", 0)
                put("joined_precision", EvidencePrecision.UNKNOWN.name)
                put("joined_source", EvidenceSource.SNAPSHOT.name)
            },
        )
        db.insertOrThrow(
            "member_events",
            null,
            ContentValues().apply {
                put("uid", 0)
                put("membership_period_id", periodId)
                put("event_type", MemberEventType.JOINED.name)
                put("observed_at", CAPTURED_AT.toEpochMilli())
                put("precision", EvidencePrecision.INFERRED.name)
                put("source", EvidenceSource.SNAPSHOT.name)
            },
        )
        db.execSQL(
            "INSERT INTO weekly_overrides (uid, period_start, game_day, attended) VALUES (0, ?, ?, 1)",
            arrayOf<Any>(WEEK.toEpochDay(), WEEK.plusDays(1).toEpochDay()),
        )
        insertActivity(db, NAMED_AT, "Zero", 0L, ActivityResolution.UNIQUE_ROSTER_NAME)
        insertActivity(db, PATROL_AT, "Zero", 0L, ActivityResolution.EXACT_UPDATE)
        // The same fact once unresolved and once resolved to UID 0: one survives, unresolved.
        insertActivity(db, DUPLICATED_AT, "Zero", null, ActivityResolution.UNRESOLVED)
        insertActivity(db, DUPLICATED_AT, "Zero", 0L, ActivityResolution.UNIQUE_ROSTER_NAME)
    }

    private fun assertNoInvalidIdentity(db: SQLiteDatabase) {
        listOf("members", "snapshot_members", "membership_periods", "member_events", "weekly_overrides")
            .forEach { table -> assertEquals(table, 0L, count(db, table, "uid <= 0")) }
        assertEquals(0L, count(db, "platoon_activity", "resolved_uid <= 0"))
        assertEquals(0L, count(db, "snapshots", "source_file = 'zero.csv'"))
        db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
    }

    private fun assertRepairedActivity(db: SQLiteDatabase) {
        assertEquals(0L, count(db, "platoon_activity", "occurred_at = ${PATROL_AT.toEpochMilli()}"))
        assertEquals(
            1L,
            count(
                db,
                "platoon_activity",
                "occurred_at = ${NAMED_AT.toEpochMilli()} AND resolved_uid IS NULL " +
                    "AND resolution = '${ActivityResolution.UNRESOLVED.name}'",
            ),
        )
        assertEquals(1L, count(db, "platoon_activity", "occurred_at = ${DUPLICATED_AT.toEpochMilli()}"))
    }

    private fun insertSnapshotMember(db: SQLiteDatabase, snapshotId: Long, uid: Long) {
        db.execSQL(
            "INSERT INTO snapshot_members (snapshot_id, uid, name, level, weekly_merit, total_merit, " +
                "high_score, total_score, last_login) VALUES (?, ?, 'Zero', 1, 0, 0, 0, 0, 0)",
            arrayOf<Any>(snapshotId, uid),
        )
    }

    private fun insertActivity(
        db: SQLiteDatabase,
        occurredAt: Instant,
        name: String,
        resolvedUid: Long?,
        resolution: ActivityResolution,
    ) {
        db.insertOrThrow(
            "platoon_activity",
            null,
            ContentValues().apply {
                put("occurred_at", occurredAt.toEpochMilli())
                put("action_id", PlatoonDatabase.DAILY_PATROL_REWARD_ACTION_ID)
                put("kind", 1L)
                put("member_name", name)
                put("captured_at", occurredAt.toEpochMilli())
                putNullableLong("resolved_uid", resolvedUid)
                put("resolution", resolution.name)
            },
        )
    }

    private fun writable(file: File, block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun <T> readOnly(file: File, block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(block)

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun count(db: SQLiteDatabase, table: String, where: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null)
            .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun snapshot(capturedAt: Instant, source: String, vararg uids: Long) = PlatoonSnapshot(
        id = 0,
        capturedAt = capturedAt,
        members = uids.map { SnapshotMember(it, "Member $it", 60, 0, 0, 0, 0, 0) },
        sourceFile = source,
    )

    private fun member(uid: Long) = dev.gf2log.protocol.model.GuildMember(
        uid = uid.toUInt(),
        name = "Member $uid",
        level = 60u,
        weeklyMerit = 0u,
        totalMerit = 0u,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private companion object {
        val CAPTURED_AT: Instant = Instant.parse("2026-08-03T12:00:00Z")
        val NAMED_AT: Instant = CAPTURED_AT.plusSeconds(60)
        val PATROL_AT: Instant = CAPTURED_AT.plusSeconds(120)
        val DUPLICATED_AT: Instant = CAPTURED_AT.plusSeconds(180)
        val WEEK: LocalDate = LocalDate.of(2026, 8, 2)
    }
}
