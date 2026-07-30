package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.settings.AppBackupSettingsCodec
import dev.gf2log.app.settings.AppSettingsStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class PlatoonBackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val databaseFile: File
        get() = appContext.getDatabasePath(PlatoonSchema.DATABASE_NAME)

    fun export(output: OutputStream) {
        PlatoonRepository.withExclusiveDatabase {
            BackupArchive.write(output, databaseFile, settings = null)
        }
    }

    fun exportFull(output: OutputStream) {
        val settings = AppSettingsStore(appContext).read()
        PlatoonRepository.withExclusiveDatabase {
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
                PlatoonRepository.markLegacyImportComplete(appContext)
            }
        } finally {
            stagedDatabase.delete()
        }
    }

    fun restoreFull(input: InputStream) {
        val restoreDirectory = File(appContext.cacheDir, "platoon-restore").apply { mkdirs() }
        val stagedDatabase = File(restoreDirectory, "platoon.db.staged")
        if (stagedDatabase.exists() && !stagedDatabase.delete()) {
            error("Unable to clear a previous staged restore")
        }
        val staged = BackupArchive.stage(input, stagedDatabase)
        try {
            BackupFormatPolicy.requireComplete(staged.formatVersion, staged.settings != null)
            val restoredSettings = AppBackupSettingsCodec.decode(requireNotNull(staged.settings))
            validateDatabase(stagedDatabase, requireCurrentSchema = true)
            val settingsStore = AppSettingsStore(appContext)
            val previousSettings = settingsStore.read()
            PlatoonRepository.withExclusiveDatabase {
                replaceDatabase(stagedDatabase) {
                    try {
                        settingsStore.replace(restoredSettings)
                        PlatoonRepository.markLegacyImportComplete(appContext)
                    } catch (error: Exception) {
                        runCatching { settingsStore.replace(previousSettings) }
                            .exceptionOrNull()
                            ?.let(error::addSuppressed)
                        throw error
                    }
                }
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

    private fun replaceDatabase(staged: File, afterInstall: () -> Unit = {}) {
        databaseFile.parentFile?.mkdirs()
        val previous = File(
            databaseFile.parentFile,
            "${PlatoonSchema.DATABASE_NAME}.pre_restore",
        )
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
            afterInstall()
            previous.delete()
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

    private fun ensureDatabaseExists() {
        if (databaseFile.isFile) return
        PlatoonDatabase(appContext).use { helper ->
            helper.writableDatabase.rawQuery("SELECT COUNT(*) FROM members", null).use { cursor ->
                check(cursor.moveToFirst())
            }
        }
    }

    companion object {
        const val FILE_EXTENSION = "gf2backup"
        const val MIME_TYPE = "application/vnd.dev.gf2log.backup"
        private const val SCHEMA_REFERENCE_DATABASE = "platoon-schema-reference.db"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
