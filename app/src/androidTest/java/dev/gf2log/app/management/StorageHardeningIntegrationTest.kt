package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.PlatoonProfileData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Restore, deletion, corruption and CSV-claim invariants of the 2.7.2 storage hardening. */
@RunWith(AndroidJUnit4::class)
class StorageHardeningIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var profile: PlatoonProfile
    private lateinit var scope: PlatoonStorageScope

    @Before
    fun setUp() {
        // A fresh scope per test: per-process recovery bookkeeping never carries over.
        profile = PlatoonProfileRegistry(context).upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(Random.nextInt(1_000_000, 9_000_000).toUInt(), "Hardening Platoon", 0u, 0u),
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
    fun restoreRebuildsConstraintsThatIntrospectionCannotSeeIntoTheAppSchema() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1, "One"), member(2, "Two")), "roster.csv")
        val tampered = copyOfLiveDatabase()
        SQLiteDatabase.openDatabase(tampered.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA legacy_alter_table = ON")
            db.execSQL("ALTER TABLE snapshot_members RENAME TO snapshot_members_old")
            db.execSQL(
                """
                CREATE TABLE snapshot_members (
                    snapshot_id INTEGER NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
                    uid INTEGER NOT NULL,
                    name TEXT NOT NULL COLLATE NOCASE,
                    level INTEGER NOT NULL CHECK (level < 1000),
                    weekly_merit INTEGER NOT NULL,
                    total_merit INTEGER NOT NULL,
                    high_score INTEGER NOT NULL,
                    total_score INTEGER NOT NULL,
                    last_login INTEGER NOT NULL,
                    PRIMARY KEY(snapshot_id, uid) ON CONFLICT IGNORE
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO snapshot_members SELECT * FROM snapshot_members_old")
            db.execSQL("DROP TABLE snapshot_members_old")
        }

        PlatoonBackupManager.restoreSelected(context, ByteArrayInputStream(archiveOf(tampered)))

        PlatoonRepository.withExclusiveDatabase(scope) {
            readOnlyLive { live ->
                PlatoonDatabase.inMemory(context, scope).use { reference ->
                    assertEquals(
                        DatabaseSchemaContract.readTableSql(reference.readableDatabase),
                        DatabaseSchemaContract.readTableSql(live),
                    )
                }
                assertEquals(2L, count(live, "snapshot_members"))
            }
        }
        val after = PlatoonRepository(context, scope)
        after.ingest(CAPTURED_AT.plusSeconds(3_600), listOf(member(1, "One", level = 5_000u)), "later.csv")
        assertEquals(2, after.listSnapshots().size)
    }

    @Test
    fun restoreRejectsWeeklyHistoryTheAppCouldNotHaveWritten() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1, "One")), "roster.csv")
        repository.buildWeeklyTableRevision(WEEK, ZoneOffset.UTC)
        val tooMany = copyOfLiveDatabase().also { file ->
            writable(file) { db ->
                repeat(PlatoonDatabase.MAX_WEEKLY_REPORT_HISTORY) {
                    db.execSQL(
                        "INSERT INTO weekly_report_history (period_start, recorded_at, fingerprint, report_blob) " +
                            "SELECT period_start, recorded_at, fingerprint, report_blob FROM weekly_report_history LIMIT 1",
                    )
                }
            }
        }
        val crossWeek = copyOfLiveDatabase().also { file ->
            writable(file) { db ->
                db.execSQL(
                    "INSERT INTO weekly_report_history_state (period_start, active_history_id) " +
                        "SELECT period_start + 7, id FROM weekly_report_history LIMIT 1",
                )
            }
        }
        val undecodable = copyOfLiveDatabase().also { file ->
            writable(file) { db -> db.execSQL("UPDATE weekly_report_history SET report_blob = x'00ff'") }
        }

        listOf(tooMany, crossWeek).forEach { file ->
            assertThrows(InvalidBackupException::class.java) {
                PlatoonBackupManager.restoreSelected(context, ByteArrayInputStream(archiveOf(file)))
            }
        }
        assertEquals(1, PlatoonRepository(context, scope).listMemberStatuses().size)

        // A payload that no longer decodes is derived data the weekly screen already ignores:
        // the restore keeps everything else and leaves the revision behind.
        PlatoonBackupManager.restoreSelected(context, ByteArrayInputStream(archiveOf(undecodable)))
        assertTrue(PlatoonRepository(context, scope).listWeeklyReportHistory(WEEK).isEmpty())
        assertEquals(1, PlatoonRepository(context, scope).listMemberStatuses().size)
    }

    @Test
    fun deletingAMemberRemovesEveryStoredCopyOfThem() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(GONE, "Gone"), member(STAYS, "Stays")), "roster.csv")
        check(repository.updateMember(GONE, "Gone", "private note"))
        val revision = repository.buildWeeklyTableRevision(WEEK, ZoneOffset.UTC)
        assertTrue(GONE in revision.memberPrivateNotesByUid)
        val stored = repository.listWeeklyReportHistory(WEEK).first()
        check(repository.restoreWeeklyReportHistory(stored.id, WEEK))
        CsvImportCheckpointManager(context, scope).apply {
            create(emptySet())
            seal()
        }

        assertTrue(repository.deleteMember(GONE))

        PlatoonRepository.withExclusiveDatabase(scope) {
            readOnlyLive { live ->
                live.rawQuery("SELECT report_blob FROM weekly_report_history", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val decoded = WeeklyReportHistoryCodec.decodeRevision(cursor.getBlob(0))
                        assertFalse(WeeklyHistoryIntegrity.references(decoded, GONE))
                    }
                }
                assertEquals(
                    0L,
                    count(
                        live,
                        "weekly_report_history_state",
                        "active_history_id IS NOT NULL AND active_history_id NOT IN " +
                            "(SELECT id FROM weekly_report_history)",
                    ),
                )
            }
        }
        val shown = PlatoonRepository(context, scope).readWeeklyTableRevision(WEEK, ZoneOffset.UTC)
        assertFalse(WeeklyHistoryIntegrity.references(shown, GONE))
        assertFalse(CsvImportCheckpointManager(context, scope).canUndo())
    }

    @Test
    fun aHeldImportClaimRefusesOtherWritersRestoresAndDeletion() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1, "One")), "roster.csv")
        val archive = ByteArrayOutputStream().also { PlatoonBackupManager(context, scope).export(it) }.toByteArray()
        val applying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val importer = Thread {
            CsvImportCheckpointManager(context, scope).runImport(emptySet()) {
                applying.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }.apply { start() }
        try {
            assertTrue(applying.await(10, TimeUnit.SECONDS))
            assertThrows(CsvImportInProgressException::class.java) {
                repository.updateMember(1, "Nick", "")
            }
            assertThrows(IllegalStateException::class.java) {
                PlatoonBackupManager.restoreSelected(context, ByteArrayInputStream(archive))
            }
            assertFalse(PlatoonProfileAdministration(context).deleteProfile(scope.storageId))
            assertEquals(1, CsvImportCheckpointManager.recoverInterruptedImports(context, listOf(scope)))
        } finally {
            release.countDown()
            importer.join(10_000)
        }
        assertTrue(PlatoonRepository(context, scope).updateMember(1, "Nick", ""))
    }

    @Test
    fun aDeletedPlatoonLeavesNoRestoreCopiesAndIsNotRecreated() {
        PlatoonRepository(context, scope).ingest(CAPTURED_AT, listOf(member(1, "One")), "roster.csv")
        val database = context.getDatabasePath(scope.databaseName)
        val leftovers = listOf(
            File(database.path + ".pre_restore"),
            File(database.path + ".pre_restore-wal"),
            File(database.path + ".corrupt-1"),
            File(context.cacheDir, "platoon-restore/${scope.storageId}/platoon.db.staged"),
        ).onEach { file ->
            file.parentFile?.mkdirs()
            file.writeText("leftover")
        }
        val stale = PlatoonRepository(context, scope)

        assertTrue(PlatoonProfileAdministration(context).deleteProfile(scope.storageId))

        leftovers.forEach { assertFalse(it.path, it.exists()) }
        assertThrows(IllegalStateException::class.java) { stale.listSnapshots() }
        assertThrows(IllegalStateException::class.java) {
            PlatoonBackupManager(context, scope).export(ByteArrayOutputStream())
        }
        assertFalse(database.exists())
        assertFalse(scope.rootDirectory(context).exists())
    }

    @Test
    fun aDamagedDatabaseIsNeitherExportedNorDeleted() {
        val repository = PlatoonRepository(context, scope)
        repeat(40) { index ->
            repository.ingest(
                CAPTURED_AT.plusSeconds(index * 3_600L),
                (1..50).map { member(it.toLong(), "Member $it with a long enough name $index") },
                "roster-$index.csv",
            )
        }
        val database = context.getDatabasePath(scope.databaseName)
        PlatoonRepository.withExclusiveDatabase(scope) {
            RandomAccessFile(database, "rw").use { file ->
                val pageSize = 4_096L
                var page = 1L
                while ((page + 1) * pageSize <= file.length()) {
                    file.seek(page * pageSize)
                    file.write(ByteArray(64) { 0x5a })
                    page += 1
                }
            }
        }

        val output = ByteArrayOutputStream()
        val failure = runCatching { PlatoonBackupManager(context, scope).export(output) }

        assertTrue(failure.exceptionOrNull().toString(), failure.exceptionOrNull() is PlatoonDatabaseDamagedException)
        assertEquals(0, output.size())
        // Either still in place (integrity check) or moved aside by the corruption handler.
        val kept = (listOf(database) + PlatoonDatabaseDamage.quarantinedFiles(context, scope)).filter(File::isFile)
        assertTrue(kept.any { it.length() >= 4_096L })
    }

    @Test
    fun aQuarantinedDatabaseIsNeverRecreatedEmptyUntilARestoreOrRebuild() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1, "One")), "roster.csv")
        val archive = ByteArrayOutputStream().also { PlatoonBackupManager(context, scope).export(it) }.toByteArray()
        val database = context.getDatabasePath(scope.databaseName)
        PlatoonRepository.withExclusiveDatabase(scope) {
            PlatoonDatabaseDamage.quarantine(context, scope, database)
        }

        assertThrows(PlatoonDatabaseDamagedException::class.java) { repository.listSnapshots() }
        assertFalse(database.exists())
        assertEquals(1, PlatoonDatabaseDamage.quarantinedFiles(context, scope).size)

        PlatoonBackupManager.restoreSelected(context, ByteArrayInputStream(archive))
        assertEquals(1, PlatoonRepository(context, scope).listSnapshots().size)
        assertFalse(PlatoonDatabaseDamage.isDamaged(context, scope))
    }

    @Test
    fun aStaleRollbackCopyCannotReplaceLiveDataDuringARestore() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1, "One")), "roster.csv")
        val archive = ByteArrayOutputStream().also { PlatoonBackupManager(context, scope).export(it) }.toByteArray()
        repository.ingest(CAPTURED_AT.plusSeconds(60), listOf(member(1, "One"), member(2, "Two")), "later.csv")
        val manager = PlatoonBackupManager(context, scope)
        val before = manager.currentDatabaseSha256()
        // A copy that cannot be deleted; before 2.7.2 the rollback installed it as the database.
        File(context.getDatabasePath(scope.databaseName).path + ".pre_restore").apply {
            mkdirs()
            File(this, "stale").writeText("stale")
        }

        assertThrows(IllegalStateException::class.java) { manager.restore(ByteArrayInputStream(archive)) }

        assertEquals(before, PlatoonBackupManager(context, scope).currentDatabaseSha256())
        assertFalse(File(scope.rootDirectory(context), "platoon-full-restore").exists())
        File(context.getDatabasePath(scope.databaseName).path + ".pre_restore").deleteRecursively()
    }

    @Test
    fun anExpiredUndoCheckpointIsDiscarded() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1, "One")), "roster.csv")
        CsvImportCheckpointManager(context, scope).apply {
            create(emptySet())
            seal()
        }
        repository.ingest(CAPTURED_AT.plusSeconds(60), listOf(member(1, "One"), member(2, "Two")), "later.csv")

        assertThrows(CsvCheckpointExpiredException::class.java) {
            CsvImportCheckpointManager(context, scope).restore()
        }
        assertFalse(CsvImportCheckpointManager(context, scope).canUndo())
        assertEquals(2, PlatoonRepository(context, scope).listSnapshots().size)
    }

    @Test
    fun aWeeklyRevisionBuiltForAnOldResetZoneIsNotRecorded() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1, "One")), "roster.csv")
        val stale: ZoneId = ZoneId.of("Asia/Seoul")

        val before = repository.listWeeklyReportHistory(WEEK).map { it.id }

        // A screen still holding the previous zone renders, but its projection is not stored.
        repository.buildWeeklyTableRevision(WEEK, stale)

        assertEquals(before, repository.listWeeklyReportHistory(WEEK).map { it.id })
    }

    @Test
    fun constructingARepositoryOnTheMainThreadNeverWaitsForTheMaintenanceGate() {
        // A transaction directory without state only needs cleanup, which takes the exclusive gate.
        val transaction = File(scope.rootDirectory(context), "platoon-full-restore").apply { mkdirs() }
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            PlatoonRepository.withExclusiveDatabase(scope) {
                holding.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }.apply { start() }
        var repository: PlatoonRepository? = null
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val started = System.nanoTime()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                repository = PlatoonRepository(context, scope)
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
        } finally {
            release.countDown()
            holder.join(10_000)
        }
        requireNotNull(repository).listSnapshots()
        assertFalse(transaction.exists())
    }

    @Test
    fun anInterruptedImportRollsBackBeforeTheFirstEditOfTheProcess() {
        // Seeded below the repository so this scope's one-time CSV recovery has not run yet.
        PlatoonDatabase(context, scope.databaseName).use { database ->
            database.ingestSnapshot(snapshot(CAPTURED_AT, "before.csv", 1), EvidenceSource.SNAPSHOT)
        }
        val root = File(scope.rootDirectory(context), "csv-import-checkpoint").apply { mkdirs() }
        PlatoonRepository.withExclusiveDatabase(scope) {
            File(root, "platoon.gf2backup").outputStream().use { output ->
                BackupArchive.write(output, context.getDatabasePath(scope.databaseName), null, profile)
            }
        }
        File(root, "planned-files.txt").writeText("\n")
        // The interrupted import's own change, never sealed.
        PlatoonDatabase(context, scope.databaseName).use { database ->
            database.ingestSnapshot(snapshot(CAPTURED_AT.plusSeconds(60), "import.csv", 1, 2), EvidenceSource.SNAPSHOT)
        }

        assertTrue(PlatoonRepository(context, scope).updateMember(1, "Nick", "kept"))

        val members = PlatoonRepository(context, scope).listMemberStatuses()
        assertEquals(listOf(1L), members.map { it.uid })
        assertEquals("Nick", members.single().name)
        assertFalse(root.exists())
    }

    private fun copyOfLiveDatabase(): File {
        val copy = File(context.cacheDir, "hardening-${System.nanoTime()}.db")
        PlatoonRepository.withExclusiveDatabase(scope) {
            context.getDatabasePath(scope.databaseName).copyTo(copy, overwrite = true)
        }
        return copy
    }

    private fun archiveOf(database: File): ByteArray = ByteArrayOutputStream().also { output ->
        BackupArchive.write(output, database, null, profile)
        database.delete()
    }.toByteArray()

    private fun writable(file: File, block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)

    private fun <T> readOnlyLive(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(
        context.getDatabasePath(scope.databaseName).path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use(block)

    private fun count(db: SQLiteDatabase, table: String, where: String? = null): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table" + (where?.let { " WHERE $it" } ?: ""), null)
            .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun snapshot(capturedAt: Instant, source: String, vararg uids: Long) = PlatoonSnapshot(
        id = 0,
        capturedAt = capturedAt,
        members = uids.map { SnapshotMember(it, "Member $it", 60, 0, 0, 0, 0, 0) },
        sourceFile = source,
    )

    private fun member(uid: Long, name: String, level: UInt = 60u) = GuildMember(
        uid = uid.toUInt(),
        name = name,
        level = level,
        weeklyMerit = 0u,
        totalMerit = 0u,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private companion object {
        const val GONE = 4242L
        const val STAYS = 4343L
        val CAPTURED_AT: Instant = Instant.parse("2026-08-03T12:00:00Z")
        val WEEK: LocalDate = LocalDate.of(2026, 8, 2)
    }
}
