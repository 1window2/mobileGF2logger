package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import dev.gf2log.app.settings.AppBackupSettingsCodec
import dev.gf2log.app.settings.AppSettingsStore
import dev.gf2log.app.settings.BackupSettingsStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class PlatoonBackupManager internal constructor(
    context: Context,
    private val settingsStore: BackupSettingsStore,
    private val restoreObserver: (RestoreCheckpoint) -> Unit = {},
) {
    constructor(context: Context) : this(context, AppSettingsStore(context.applicationContext))

    private val appContext = context.applicationContext
    private val databaseFile: File
        get() = appContext.getDatabasePath(PlatoonSchema.DATABASE_NAME)

    init {
        recoverInterruptedFullRestore(appContext, settingsStore)
    }

    fun export(output: OutputStream) {
        PlatoonRepository(appContext).reconcileRetainedCsvFiles()
        PlatoonRepository.withExclusiveDatabase {
            BackupArchive.write(output, databaseFile, settings = null)
        }
    }

    fun exportFull(output: OutputStream) {
        PlatoonRepository(appContext).reconcileRetainedCsvFiles()
        PlatoonRepository.withExclusiveDatabase {
            val settings = settingsStore.read()
            ensureDatabaseExists()
            BackupArchive.write(output, databaseFile, AppBackupSettingsCodec.encode(settings))
        }
    }

    fun restore(input: InputStream) {
        val restoreDirectory = File(appContext.cacheDir, "platoon-restore").apply { mkdirs() }
        val stagedDatabase = File(restoreDirectory, "platoon.db.staged")
        if (stagedDatabase.exists() && !stagedDatabase.delete()) {
            error("Unable to clear a previous staged restore")
        }
        val staged = BackupArchive.stage(input, stagedDatabase)
        try {
            BackupFormatPolicy.requirePlatoonOnly(
                staged.formatVersion,
                staged.settings != null,
            )
            validateDatabase(stagedDatabase, requireCurrentSchema = false)
            PlatoonRepository.withExclusiveDatabase {
                replaceDatabase(stagedDatabase)
            }
        } finally {
            stagedDatabase.delete()
        }
    }

    fun restoreFull(input: InputStream) {
        val restoreDirectory = File(appContext.cacheDir, "platoon-restore").apply { mkdirs() }
        val stagedDatabase = File(restoreDirectory, "platoon.db.staged")
        val retainedCsvDirectory = File(
            appContext.filesDir,
            PlatoonRepository.RETAINED_CSV_DIRECTORY,
        )
        val previousRetainedCsvDirectory = File(
            appContext.filesDir,
            "${PlatoonRepository.RETAINED_CSV_DIRECTORY}.pre_restore",
        )
        if (stagedDatabase.exists() && !stagedDatabase.delete()) {
            error("Unable to clear a previous staged restore")
        }
        val staged = BackupArchive.stage(input, stagedDatabase)
        try {
            BackupFormatPolicy.requireComplete(staged.formatVersion, staged.settings != null)
            val restoredSettings = AppBackupSettingsCodec.decode(requireNotNull(staged.settings))
            validateDatabase(stagedDatabase, requireCurrentSchema = true)
            try {
                PlatoonRepository.withExclusiveDatabase {
                    beginFullRestore(
                        previousSettings = settingsStore.read(),
                        databaseExisted = databaseFile.isFile,
                    )
                    replaceDatabase(stagedDatabase, preservePrevious = true)
                    restoreObserver(RestoreCheckpoint.DATABASE_INSTALLED)
                    settingsStore.replace(restoredSettings)
                    restoreObserver(RestoreCheckpoint.SETTINGS_REPLACED)
                    retireRetainedCsvCache(
                        retainedCsvDirectory,
                        previousRetainedCsvDirectory,
                    )
                    restoreObserver(RestoreCheckpoint.RETAINED_CSV_RETIRED)
                    writeRestoreState(RestoreState.COMMITTED)
                    restoreObserver(RestoreCheckpoint.COMMITTED)
                    cleanupCommittedFullRestore(
                        previousRetainedCsvDirectory,
                        previousDatabaseFile(),
                    )
                }
            } catch (error: Exception) {
                runCatching { recoverInterruptedFullRestore(appContext, settingsStore) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
                throw error
            }
        } finally {
            stagedDatabase.delete()
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
            PlatoonDatabase(appContext, SCHEMA_REFERENCE_DATABASE).use { helper ->
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
            PlatoonDatabase(appContext).use { helper ->
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
        "${PlatoonSchema.DATABASE_NAME}.pre_restore",
    )

    private fun ensureDatabaseExists() {
        if (databaseFile.isFile) return
        PlatoonDatabase(appContext).use { helper ->
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

    private fun beginFullRestore(
        previousSettings: dev.gf2log.app.settings.AppBackupSettings,
        databaseExisted: Boolean,
    ) {
        val transactionDirectory = restoreTransactionDirectory(appContext)
        require(!transactionDirectory.exists()) { "A previous full restore is still pending" }
        check(transactionDirectory.mkdirs()) { "Unable to create the full-restore transaction" }
        writeAtomic(
            restoreSettingsFile(appContext),
            AppBackupSettingsCodec.encode(previousSettings),
        )
        if (!databaseExisted) {
            writeAtomic(restoreDatabaseWasMissingFile(appContext), ByteArray(0))
        }
        writeRestoreState(RestoreState.PREPARED)
    }

    private fun writeRestoreState(state: RestoreState) {
        writeAtomic(restoreStateFile(appContext), state.name.toByteArray(Charsets.US_ASCII))
    }

    private fun cleanupCommittedFullRestore(previousCsv: File, previousDatabase: File) {
        if (previousCsv.exists() && !previousCsv.deleteRecursively()) {
            return
        }
        if (previousDatabase.exists() && !previousDatabase.delete()) return
        cleanupRestoreTransaction(appContext)
    }

    companion object {
        const val FILE_EXTENSION = "gf2backup"
        const val MIME_TYPE = "application/vnd.dev.gf2log.backup"
        private const val SCHEMA_REFERENCE_DATABASE = "platoon-schema-reference.db"
        private const val RESTORE_TRANSACTION_DIRECTORY = "platoon-full-restore"
        private const val RESTORE_STATE_FILE = "state"
        private const val RESTORE_SETTINGS_FILE = "settings.pre_restore"
        private const val RESTORE_DATABASE_WAS_MISSING_FILE = "database.was_missing"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        internal fun recoverInterruptedFullRestore(
            context: Context,
            settingsStore: BackupSettingsStore = AppSettingsStore(context.applicationContext),
        ) {
            val appContext = context.applicationContext
            PlatoonRepository.withExclusiveDatabase {
                val stateFile = restoreStateFile(appContext)
                if (!stateFile.isFile) {
                    cleanupRestoreTransaction(appContext)
                    cleanupLegacyRetiredCsvArtifacts(appContext)
                    recoverLegacyRetainedCsvRetirement(appContext)
                    return@withExclusiveDatabase
                }
                val state = runCatching {
                    RestoreState.valueOf(stateFile.readText(Charsets.US_ASCII))
                }.getOrElse { throw IllegalStateException("Invalid full-restore transaction", it) }
                val database = appContext.getDatabasePath(PlatoonSchema.DATABASE_NAME)
                val previousDatabase = File(
                    database.parentFile,
                    "${PlatoonSchema.DATABASE_NAME}.pre_restore",
                )
                val retainedCsv = File(
                    appContext.filesDir,
                    PlatoonRepository.RETAINED_CSV_DIRECTORY,
                )
                val previousCsv = File(
                    appContext.filesDir,
                    "${PlatoonRepository.RETAINED_CSV_DIRECTORY}.pre_restore",
                )
                when (state) {
                    RestoreState.PREPARED -> rollbackPreparedFullRestore(
                        appContext,
                        settingsStore,
                        database,
                        previousDatabase,
                        retainedCsv,
                        previousCsv,
                    )
                    RestoreState.COMMITTED -> {
                        if (previousCsv.exists() && !previousCsv.deleteRecursively()) return@withExclusiveDatabase
                        if (previousDatabase.exists() && !previousDatabase.delete()) return@withExclusiveDatabase
                        cleanupRestoreTransaction(appContext)
                    }
                }
            }
        }

        private fun rollbackPreparedFullRestore(
            context: Context,
            settingsStore: BackupSettingsStore,
            database: File,
            previousDatabase: File,
            retainedCsv: File,
            previousCsv: File,
        ) {
            if (previousDatabase.exists()) {
                databaseSidecars(database).forEach(File::delete)
                if (database.exists() && !database.delete()) {
                    error("Unable to remove the interrupted restored database")
                }
                if (!previousDatabase.renameTo(database)) {
                    error("Unable to recover the previous Platoon database")
                }
            } else if (restoreDatabaseWasMissingFile(context).isFile) {
                databaseSidecars(database).forEach { file ->
                    if (file.exists() && !file.delete()) {
                        error("Unable to remove the interrupted restored database")
                    }
                }
            }
            val settingsFile = restoreSettingsFile(context)
            require(settingsFile.isFile) { "Previous app settings are missing" }
            settingsStore.replace(AppBackupSettingsCodec.decode(settingsFile.readBytes()))
            if (previousCsv.exists()) {
                if (retainedCsv.exists() && !retainedCsv.deleteRecursively()) {
                    error("Unable to clear retained CSV files during recovery")
                }
                if (!previousCsv.renameTo(retainedCsv)) {
                    error("Unable to recover retained CSV files")
                }
            }
            cleanupRestoreTransaction(context)
        }

        private fun recoverLegacyRetainedCsvRetirement(context: Context) {
            val directory = File(context.filesDir, PlatoonRepository.RETAINED_CSV_DIRECTORY)
            val previous = File(
                context.filesDir,
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
        private fun cleanupLegacyRetiredCsvArtifacts(context: Context) {
            val restoreDirectory = File(context.cacheDir, "platoon-restore")
            restoreDirectory.listFiles()
                .orEmpty()
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

        private fun cleanupRestoreTransaction(context: Context) {
            val directory = restoreTransactionDirectory(context)
            if (!directory.exists()) return
            val settings = restoreSettingsFile(context)
            val state = restoreStateFile(context)
            val databaseWasMissing = restoreDatabaseWasMissingFile(context)
            settings.delete()
            state.delete()
            databaseWasMissing.delete()
            directory.delete()
        }

        private fun restoreTransactionDirectory(context: Context) =
            File(context.filesDir, RESTORE_TRANSACTION_DIRECTORY)

        private fun restoreStateFile(context: Context) =
            File(restoreTransactionDirectory(context), RESTORE_STATE_FILE)

        private fun restoreSettingsFile(context: Context) =
            File(restoreTransactionDirectory(context), RESTORE_SETTINGS_FILE)

        private fun restoreDatabaseWasMissingFile(context: Context) =
            File(restoreTransactionDirectory(context), RESTORE_DATABASE_WAS_MISSING_FILE)

        private fun databaseSidecars(database: File): List<File> =
            listOf("", "-wal", "-shm", "-journal").map { suffix -> File(database.path + suffix) }
    }

    internal enum class RestoreCheckpoint {
        DATABASE_INSTALLED,
        SETTINGS_REPLACED,
        RETAINED_CSV_RETIRED,
        COMMITTED,
    }

    private enum class RestoreState {
        PREPARED,
        COMMITTED,
    }
}
