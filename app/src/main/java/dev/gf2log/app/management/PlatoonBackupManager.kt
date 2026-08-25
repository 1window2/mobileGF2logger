package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.AtomicFile
import dev.gf2log.app.settings.AppBackupSettings
import dev.gf2log.app.settings.AppBackupSettingsCodec
import dev.gf2log.app.settings.BackupSettingsStore
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.ScopedAppSettingsStore
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipException

class PlatoonBackupManager internal constructor(
    context: Context,
    private val settingsStore: BackupSettingsStore,
    private val restoreObserver: (RestoreCheckpoint) -> Unit = {},
    private val storageScope: PlatoonStorageScope =
        PlatoonProfileRegistry(context).activeScope(),
) {
    internal constructor(context: Context, storageScope: PlatoonStorageScope) : this(
        context = context,
        settingsStore = ScopedAppSettingsStore(
            context.applicationContext,
            storageScope.storageId,
        ),
        storageScope = storageScope,
    )

    constructor(context: Context) : this(context, PlatoonProfileRegistry(context).activeScope())

    private val appContext = context.applicationContext
    private val databaseFile: File
        get() = appContext.getDatabasePath(storageScope.databaseName)

    private val restoreDirectory: File
        get() = File(appContext.cacheDir, "platoon-restore/${storageScope.storageId}")

    init {
        recoverInterruptedFullRestore(appContext, settingsStore, storageScope)
    }

    fun export(output: OutputStream) {
        PlatoonRepository(appContext, storageScope).reconcileRetainedCsvFiles()
        PlatoonRepository.withExclusiveDatabase(storageScope) {
            ensureDatabaseExists()
            BackupArchive.write(
                output,
                databaseFile,
                settings = null,
                profile = backupProfile(),
            )
        }
    }

    /** Returns a stable digest after closing SQLite so WAL state is checkpointed. */
    internal fun currentDatabaseSha256(): String =
        PlatoonRepository.withExclusiveDatabase(storageScope) {
        ensureDatabaseExists()
        val digest = MessageDigest.getInstance("SHA-256")
        databaseFile.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { value ->
            String.format("%02x", value.toInt() and 0xff)
        }
    }

    fun exportFull(output: OutputStream) {
        PlatoonRepository(appContext, storageScope).reconcileRetainedCsvFiles()
        PlatoonRepository.withExclusiveDatabase(storageScope) {
            val settings = settingsStore.read()
            ensureDatabaseExists()
            BackupArchive.write(
                output,
                databaseFile,
                AppBackupSettingsCodec.encode(settings),
                backupProfile(),
            )
        }
    }

    fun restore(input: InputStream) {
        restoreDirectory.mkdirs()
        val stagedDatabase = File(restoreDirectory, "platoon.db.staged")
        if (stagedDatabase.exists() && !stagedDatabase.delete()) {
            error("Unable to clear a previous staged restore")
        }
        val staged = validateSelectedBackup {
            BackupArchive.stage(input, stagedDatabase)
        }
        try {
            val target = managerFor(staged)
            target.manager.restoreStagedPlatoon(stagedDatabase, staged, target)
        } finally {
            stagedDatabase.delete()
        }
    }

    // Function Name: restoreCheckpoint
    // Description:
    // - Restores a same-device, current-schema checkpoint without retiring retained CSV evidence.
    // - Used only after the caller removes deterministic files planned by the reverted import.
    // Parameters:
    // - input: Checkpoint archive created by this application.
    // Returns:
    // - Unit after validated, crash-aware database replacement.
    internal fun restoreCheckpoint(input: InputStream) {
        restoreDirectory.mkdirs()
        val stagedDatabase = File(restoreDirectory, "platoon.db.staged")
        if (stagedDatabase.exists() && !stagedDatabase.delete()) {
            error("Unable to clear a previous staged restore")
        }
        val staged = validateSelectedBackup {
            BackupArchive.stage(input, stagedDatabase)
        }
        try {
            validateSelectedBackup {
                BackupFormatPolicy.requirePlatoonOnly(
                    staged.formatVersion,
                    staged.settings != null,
                )
                requireArchiveMatchesScope(staged)
                validateDatabase(stagedDatabase, requireCurrentSchema = true)
            }
            replaceRestoredState(stagedDatabase, restoredSettings = null, retireRetainedCsv = false)
        } finally {
            stagedDatabase.delete()
        }
    }

    fun restoreFull(input: InputStream) {
        restoreDirectory.mkdirs()
        val stagedDatabase = File(restoreDirectory, "platoon.db.staged")
        if (stagedDatabase.exists() && !stagedDatabase.delete()) {
            error("Unable to clear a previous staged restore")
        }
        val staged = validateSelectedBackup {
            BackupArchive.stage(input, stagedDatabase)
        }
        try {
            val target = managerFor(staged)
            target.manager.restoreStagedComplete(stagedDatabase, staged, target)
        } finally {
            stagedDatabase.delete()
        }
    }

    private fun restoreStagedPlatoon(
        stagedDatabase: File,
        staged: BackupArchive.StagedArchive,
        target: RestoreTarget,
    ) {
        validateSelectedBackup {
            BackupFormatPolicy.requirePlatoonOnly(staged.formatVersion, staged.settings != null)
            requireArchiveMatchesScope(staged)
            validateDatabase(stagedDatabase, requireCurrentSchema = false)
        }
        replaceRestoredState(stagedDatabase, restoredSettings = null, restoreTarget = target)
    }

    private fun restoreStagedComplete(
        stagedDatabase: File,
        staged: BackupArchive.StagedArchive,
        target: RestoreTarget,
    ) {
        val restoredSettings = validateSelectedBackup {
            BackupFormatPolicy.requireComplete(staged.formatVersion, staged.settings != null)
            requireArchiveMatchesScope(staged)
            AppBackupSettingsCodec.decode(requireNotNull(staged.settings)).also {
                validateDatabase(stagedDatabase, requireCurrentSchema = false)
            }
        }
        replaceRestoredState(stagedDatabase, restoredSettings, restoreTarget = target)
    }

    private fun managerFor(staged: BackupArchive.StagedArchive): RestoreTarget {
        val registry = PlatoonProfileRegistry(appContext)
        val restoredProfile = requireNotNull(staged.profile) {
            "Unscoped legacy backups are no longer supported"
        }.toProfile()
        require(!restoredProfile.legacy) { "Unscoped legacy backups are no longer supported" }
        registry.requireRestoreCapacity(restoredProfile)
        val scope = PlatoonStorageScope(restoredProfile.storageId)
        val previousProfile = registry.find(scope.storageId)
        val previousActiveStorageId = registry.active()?.storageId
        val clientRegions = ClientServerRegionPreferences(appContext)
        val previousCaptureRegion = clientRegions.stored(restoredProfile.client.packageName)
        val manager = if (scope == storageScope) {
            this
        } else {
            PlatoonBackupManager(
                context = appContext,
                settingsStore = ScopedAppSettingsStore(appContext, scope.storageId),
                restoreObserver = restoreObserver,
                storageScope = scope,
            )
        }
        return RestoreTarget(
            manager = manager,
            restoredProfile = restoredProfile,
            previousProfile = previousProfile,
            previousActiveStorageId = previousActiveStorageId,
            registry = registry,
            previousCaptureRegion = previousCaptureRegion,
        )
    }

    private data class RestoreTarget(
        val manager: PlatoonBackupManager,
        val restoredProfile: PlatoonProfile?,
        val previousProfile: PlatoonProfile?,
        val previousActiveStorageId: String?,
        val registry: PlatoonProfileRegistry,
        val previousCaptureRegion: GameServerRegion?,
    ) {
        fun rollbackJournal(): PlatoonProfileRestoreJournal = PlatoonProfileRestoreJournal(
                targetStorageId = manager.storageScope.storageId,
                previousProfile = previousProfile,
                previousActiveStorageId = previousActiveStorageId,
                ownerPackage = restoredProfile?.client?.packageName,
                previousCaptureRegion = previousCaptureRegion,
            )

        fun installProfileMetadataAndActivate() {
            val storageId = requireNotNull(restoredProfile)
                .let(registry::upsertRestored)
                .storageId
            check(registry.setActive(storageId)) {
                "Unable to select the restored Platoon"
            }
        }
    }

    private fun requireArchiveMatchesScope(staged: BackupArchive.StagedArchive) {
        val archivedProfile = requireNotNull(staged.profile) {
            "Unscoped legacy backups are no longer supported"
        }
        require(!archivedProfile.legacy) { "Unscoped legacy backups are no longer supported" }
        require(archivedProfile.storageId == storageScope.storageId) {
            "Backup belongs to a different Platoon"
        }
    }

    private fun backupProfile(): PlatoonProfile {
        val registry = PlatoonProfileRegistry(appContext)
        registry.ensureInitialized()
        return requireNotNull(registry.find(storageScope.storageId)) {
            "The selected Platoon profile is unavailable"
        }
    }

    // Function Name: validateSelectedBackup
    // Description:
    // - Converts expected archive, settings, and SQLite validation failures into a typed error.
    // - Leaves provider I/O and local restore-state failures distinct for accurate UI reporting.
    // Parameters:
    // - validation: Read-only validation operation for the selected backup.
    // Returns:
    // - Returns the validation result when the selected backup is valid.
    private inline fun <T> validateSelectedBackup(validation: () -> T): T = try {
        validation()
    } catch (error: IllegalArgumentException) {
        throw InvalidBackupException(error)
    } catch (error: SQLiteException) {
        throw InvalidBackupException(error)
    } catch (error: ZipException) {
        throw InvalidBackupException(error)
    } catch (error: EOFException) {
        throw InvalidBackupException(error)
    }

    // Function Name: replaceRestoredState
    // Description:
    // - Replaces the validated database and retires target-device roster CSV evidence atomically.
    // - Replaces settings only for a complete backup while preserving them for a v1 backup.
    // - Uses the durable restore journal so a process death or failure rolls every resource back.
    // Parameters:
    // - stagedDatabase: Validated database extracted from the selected backup.
    // - restoredSettings: Complete-backup settings, or null for Platoon-only compatibility restore.
    // Returns:
    // - Returns normally after the restored state commits and rollback artifacts are cleaned.
    private fun replaceRestoredState(
        stagedDatabase: File,
        restoredSettings: AppBackupSettings?,
        retireRetainedCsv: Boolean = true,
        restoreTarget: RestoreTarget? = null,
    ) {
        val retainedCsvDirectory = File(
            storageScope.rootDirectory(appContext),
            PlatoonRepository.RETAINED_CSV_DIRECTORY,
        )
        val previousRetainedCsvDirectory = File(
            storageScope.rootDirectory(appContext),
            "${PlatoonRepository.RETAINED_CSV_DIRECTORY}.pre_restore",
        )
        try {
            PlatoonRepository.withExclusiveDatabase(storageScope) {
                beginRestoreTransaction(
                    previousSettings = if (restoredSettings == null) {
                        null
                    } else {
                        settingsStore.read()
                    },
                    databaseExisted = databaseFile.isFile,
                    profileRollback = restoreTarget?.rollbackJournal(),
                )
                replaceDatabase(stagedDatabase, preservePrevious = true)
                restoreObserver(RestoreCheckpoint.DATABASE_INSTALLED)
                if (restoredSettings != null) {
                    settingsStore.replace(restoredSettings)
                    restoreObserver(RestoreCheckpoint.SETTINGS_REPLACED)
                }
                if (retireRetainedCsv) {
                    retireRetainedCsvCache(
                        retainedCsvDirectory,
                        previousRetainedCsvDirectory,
                    )
                    restoreObserver(RestoreCheckpoint.RETAINED_CSV_RETIRED)
                }
                restoreTarget?.installProfileMetadataAndActivate()
                if (restoreTarget != null) {
                    restoreObserver(RestoreCheckpoint.PROFILE_METADATA_INSTALLED)
                }
                writeRestoreState(RestoreState.COMMITTED)
                restoreObserver(RestoreCheckpoint.COMMITTED)
                cleanupCommittedRestore(
                    previousRetainedCsvDirectory,
                    previousDatabaseFile(),
                )
            }
        } catch (error: Exception) {
            runCatching {
                recoverInterruptedFullRestore(appContext, settingsStore, storageScope)
            }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private fun validateDatabase(file: File, requireCurrentSchema: Boolean) {
        file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            require(input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)) {
                "Backup is not a SQLite database"
            }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                require(cursor.moveToFirst()) { "Backup schema version is missing" }
                cursor.getInt(0)
            }
            require(version in PlatoonSchema.MIN_BACKUP_VERSION..PlatoonSchema.CURRENT_VERSION) {
                "Unsupported Platoon database schema"
            }
            require(!requireCurrentSchema || version == PlatoonSchema.CURRENT_VERSION) {
                "Full backup does not contain the complete current Platoon schema"
            }
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "Backup database integrity check failed"
                }
            }
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                null,
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            val missing = PlatoonSchema.requiredTables(version) - tables
            require(missing.isEmpty()) {
                "Backup database is missing required tables: ${missing.sorted().joinToString()}"
            }
            PlatoonSchema.requiredColumns(version).forEach { (table, required) ->
                val columns = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(1))
                    }
                }
                val missingColumns = required - columns
                require(missingColumns.isEmpty()) {
                    "Backup table $table is missing required columns: " +
                        missingColumns.sorted().joinToString()
                }
            }
            if (version == PlatoonSchema.CURRENT_VERSION) validateCurrentSchema(db)
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                require(!cursor.moveToFirst()) { "Backup database has invalid references" }
            }
        }
    }

    private fun validateCurrentSchema(database: SQLiteDatabase) {
        appContext.deleteDatabase(SCHEMA_REFERENCE_DATABASE)
        try {
            PlatoonDatabase(appContext, SCHEMA_REFERENCE_DATABASE, storageScope).use { helper ->
                val expected = DatabaseSchemaContract.read(helper.readableDatabase)
                val actual = DatabaseSchemaContract.read(database)
                require(actual == expected) {
                    "Full backup database does not match the current Platoon schema"
                }
            }
        } finally {
            appContext.deleteDatabase(SCHEMA_REFERENCE_DATABASE)
        }
    }

    private fun replaceDatabase(staged: File, preservePrevious: Boolean = false) {
        databaseFile.parentFile?.mkdirs()
        val previous = previousDatabaseFile()
        if (previous.exists() && !previous.delete()) error("Unable to clear previous restore backup")
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(databaseFile.path + suffix).delete()
        }
        if (databaseFile.exists() && !databaseFile.renameTo(previous)) {
            error("Unable to preserve the current Platoon database")
        }
        val installed = staged.renameTo(databaseFile)
        if (!installed) {
            previous.renameTo(databaseFile)
            error("Unable to install the restored Platoon database")
        }
        try {
            // Opening through the real helper upgrades old supported backups
            // and verifies that the installed database serves the current
            // schema before the rollback copy is discarded.
            PlatoonDatabase(appContext, storageScope.databaseName).use { helper ->
                helper.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM members",
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                }
            }
            validateDatabase(databaseFile, requireCurrentSchema = true)
            if (!preservePrevious && previous.exists() && !previous.delete()) {
                error("Unable to discard the previous Platoon database")
            }
        } catch (error: Exception) {
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                File(databaseFile.path + suffix).delete()
            }
            if (previous.exists() && !previous.renameTo(databaseFile)) {
                error.addSuppressed(
                    IllegalStateException("Unable to restore the previous Platoon database"),
                )
            }
            throw error
        }
    }

    private fun previousDatabaseFile() = File(
        databaseFile.parentFile,
        "${storageScope.databaseName}.pre_restore",
    )

    private fun ensureDatabaseExists() {
        if (databaseFile.isFile) return
        PlatoonDatabase(appContext, storageScope.databaseName).use { helper ->
            helper.writableDatabase.rawQuery("SELECT COUNT(*) FROM members", null).use { cursor ->
                check(cursor.moveToFirst())
            }
        }
    }

    private fun retireRetainedCsvCache(directory: File, previous: File) {
        require(!previous.exists()) { "A previous retained CSV restore is still pending" }
        if (!directory.exists()) return
        if (!directory.renameTo(previous)) {
            error("Unable to retire retained CSV files after restore")
        }
    }

    // Function Name: beginRestoreTransaction
    // Description:
    // - Journals only the resources that the selected backup format can replace.
    // - Keeps v1 database-only restore independent from app settings validation.
    // Parameters:
    // - previousSettings: Settings rollback value for complete restore, or null for v1 restore.
    // - databaseExisted: Whether rollback must restore a previous database or remove a new one.
    // Returns:
    // - Returns normally after the durable PREPARED transaction state is written.
    private fun beginRestoreTransaction(
        previousSettings: AppBackupSettings?,
        databaseExisted: Boolean,
        profileRollback: PlatoonProfileRestoreJournal?,
    ) {
        val transactionDirectory = restoreTransactionDirectory(appContext, storageScope)
        require(!transactionDirectory.exists()) { "A previous backup restore is still pending" }
        check(transactionDirectory.mkdirs()) { "Unable to create the backup-restore transaction" }
        if (previousSettings != null) {
            writeAtomic(
                restoreSettingsFile(appContext, storageScope),
                AppBackupSettingsCodec.encode(previousSettings),
            )
            writeAtomic(restoreSettingsRollbackFile(appContext, storageScope), ByteArray(0))
        }
        if (!databaseExisted) {
            writeAtomic(restoreDatabaseWasMissingFile(appContext, storageScope), ByteArray(0))
        }
        if (profileRollback != null) {
            writeAtomic(
                restoreProfileFile(appContext, storageScope),
                PlatoonProfileRestoreJournalCodec.encode(profileRollback),
            )
        }
        writeRestoreState(RestoreState.PREPARED)
    }

    private fun writeRestoreState(state: RestoreState) {
        writeAtomic(
            restoreStateFile(appContext, storageScope),
            state.name.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun cleanupCommittedRestore(previousCsv: File, previousDatabase: File) {
        if (previousCsv.exists() && !previousCsv.deleteRecursively()) {
            return
        }
        if (previousDatabase.exists() && !previousDatabase.delete()) return
        cleanupRestoreTransaction(appContext, storageScope)
    }

    companion object {
        const val FILE_EXTENSION = "gf2backup"
        const val MIME_TYPE = "application/vnd.dev.gf2log.backup"
        private const val SCHEMA_REFERENCE_DATABASE = "platoon-schema-reference.db"
        private const val RESTORE_TRANSACTION_DIRECTORY = "platoon-full-restore"
        private const val RESTORE_STATE_FILE = "state"
        private const val RESTORE_SETTINGS_FILE = "settings.pre_restore"
        private const val RESTORE_SETTINGS_ROLLBACK_FILE = "settings.rollback_required"
        private const val RESTORE_DATABASE_WAS_MISSING_FILE = "database.was_missing"
        private const val RESTORE_PROFILE_FILE = "profile.pre_restore"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /** Restores a scoped archive even when a fresh installation has no active profile. */
        fun restoreSelected(
            context: Context,
            input: InputStream,
            complete: Boolean,
        ) {
            val appContext = context.applicationContext
            val stagingDirectory = File(appContext.cacheDir, "platoon-restore-selected").apply {
                check(mkdirs() || isDirectory) { "Unable to create the restore staging directory" }
            }
            val stagedDatabase = File.createTempFile("selected-", ".db", stagingDirectory)
            try {
                val staged = try {
                    BackupArchive.stage(input, stagedDatabase)
                } catch (error: IllegalArgumentException) {
                    throw InvalidBackupException(error)
                } catch (error: ZipException) {
                    throw InvalidBackupException(error)
                } catch (error: EOFException) {
                    throw InvalidBackupException(error)
                }
                val profile = try {
                    requireNotNull(staged.profile) {
                        "Unscoped legacy backups are no longer supported"
                    }.toProfile().also {
                        require(!it.legacy) { "Unscoped legacy backups are no longer supported" }
                    }
                } catch (error: IllegalArgumentException) {
                    throw InvalidBackupException(error)
                }
                val manager = PlatoonBackupManager(
                    appContext,
                    PlatoonStorageScope(profile.storageId),
                )
                val target = manager.managerFor(staged)
                if (complete) {
                    manager.restoreStagedComplete(stagedDatabase, staged, target)
                } else {
                    manager.restoreStagedPlatoon(stagedDatabase, staged, target)
                }
            } finally {
                stagedDatabase.delete()
                stagingDirectory.delete()
            }
        }

        internal fun recoverInterruptedFullRestore(
            context: Context,
            settingsStore: BackupSettingsStore? = null,
        ) {
            val appContext = context.applicationContext
            val profiles = PlatoonProfileRegistry(appContext).ensureInitialized()
            val registeredScopes = profiles.map { PlatoonStorageScope(it.storageId) }
            val interruptedScopes = File(appContext.filesDir, "platoons")
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter(File::isDirectory)
                .mapNotNull { directory ->
                    directory.name
                        .takeIf(PlatoonProfileIdentity::isValidStorageId)
                        ?.let(::PlatoonStorageScope)
                }
                .filter { scope -> restoreStateFile(appContext, scope).isFile }
                .toList()
            val scopes = buildList {
                addAll(registeredScopes)
                addAll(interruptedScopes)
                val legacyScope = PlatoonStorageScope(PlatoonProfileIdentity.LEGACY_STORAGE_ID)
                if (isEmpty() || restoreStateFile(appContext, legacyScope).isFile) {
                    add(legacyScope)
                }
            }
            scopes.distinct().forEach { scope ->
                recoverInterruptedFullRestore(
                    appContext,
                    if (settingsStore != null && scope.isLegacy) {
                        settingsStore
                    } else {
                        ScopedAppSettingsStore(appContext, scope.storageId)
                    },
                    scope,
                )
            }
        }

        internal fun recoverInterruptedFullRestore(
            context: Context,
            scope: PlatoonStorageScope,
        ) = recoverInterruptedFullRestore(
            context.applicationContext,
            ScopedAppSettingsStore(context.applicationContext, scope.storageId),
            scope,
        )

        private fun recoverInterruptedFullRestore(
            appContext: Context,
            settingsStore: BackupSettingsStore,
            scope: PlatoonStorageScope,
        ) {
            PlatoonRepository.withExclusiveDatabase(scope) {
                val stateFile = restoreStateFile(appContext, scope)
                if (!stateFile.isFile) {
                    cleanupRestoreTransaction(appContext, scope)
                    cleanupLegacyRetiredCsvArtifacts(appContext, scope)
                    recoverLegacyRetainedCsvRetirement(appContext, scope)
                    return@withExclusiveDatabase
                }
                val state = runCatching {
                    RestoreState.valueOf(stateFile.readText(Charsets.US_ASCII))
                }.getOrElse { throw IllegalStateException("Invalid backup-restore transaction", it) }
                val database = appContext.getDatabasePath(scope.databaseName)
                val previousDatabase = File(
                    database.parentFile,
                    "${scope.databaseName}.pre_restore",
                )
                val retainedCsv = File(
                    scope.rootDirectory(appContext),
                    PlatoonRepository.RETAINED_CSV_DIRECTORY,
                )
                val previousCsv = File(
                    scope.rootDirectory(appContext),
                    "${PlatoonRepository.RETAINED_CSV_DIRECTORY}.pre_restore",
                )
                when (state) {
                    RestoreState.PREPARED -> rollbackPreparedRestore(
                        appContext,
                        settingsStore,
                        database,
                        previousDatabase,
                        retainedCsv,
                        previousCsv,
                        scope,
                    )
                    RestoreState.COMMITTED -> {
                        if (previousCsv.exists() && !previousCsv.deleteRecursively()) return@withExclusiveDatabase
                        if (previousDatabase.exists() && !previousDatabase.delete()) return@withExclusiveDatabase
                        cleanupRestoreTransaction(appContext, scope)
                    }
                }
            }
        }

        private fun rollbackPreparedRestore(
            context: Context,
            settingsStore: BackupSettingsStore,
            database: File,
            previousDatabase: File,
            retainedCsv: File,
            previousCsv: File,
            scope: PlatoonStorageScope,
        ) {
            if (previousDatabase.exists()) {
                databaseSidecars(database).forEach(File::delete)
                if (database.exists() && !database.delete()) {
                    error("Unable to remove the interrupted restored database")
                }
                if (!previousDatabase.renameTo(database)) {
                    error("Unable to recover the previous Platoon database")
                }
            } else if (restoreDatabaseWasMissingFile(context, scope).isFile) {
                databaseSidecars(database).forEach { file ->
                    if (file.exists() && !file.delete()) {
                        error("Unable to remove the interrupted restored database")
                    }
                }
            }
            val settingsFile = restoreSettingsFile(context, scope)
            val settingsRollback = restoreSettingsRollbackFile(context, scope)
            if (settingsRollback.isFile || settingsFile.isFile) {
                require(settingsFile.isFile) { "Previous app settings are missing" }
                settingsStore.replace(AppBackupSettingsCodec.decode(settingsFile.readBytes()))
            }
            if (previousCsv.exists()) {
                if (retainedCsv.exists() && !retainedCsv.deleteRecursively()) {
                    error("Unable to clear retained CSV files during recovery")
                }
                if (!previousCsv.renameTo(retainedCsv)) {
                    error("Unable to recover retained CSV files")
                }
            }
            val profileFile = restoreProfileFile(context, scope)
            if (profileFile.isFile) {
                val rollback = PlatoonProfileRestoreJournalCodec.decode(profileFile.readBytes())
                require(rollback.targetStorageId == scope.storageId) {
                    "Profile restore journal belongs to a different Platoon"
                }
                rollback.ownerPackage?.let { ownerPackage ->
                    val preferences = ClientServerRegionPreferences(context)
                    val previousRegion = rollback.previousCaptureRegion
                    if (previousRegion == null) {
                        preferences.clear(ownerPackage)
                    } else {
                        preferences.set(ownerPackage, previousRegion)
                    }
                }
                PlatoonProfileRegistry(context).restoreTargetState(
                    targetStorageId = rollback.targetStorageId,
                    previousProfile = rollback.previousProfile,
                    previousActiveStorageId = rollback.previousActiveStorageId,
                )
            }
            cleanupRestoreTransaction(context, scope)
        }

        private fun recoverLegacyRetainedCsvRetirement(
            context: Context,
            scope: PlatoonStorageScope,
        ) {
            val directory = File(
                scope.rootDirectory(context),
                PlatoonRepository.RETAINED_CSV_DIRECTORY,
            )
            val previous = File(
                scope.rootDirectory(context),
                "${PlatoonRepository.RETAINED_CSV_DIRECTORY}.pre_restore",
            )
            if (!previous.exists()) return
            require(!directory.exists()) {
                "Both retained CSV state and a previous restore copy exist"
            }
            if (!previous.renameTo(directory)) {
                error("Unable to recover retained CSV files from an interrupted restore")
            }
        }

        // Function Name: cleanupLegacyRetiredCsvArtifacts
        // Description:
        // - Removes obsolete cache-only retained-CSV copies left by pre-v2 restore code.
        // - Runs only when no current full-restore transaction state exists.
        // Parameters:
        // - context: Application context used to locate the private restore cache.
        // Returns:
        // - Returns normally after all obsolete artifacts are removed.
        private fun cleanupLegacyRetiredCsvArtifacts(
            context: Context,
            scope: PlatoonStorageScope,
        ) {
            val restoreDirectories = buildList {
                add(File(context.cacheDir, "platoon-restore/${scope.storageId}"))
                if (scope.isLegacy) add(File(context.cacheDir, "platoon-restore"))
            }
            restoreDirectories.flatMap { it.listFiles().orEmpty().asList() }
                .filter { file ->
                    file.isDirectory && file.name.startsWith("guild-members.retired-")
                }
                .forEach { directory ->
                    check(directory.deleteRecursively()) {
                        "Unable to remove an obsolete retained CSV restore artifact"
                    }
                }
        }

        private fun writeAtomic(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val atomicFile = AtomicFile(file)
            val output = atomicFile.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                atomicFile.finishWrite(output)
            } catch (error: Exception) {
                atomicFile.failWrite(output)
                throw error
            }
        }

        private fun cleanupRestoreTransaction(context: Context, scope: PlatoonStorageScope) {
            val directory = restoreTransactionDirectory(context, scope)
            if (!directory.exists()) return
            val settings = restoreSettingsFile(context, scope)
            val settingsRollback = restoreSettingsRollbackFile(context, scope)
            val state = restoreStateFile(context, scope)
            val databaseWasMissing = restoreDatabaseWasMissingFile(context, scope)
            val profile = restoreProfileFile(context, scope)
            settings.delete()
            settingsRollback.delete()
            state.delete()
            databaseWasMissing.delete()
            profile.delete()
            directory.delete()
        }

        private fun restoreTransactionDirectory(context: Context, scope: PlatoonStorageScope) =
            File(scope.rootDirectory(context), RESTORE_TRANSACTION_DIRECTORY)

        private fun restoreStateFile(context: Context, scope: PlatoonStorageScope) =
            File(restoreTransactionDirectory(context, scope), RESTORE_STATE_FILE)

        private fun restoreSettingsFile(context: Context, scope: PlatoonStorageScope) =
            File(restoreTransactionDirectory(context, scope), RESTORE_SETTINGS_FILE)

        private fun restoreSettingsRollbackFile(context: Context, scope: PlatoonStorageScope) =
            File(restoreTransactionDirectory(context, scope), RESTORE_SETTINGS_ROLLBACK_FILE)

        private fun restoreDatabaseWasMissingFile(context: Context, scope: PlatoonStorageScope) =
            File(restoreTransactionDirectory(context, scope), RESTORE_DATABASE_WAS_MISSING_FILE)

        private fun restoreProfileFile(context: Context, scope: PlatoonStorageScope) =
            File(restoreTransactionDirectory(context, scope), RESTORE_PROFILE_FILE)

        private fun databaseSidecars(database: File): List<File> =
            listOf("", "-wal", "-shm", "-journal").map { suffix -> File(database.path + suffix) }
    }

    internal enum class RestoreCheckpoint {
        DATABASE_INSTALLED,
        SETTINGS_REPLACED,
        RETAINED_CSV_RETIRED,
        PROFILE_METADATA_INSTALLED,
        COMMITTED,
    }

    private enum class RestoreState {
        PREPARED,
        COMMITTED,
    }
}

// Class Name: InvalidBackupException
// Role: Identifies failures that prove the selected backup is invalid.
// Responsibilities:
//   - Error classification: Separates backup validation failures from operational restore errors.
// Attributes:
//   - cause: Validation exception raised while reading the selected backup.
internal class InvalidBackupException(cause: Exception) :
    Exception("Selected backup failed validation", cause)
