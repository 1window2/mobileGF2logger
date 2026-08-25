package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.TargetPackagePreferences
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.WeeklyPngPendingState
import dev.gf2log.app.WeeklyReportActivity
import dev.gf2log.app.settings.AppBackupSettings
import dev.gf2log.app.settings.AppBackupSettingsCodec
import dev.gf2log.app.settings.AppSettingsStore
import dev.gf2log.app.settings.BackupSettingsStore
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.ScopedAppSettingsStore
import dev.gf2log.app.settings.WeeklyCutlines
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.PayloadCatalog
import dev.gf2log.protocol.model.GuildMember
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatoonBackupManagerIntegrationTest {
    private lateinit var context: Context
    private lateinit var settingsStore: BackupSettingsStore
    private lateinit var storageScope: PlatoonStorageScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearState()
        val profile = PlatoonProfileRegistry(context).upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            dev.gf2log.protocol.model.PlatoonProfileData(
                101817u,
                "Backup test Platoon",
                emptyList(),
                emptyList(),
            ),
        )
        check(PlatoonProfileRegistry(context).setActive(profile.storageId))
        storageScope = PlatoonStorageScope(profile.storageId)
        settingsStore = ScopedAppSettingsStore(context, storageScope.storageId)
    }

    @After
    fun tearDown() {
        clearState()
    }

    @Test
    fun completeBackupRoundTripsSettingsAndEveryManagementTable() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).exportFull(it)
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        writeRetainedCsv(CURRENT_UID, "Cached current member")
        PlatoonBackupManager(context).restoreFull(ByteArrayInputStream(archive))

        assertEquals(archivedSettings(), settingsStore.read())
        assertFalse(FilePaths.retainedCsvDirectory(context).exists())
        assertTrue(FilePaths.retiredCsvCleanupDirectories(context).isEmpty())
        assertEquals(
            PlatoonRepository.ImportResult(0, 0, 0, 0),
            PlatoonRepository(context).reconcileRetainedCsvFiles(),
        )
        PlatoonDatabase(context).use { database ->
            val db = database.readableDatabase
            assertEquals(1L, count(db, "members", "uid = ?", ARCHIVED_UID))
            assertEquals(1L, count(db, "membership_periods", "uid = ?", ARCHIVED_UID))
            assertEquals(1L, count(db, "member_events", "uid = ?", ARCHIVED_UID))
            assertEquals(1L, count(db, "snapshot_members", "uid = ?", ARCHIVED_UID))
            assertEquals(1L, count(db, "weekly_overrides", "uid = ?", ARCHIVED_UID))
            assertEquals(1L, count(db, "platoon_activity", "resolved_uid = ?", ARCHIVED_UID))
            assertEquals(1L, count(db, "weekly_notes", "text = ?", ARCHIVED_NOTE))
            assertEquals(0L, count(db, "members", "uid = ?", CURRENT_UID))
            assertFalse(db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
        }
    }

    @Test
    fun schemaTenCompleteBackupMigratesBeforeCurrentContractValidation() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val databaseFile = context.getDatabasePath(storageScope.databaseName)
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { legacy ->
            legacy.execSQL("DROP INDEX platoon_activity_resolution_retention")
            legacy.execSQL("DROP INDEX platoon_activity_retention_order")
            legacy.execSQL("DROP TABLE platoon_maintenance_state")
            legacy.version = 10
        }
        val archive = ByteArrayOutputStream().also { output ->
            BackupArchive.write(
                output,
                databaseFile,
                AppBackupSettingsCodec.encode(archivedSettings()),
                PlatoonProfileRegistry(context).active(),
            )
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        PlatoonBackupManager(context).restoreFull(ByteArrayInputStream(archive))

        assertEquals(archivedSettings(), settingsStore.read())
        PlatoonDatabase(context).use { database ->
            val restored = database.readableDatabase
            assertEquals(PlatoonSchema.CURRENT_VERSION, restored.version)
            assertEquals(1L, count(restored, "members", "uid = ?", ARCHIVED_UID))
            assertEquals(
                1L,
                restored.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE type = 'table' AND name = 'platoon_maintenance_state'",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getLong(0)
                },
            )
        }
    }

    @Test
    fun platoonOnlyRestoreRetiresUnrelatedRetainedRosterEvidence() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).export(it)
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        TargetPackagePreferences.set(context, INVALID_TARGET_PACKAGE)
        writeRetainedCsv(CURRENT_UID, "Cached current member")
        PlatoonBackupManager(context).restore(ByteArrayInputStream(archive))

        assertEquals(
            currentSettings().copy(targetPackage = INVALID_TARGET_PACKAGE),
            settingsStore.read(),
        )
        assertFalse(FilePaths.retainedCsvDirectory(context).exists())
        assertEquals(
            PlatoonRepository.ImportResult(0, 0, 0, 0),
            PlatoonRepository(context).reconcileRetainedCsvFiles(),
        )
        PlatoonDatabase(context).use { database ->
            val db = database.readableDatabase
            assertEquals(1L, count(db, "members", "uid = ?", ARCHIVED_UID))
            assertEquals(0L, count(db, "members", "uid = ?", CURRENT_UID))
            assertFalse(db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
        }
        assertFalse(FilePaths.preRestoreDatabase(context).exists())
        assertFalse(FilePaths.previousRetainedCsvDirectory(context).exists())
        assertFalse(FilePaths.restoreTransactionDirectory(context).exists())
    }

    @Test
    fun platoonOnlyRollbackDoesNotReadOrReplaceUnrelatedSettings() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).export(it)
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        TargetPackagePreferences.set(context, INVALID_TARGET_PACKAGE)
        val retainedCsv = writeRetainedCsv(CURRENT_UID, "Cached current member")
        val inaccessibleSettings = object : BackupSettingsStore {
            override fun read(): AppBackupSettings = error("Legacy restore must not read settings")

            override fun replace(settings: AppBackupSettings) {
                error("Legacy restore must not replace settings")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            PlatoonBackupManager(
                context = context,
                settingsStore = inaccessibleSettings,
                restoreObserver = { checkpoint ->
                    if (checkpoint == PlatoonBackupManager.RestoreCheckpoint.RETAINED_CSV_RETIRED) {
                        error("simulated legacy restore failure")
                    }
                },
            ).restore(ByteArrayInputStream(archive))
        }

        assertEquals(INVALID_TARGET_PACKAGE, TargetPackagePreferences.get(context))
        assertTrue(retainedCsv.isFile)
        PlatoonDatabase(context).use { database ->
            val db = database.readableDatabase
            assertEquals(1L, count(db, "members", "uid = ?", CURRENT_UID))
            assertEquals(0L, count(db, "members", "uid = ?", ARCHIVED_UID))
        }
        assertFalse(FilePaths.preRestoreDatabase(context).exists())
        assertFalse(FilePaths.previousRetainedCsvDirectory(context).exists())
        assertFalse(FilePaths.restoreTransactionDirectory(context).exists())
    }

    @Test
    fun corruptOrTruncatedZipReadFailureIsClassifiedAsAnInvalidBackup() {
        listOf(
            ZipException("corrupt compressed data"),
            EOFException("truncated compressed data"),
        ).forEach { readFailure ->
            val invalidArchive = object : InputStream() {
                override fun read(): Int = throw readFailure
            }

            assertThrows(InvalidBackupException::class.java) {
                PlatoonBackupManager(context).restoreFull(invalidArchive)
            }
        }
    }

    @Test
    fun preMarkerPreparedRestoreTransactionStillRecoversSettings() {
        settingsStore.replace(currentSettings())
        FilePaths.restoreSettingsFile(context).apply {
            parentFile?.mkdirs()
            writeBytes(AppBackupSettingsCodec.encode(archivedSettings()))
        }
        FilePaths.restoreStateFile(context).writeText("PREPARED", Charsets.US_ASCII)

        PlatoonBackupManager.recoverInterruptedFullRestore(context, settingsStore)

        assertEquals(archivedSettings(), settingsStore.read())
        assertFalse(FilePaths.restoreTransactionDirectory(context).exists())
    }

    @Test
    fun settingsFailureRollsBackDatabaseAndSettingsTogether() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).exportFull(it)
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        val retainedCsv = writeRetainedCsv(CURRENT_UID, "Cached current member")
        val failingStore = object : BackupSettingsStore {
            private var replacements = 0

            override fun read(): AppBackupSettings = settingsStore.read()

            override fun replace(settings: AppBackupSettings) {
                settingsStore.replace(settings)
                replacements += 1
                if (replacements == 1) error("simulated post-write settings failure")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            PlatoonBackupManager(context, failingStore)
                .restoreFull(ByteArrayInputStream(archive))
        }

        assertEquals(currentSettings(), settingsStore.read())
        assertTrue(retainedCsv.isFile)
        PlatoonDatabase(context).use { database ->
            val db = database.readableDatabase
            assertEquals(1L, count(db, "members", "uid = ?", CURRENT_UID))
            assertEquals(0L, count(db, "members", "uid = ?", ARCHIVED_UID))
            assertFalse(db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
        }
        assertFalse(FilePaths.preRestoreDatabase(context).exists())
    }

    @Test
    fun freshInstallFailureRestoresTheMissingDatabaseState() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).exportFull(it)
        }.toByteArray()

        PlatoonRepository.withExclusiveDatabase(storageScope) {
            assertTrue(context.deleteDatabase(storageScope.databaseName))
        }
        settingsStore.replace(currentSettings())

        assertThrows(IllegalStateException::class.java) {
            PlatoonBackupManager(
                context = context,
                settingsStore = settingsStore,
                restoreObserver = { checkpoint ->
                    if (checkpoint == PlatoonBackupManager.RestoreCheckpoint.DATABASE_INSTALLED) {
                        error("simulated fresh-install restore failure")
                    }
                },
            ).restoreFull(ByteArrayInputStream(archive))
        }

        assertEquals(currentSettings(), settingsStore.read())
        assertFalse(context.getDatabasePath(storageScope.databaseName).exists())
        assertFalse(FilePaths.preRestoreDatabase(context).exists())
        assertFalse(FilePaths.restoreTransactionDirectory(context).exists())
    }

    @Test
    fun staleRetiredCsvCleanupArtifactDoesNotBlockACompleteRestore() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).exportFull(it)
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        val stale = java.io.File(
            FilePaths.restoreDirectory(context),
            "guild-members.retired-stale",
        ).apply { mkdirs() }
        java.io.File(stale, "obsolete.csv").writeText("obsolete", Charsets.UTF_8)

        PlatoonBackupManager(context).restoreFull(ByteArrayInputStream(archive))

        assertEquals(archivedSettings(), settingsStore.read())
        assertTrue(FilePaths.retiredCsvCleanupDirectories(context).isEmpty())
        PlatoonDatabase(context).use { database ->
            assertEquals(
                1L,
                count(database.readableDatabase, "members", "uid = ?", ARCHIVED_UID),
            )
        }
    }

    @Test
    fun postRetirementFailureRollsBackDatabaseSettingsAndRetainedCsvTogether() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).exportFull(it)
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        val retainedCsv = writeRetainedCsv(CURRENT_UID, "Cached current member")

        assertThrows(IllegalStateException::class.java) {
            PlatoonBackupManager(
                context = context,
                settingsStore = settingsStore,
                restoreObserver = { checkpoint ->
                    if (checkpoint == PlatoonBackupManager.RestoreCheckpoint.RETAINED_CSV_RETIRED) {
                        error("simulated post-retirement failure")
                    }
                },
            ).restoreFull(ByteArrayInputStream(archive))
        }

        assertEquals(currentSettings(), settingsStore.read())
        assertTrue(retainedCsv.isFile)
        assertFalse(FilePaths.previousRetainedCsvDirectory(context).exists())
        PlatoonDatabase(context).use { database ->
            val db = database.readableDatabase
            assertEquals(1L, count(db, "members", "uid = ?", CURRENT_UID))
            assertEquals(0L, count(db, "members", "uid = ?", ARCHIVED_UID))
            assertFalse(db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
        }
        assertFalse(FilePaths.preRestoreDatabase(context).exists())
    }

    @Test
    fun pendingRetainedCsvRollbackStateRecoversBeforeTheNextRestore() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).exportFull(it)
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        FilePaths.retainedCsvDirectory(context).deleteRecursively()
        val pending = java.io.File(
            FilePaths.previousRetainedCsvDirectory(context).apply { mkdirs() },
            "pending.csv",
        ).apply { writeText("pending", Charsets.UTF_8) }

        PlatoonBackupManager(context).restoreFull(ByteArrayInputStream(archive))

        assertFalse(pending.exists())
        assertFalse(FilePaths.previousRetainedCsvDirectory(context).exists())
        assertEquals(archivedSettings(), settingsStore.read())
        PlatoonDatabase(context).use { database ->
            assertEquals(
                1L,
                count(database.readableDatabase, "members", "uid = ?", ARCHIVED_UID),
            )
        }
    }

    @Test
    fun liveAndPendingRetainedCsvStatesRejectRestoreWithoutDeletingEither() {
        seedDatabase(ARCHIVED_UID, "Archived member", "archived-source.csv")
        settingsStore.replace(archivedSettings())
        val archive = ByteArrayOutputStream().also {
            PlatoonBackupManager(context).exportFull(it)
        }.toByteArray()

        replaceDatabaseWithCurrentState()
        settingsStore.replace(currentSettings())
        val live = writeRetainedCsv(CURRENT_UID, "Live cached member")
        val pending = java.io.File(
            FilePaths.previousRetainedCsvDirectory(context).apply { mkdirs() },
            "pending.csv",
        ).apply { writeText("pending", Charsets.UTF_8) }

        assertThrows(IllegalArgumentException::class.java) {
            PlatoonBackupManager(context).restoreFull(ByteArrayInputStream(archive))
        }

        assertTrue(live.isFile)
        assertTrue(pending.isFile)
        assertEquals(currentSettings(), settingsStore.read())
        PlatoonDatabase(context).use { database ->
            assertEquals(
                1L,
                count(database.readableDatabase, "members", "uid = ?", CURRENT_UID),
            )
        }
    }

    @Test
    fun failedCsvImportPreservesThePreviousSuccessfulUndoCheckpoint() {
        seedDatabase(ARCHIVED_UID, "Before first import", "before-first-import.csv")
        val firstImport = CsvImportCheckpointManager(context, storageScope)
        firstImport.create(emptySet())
        replaceDatabaseForCheckpoint(CURRENT_UID, "After first import", "after-first-import.csv")
        firstImport.seal()

        val failedImport = CsvImportCheckpointManager(context, storageScope)
        failedImport.create(emptySet())
        replaceDatabaseForCheckpoint(THIRD_UID, "Failed second import", "failed-import.csv")
        failedImport.rollbackFailedImport()

        PlatoonDatabase(context).use { database ->
            val db = database.readableDatabase
            assertEquals(1L, count(db, "members", "uid = ?", CURRENT_UID))
            assertEquals(0L, count(db, "members", "uid = ?", THIRD_UID))
        }

        val previousUndo = CsvImportCheckpointManager(context, storageScope)
        assertTrue(previousUndo.canUndo())
        previousUndo.restore()

        PlatoonDatabase(context).use { database ->
            val db = database.readableDatabase
            assertEquals(1L, count(db, "members", "uid = ?", ARCHIVED_UID))
            assertEquals(0L, count(db, "members", "uid = ?", CURRENT_UID))
        }
        assertFalse(CsvImportCheckpointManager(context, storageScope).canUndo())
    }

    @Test
    fun interruptedCsvUndoCompletesAfterDatabaseInstallationOnRestart() {
        seedDatabase(ARCHIVED_UID, "Before import", "before-import.csv")
        val manager = CsvImportCheckpointManager(context, storageScope) { checkpoint ->
            if (checkpoint == PlatoonBackupManager.RestoreCheckpoint.DATABASE_INSTALLED) {
                throw SimulatedProcessDeath()
            }
        }
        manager.create(emptySet())
        replaceDatabaseForCheckpoint(CURRENT_UID, "After import", "after-import.csv")
        manager.seal()

        assertThrows(SimulatedProcessDeath::class.java) {
            manager.restore()
        }

        val recovered = CsvImportCheckpointManager(context, storageScope)

        assertFalse(recovered.canUndo())
        PlatoonDatabase(context).use { database ->
            val db = database.readableDatabase
            assertEquals(1L, count(db, "members", "uid = ?", ARCHIVED_UID))
            assertEquals(0L, count(db, "members", "uid = ?", CURRENT_UID))
        }
        assertFalse(FilePaths.csvCheckpointDirectory(context).exists())
        assertFalse(FilePaths.csvCheckpointStagingDirectory(context).exists())
        assertFalse(FilePaths.csvCheckpointPreviousDirectory(context).exists())
        assertFalse(FilePaths.restoreTransactionDirectory(context).exists())
    }

    @Test
    fun unfinishedCsvImportRecoversBeforePreviewRepositoryReads() {
        seedDatabase(ARCHIVED_UID, "Before interrupted import", "before-interrupted-import.csv")
        val checkpoint = FilePaths.csvCheckpointDirectory(context)
        assertTrue(checkpoint.mkdirs())
        java.io.FileOutputStream(java.io.File(checkpoint, "platoon.gf2backup")).use { output ->
            PlatoonBackupManager(context).export(output)
        }
        val plannedFileName = "import-20260811T000000Z-00000000000000000006.csv"
        java.io.File(checkpoint, "planned-files.txt").writeText(
            "$plannedFileName\n",
            Charsets.UTF_8,
        )
        replaceDatabaseForCheckpoint(CURRENT_UID, "Interrupted import", plannedFileName)
        val retained = java.io.File(
            storageScope.rootDirectory(context),
            PlatoonRepository.RETAINED_CSV_DIRECTORY,
        ).apply { mkdirs() }
        java.io.File(retained, plannedFileName).writeText(
            "interrupted evidence",
            Charsets.UTF_8,
        )

        CsvImportCheckpointManager(context, storageScope)

        val members = PlatoonRepository(context).listMemberStatuses()
        assertTrue(members.any { it.uid == ARCHIVED_UID })
        assertFalse(members.any { it.uid == CURRENT_UID })
        assertFalse(java.io.File(retained, plannedFileName).exists())
        assertFalse(FilePaths.csvCheckpointDirectory(context).exists())
    }

    @Test
    fun malformedCompleteBackupDoesNotMutateExistingState() {
        seedDatabase(CURRENT_UID, "Current member", "current-source.csv")
        settingsStore.replace(currentSettings())

        assertThrows(InvalidBackupException::class.java) {
            PlatoonBackupManager(context).restoreFull(
                ByteArrayInputStream("not a mobileGF2logger backup".toByteArray()),
            )
        }

        assertEquals(currentSettings(), settingsStore.read())
        PlatoonDatabase(context).use { database ->
            assertEquals(
                1L,
                count(database.readableDatabase, "members", "uid = ?", CURRENT_UID),
            )
        }
    }

    @Test
    fun interruptedScopedRestoreRollsBackProfileSelectionAndCaptureRegion() {
        val registry = PlatoonProfileRegistry(context)
        val original = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            dev.gf2log.protocol.model.PlatoonProfileData(
                100u,
                "Original",
                emptyList(),
                emptyList(),
            ),
        )
        val restored = registry.upsertDetected(
            SupportedGamePackages.DARKWINTER,
            GameServerRegion.DARKWINTER_GLOBAL,
            dev.gf2log.protocol.model.PlatoonProfileData(
                200u,
                "Restored",
                listOf(1u),
                listOf(2u),
            ),
        )
        assertTrue(registry.setActive(restored.storageId))
        PlatoonRepository(context, PlatoonStorageScope(restored.storageId)).ingest(
            Instant.parse("2026-08-24T03:00:00Z"),
            listOf(
                GuildMember(
                    uid = THIRD_UID.toUInt(),
                    name = "Restored member",
                    level = 1u,
                    weeklyMerit = 0u,
                    totalMerit = 0u,
                    highScore = 0u,
                    totalScore = 0u,
                    lastLogin = 0u,
                ),
            ),
            "restored.csv",
        )
        val archive = ByteArrayOutputStream().also { output ->
            PlatoonBackupManager(context).exportFull(output)
        }.toByteArray()

        assertTrue(registry.setActive(original.storageId))
        assertTrue(registry.forget(restored.storageId))
        val restoredScope = PlatoonStorageScope(restored.storageId)
        PlatoonRepository.withExclusiveDatabase(restoredScope) {
            assertTrue(context.deleteDatabase(restoredScope.databaseName))
        }
        ClientServerRegionPreferences(context).set(
            SupportedGamePackages.DARKWINTER,
            GameServerRegion.DARKWINTER_CHINA,
        )

        assertThrows(SimulatedProcessDeath::class.java) {
            PlatoonBackupManager(
                context = context,
                settingsStore = dev.gf2log.app.settings.ScopedAppSettingsStore(
                    context,
                    original.storageId,
                ),
                restoreObserver = { checkpoint ->
                    if (checkpoint == PlatoonBackupManager.RestoreCheckpoint.PROFILE_METADATA_INSTALLED) {
                        throw SimulatedProcessDeath()
                    }
                },
                storageScope = PlatoonStorageScope(original.storageId),
            ).restoreFull(ByteArrayInputStream(archive))
        }

        assertEquals(restored.storageId, registry.activeScope().storageId)
        assertEquals("Restored", registry.find(restored.storageId)?.platoonName)
        PlatoonBackupManager.recoverInterruptedFullRestore(context)

        assertEquals(original.storageId, registry.activeScope().storageId)
        assertEquals(null, registry.find(restored.storageId))
        assertEquals(
            GameServerRegion.DARKWINTER_CHINA,
            ClientServerRegionPreferences(context).get(SupportedGamePackages.DARKWINTER),
        )
        assertFalse(context.getDatabasePath(restoredScope.databaseName).exists())
    }

    private fun replaceDatabaseWithCurrentState() {
        replaceDatabaseForCheckpoint(CURRENT_UID, "Current member", "current-source.csv")
    }

    private fun replaceDatabaseForCheckpoint(uid: Long, name: String, sourceFile: String) {
        PlatoonRepository.withExclusiveDatabase(storageScope) {
            context.deleteDatabase(storageScope.databaseName)
        }
        seedDatabase(uid, name, sourceFile)
    }

    private fun seedDatabase(uid: Long, name: String, sourceFile: String) {
        val capturedAt = Instant.parse("2026-07-31T00:00:00Z")
        PlatoonDatabase(context).use { database ->
            database.ingestSnapshot(
                PlatoonSnapshot(
                    id = 0,
                    capturedAt = capturedAt,
                    sourceFile = sourceFile,
                    members = listOf(member(uid, name)),
                ),
                EvidenceSource.SNAPSHOT,
            )
            val db = database.writableDatabase
            val membershipPeriodId = queryLong(
                db,
                "SELECT id FROM membership_periods WHERE uid = ? LIMIT 1",
                uid,
            )
            val eventId = db.insertOrThrow(
                "member_events",
                null,
                ContentValues().apply {
                    put("uid", uid)
                    put("membership_period_id", membershipPeriodId)
                    put("event_type", MemberEventType.JOINED.name)
                    put("occurred_at", capturedAt.toEpochMilli())
                    put("event_date", PERIOD_START.toEpochDay())
                    put("time_known", 1)
                    put("observed_at", capturedAt.toEpochMilli())
                    put("precision", EvidencePrecision.EXACT.name)
                    put("source", EvidenceSource.GAME_UPDATES.name)
                    put("note", name)
                },
            )
            db.insertOrThrow(
                "weekly_overrides",
                null,
                ContentValues().apply {
                    put("uid", uid)
                    put("period_start", PERIOD_START.toEpochDay())
                    put("game_day", PERIOD_START.toEpochDay())
                    put("merit_delta", 90L)
                    put("attended", 1)
                },
            )
            db.insertOrThrow(
                "weekly_notes",
                null,
                ContentValues().apply {
                    put("period_start", PERIOD_START.toEpochDay())
                    put("game_day", PERIOD_START.toEpochDay())
                    put("text", ARCHIVED_NOTE)
                    put("event_id", eventId)
                    put("is_automatic", 0)
                },
            )
            db.insertOrThrow(
                "platoon_activity",
                null,
                ContentValues().apply {
                    put("occurred_at", capturedAt.toEpochMilli())
                    put("action_id", 802001L)
                    put("kind", 1L)
                    put("member_name", name)
                    put("captured_at", capturedAt.toEpochMilli())
                    put("resolved_uid", uid)
                    put("resolution", ActivityResolution.EXACT_UPDATE.name)
                    put("member_event_id", eventId)
                },
            )
        }
    }

    private fun archivedSettings() = settings(
        language = "ko",
        detailedNotifications = false,
        targetPackage = "com.example.archived",
        memberOrder = listOf(ARCHIVED_UID),
        dailyMerit = 90,
    )

    private fun currentSettings() = settings(
        language = "en",
        detailedNotifications = true,
        targetPackage = "com.example.current",
        memberOrder = listOf(CURRENT_UID),
        dailyMerit = 50,
    )

    private fun settings(
        language: String,
        detailedNotifications: Boolean,
        targetPackage: String,
        memberOrder: List<Long>,
        dailyMerit: Long,
    ) = AppBackupSettings(
        language = language,
        themeMode = "system",
        gameServerRegion = dev.gf2log.app.settings.GameServerRegion.MANUAL.storedValue,
        gameTimeZoneId = "Asia/Seoul",
        onboardingCompleted = true,
        detailedNotifications = detailedNotifications,
        targetPackage = targetPackage,
        payloadHistory = PayloadCatalog.categories.associate { category ->
            category.payloadType to (category.isRequired || detailedNotifications)
        },
        memberOrder = memberOrder,
        weeklyCutlines = WeeklyCutlines(
            dailyMerit = dailyMerit,
            dailyGunsmokeScore = 10_000,
            dailyGunsmokeAttempts = 3,
            weeklyMerit = 630,
            weeklyGunsmokeScore = 70_000,
            weeklyGunsmokeAttempts = 21,
            weeklyLoginDays = 7,
            weeklyPatrolDays = 7,
        ),
    )

    private fun member(uid: Long, name: String) = SnapshotMember(
        uid = uid,
        name = name,
        level = 60,
        weeklyMerit = 630,
        totalMerit = 4_560,
        highScore = 789,
        totalScore = 1_234,
        lastLogin = 1_700_000_000,
    )

    private fun writeRetainedCsv(uid: Long, name: String): java.io.File {
        val capturedAt = "2026-07-31T01:00:00Z"
        return java.io.File(
            FilePaths.retainedCsvDirectory(context).apply { mkdirs() },
            "gf2log_platoonmembers_20260731T010000Z.csv",
        ).apply {
            writeText(
                listOf(
                    GuildMembersCsv.HEADER,
                    GuildMembersCsv.row(
                        GuildMember(
                            uid = uid.toUInt(),
                            name = name,
                            level = 60u,
                            weeklyMerit = 630u,
                            totalMerit = 4_560u,
                            highScore = 789u,
                            totalScore = 1_234u,
                            lastLogin = 1_700_000_000u,
                        ),
                        capturedAt,
                    ),
                ).joinToString("\n"),
                Charsets.UTF_8,
            )
        }
    }

    private fun count(
        database: android.database.sqlite.SQLiteDatabase,
        table: String,
        selection: String,
        value: Any,
    ): Long = database.rawQuery(
        "SELECT COUNT(*) FROM $table WHERE $selection",
        arrayOf(value.toString()),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun queryLong(
        database: android.database.sqlite.SQLiteDatabase,
        sql: String,
        value: Long,
    ): Long = database.rawQuery(sql, arrayOf(value.toString())).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun clearState() {
        context.databaseList()
            .filter {
                it == PlatoonSchema.DATABASE_NAME ||
                    it.matches(Regex("platoon-[0-9a-f]{32}\\.db"))
            }
            .forEach { databaseName ->
                val scope = PlatoonStorageScope.fromDatabaseName(databaseName)
                runCatching {
                    PlatoonRepository.withExclusiveDatabase(scope) {
                        context.deleteDatabase(databaseName)
                    }
                }
            }
        java.io.File(context.cacheDir, "platoon-restore").deleteRecursively()
        java.io.File(context.filesDir, "platoon-full-restore").deleteRecursively()
        java.io.File(context.filesDir, PlatoonRepository.RETAINED_CSV_DIRECTORY).deleteRecursively()
        java.io.File(
            context.filesDir,
            "${PlatoonRepository.RETAINED_CSV_DIRECTORY}.pre_restore",
        ).deleteRecursively()
        java.io.File(context.filesDir, "csv-import-checkpoint").deleteRecursively()
        java.io.File(context.filesDir, "csv-import-checkpoint.staging").deleteRecursively()
        java.io.File(context.filesDir, "csv-import-checkpoint.previous").deleteRecursively()
        java.io.File(context.filesDir, "platoons").deleteRecursively()
        context.getSharedPreferences(USER_SETTINGS, Context.MODE_PRIVATE).edit().clear().commit()
        listOf(
            "platoon_profiles",
            "platoon_member_order",
            "platoon_weekly_cutlines",
            "platoon_timezones",
        ).forEach { preferences ->
            context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private object FilePaths {
        private fun scope(context: Context) = PlatoonProfileRegistry(context).activeScope()

        fun restoreDirectory(context: Context) = java.io.File(
            context.cacheDir,
            "platoon-restore/${scope(context).storageId}",
        )

        fun restoreTransactionDirectory(context: Context) =
            java.io.File(scope(context).rootDirectory(context), "platoon-full-restore")

        fun restoreSettingsFile(context: Context) =
            java.io.File(restoreTransactionDirectory(context), "settings.pre_restore")

        fun restoreStateFile(context: Context) =
            java.io.File(restoreTransactionDirectory(context), "state")

        fun retiredCsvCleanupDirectories(context: Context): List<java.io.File> =
            restoreDirectory(context).listFiles()
                .orEmpty()
                .filter { it.name.startsWith("guild-members.retired-") }

        fun retainedCsvDirectory(context: Context) = java.io.File(
            scope(context).rootDirectory(context),
            PlatoonRepository.RETAINED_CSV_DIRECTORY,
        )

        fun previousRetainedCsvDirectory(context: Context) = java.io.File(
            scope(context).rootDirectory(context),
            "${PlatoonRepository.RETAINED_CSV_DIRECTORY}.pre_restore",
        )

        fun preRestoreDatabase(context: Context) = java.io.File(
            context.getDatabasePath(scope(context).databaseName).parentFile,
            "${scope(context).databaseName}.pre_restore",
        )

        fun csvCheckpointDirectory(context: Context) =
            java.io.File(scope(context).rootDirectory(context), "csv-import-checkpoint")

        fun csvCheckpointStagingDirectory(context: Context) =
            java.io.File(scope(context).rootDirectory(context), "csv-import-checkpoint.staging")

        fun csvCheckpointPreviousDirectory(context: Context) =
            java.io.File(scope(context).rootDirectory(context), "csv-import-checkpoint.previous")
    }

    private class SimulatedProcessDeath : Error()

    private companion object {
        const val USER_SETTINGS = "user_settings"
        const val ARCHIVED_UID = 1001L
        const val CURRENT_UID = 2002L
        const val THIRD_UID = 3003L
        const val ARCHIVED_NOTE = "Weekly review"
        const val INVALID_TARGET_PACKAGE = "not a package"
        val PERIOD_START: LocalDate = LocalDate.of(2026, 7, 26)
    }
}
@RunWith(AndroidJUnit4::class)
class WeeklyReportActivityStateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUpProfile() {
        context.getSharedPreferences("platoon_profiles", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val profile = PlatoonProfileRegistry(context).upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            dev.gf2log.protocol.model.PlatoonProfileData(
                101817u,
                "Weekly UI test",
                emptyList(),
                emptyList(),
            ),
        )
        check(PlatoonProfileRegistry(context).setActive(profile.storageId))
    }

    @After
    fun clearProfile() {
        context.getSharedPreferences("platoon_profiles", Context.MODE_PRIVATE)
            .edit().clear().commit()
        java.io.File(context.filesDir, "platoons").deleteRecursively()
    }

    @Test
    fun repeatedWeeklyPngRendersUseDifferentProviderUris() {
        val periodStart = LocalDate.of(2026, 8, 9)
        val document = WeeklyShareProjection.Document(
            title = "GF2logger",
            subtitle = "2026-08-09 - 2026-08-15",
            headers = listOf("Member", "08/09", "Total"),
            rows = emptyList(),
            includeNotes = false,
            evidenceHealth = WeeklyEvidenceAnalyzer.Health(
                observedDays = 0,
                totalDays = 7,
                exactMetrics = 0,
                lowerBoundMetrics = 0,
                unknownMetrics = 0,
                directLoginDays = 0,
                directPatrolDays = 0,
                closingBoundaries = 0,
            ),
        )
        val writeMethod = WeeklyReportActivity::class.java.getDeclaredMethod(
            "writeWeeklyPng",
            WeeklyShareProjection.Document::class.java,
            LocalDate::class.java,
        ).apply { isAccessible = true }
        try {
            ActivityScenario.launch(WeeklyReportActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val first = writeMethod.invoke(activity, document, periodStart) as java.io.File
                    val firstUri = FileProvider.getUriForFile(
                        activity,
                        activity.packageName + ".fileprovider",
                        first,
                    )
                    val second = writeMethod.invoke(activity, document, periodStart) as java.io.File
                    val secondUri = FileProvider.getUriForFile(
                        activity,
                        activity.packageName + ".fileprovider",
                        second,
                    )

                    assertFalse(first.exists())
                    assertNotEquals(first.canonicalPath, second.canonicalPath)
                    assertNotEquals(firstUri, secondUri)
                    activity.contentResolver.openInputStream(secondUri).use { input ->
                        assertTrue(input != null && input.read() >= 0)
                    }
                }
            }
        } finally {
            WeeklyPngPendingState.directory(context.cacheDir)
                .listFiles()
                .orEmpty()
                .forEach(java.io.File::delete)
        }
    }

    @Test
    fun pendingWeeklyPngSurvivesActivityRecreation() {
        WeeklyPngPendingState.directory(context.cacheDir).mkdirs()
        val target = WeeklyPngPendingState
            .newRenderTarget(context.cacheDir, LocalDate.of(2026, 8, 9))
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val field = WeeklyReportActivity::class.java.getDeclaredField("pendingPng").apply {
            isAccessible = true
        }
        try {
            ActivityScenario.launch(WeeklyReportActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> field.set(activity, target) }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(target.canonicalFile, (field.get(activity) as java.io.File).canonicalFile)
                }
            }
        } finally {
            target.delete()
        }
    }
}
