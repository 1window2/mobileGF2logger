package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PlatoonBackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val databaseFile: File
        get() = appContext.getDatabasePath(PlatoonSchema.DATABASE_NAME)

    fun export(output: OutputStream) {
        PlatoonRepository.withExclusiveDatabase {
            val source = databaseFile
            require(source.isFile) { "No Platoon database exists" }
            require(source.length() <= MAX_DATABASE_BYTES) { "Platoon database is too large" }
            val checksum = source.sha256()
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                Properties().apply {
                    setProperty("formatVersion", FORMAT_VERSION.toString())
                    setProperty("databaseSha256", checksum)
                }.store(zip, "GF2logger Platoon management backup")
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun restore(input: InputStream) {
        val restoreDirectory = File(appContext.cacheDir, "platoon-restore").apply { mkdirs() }
        val stagedDatabase = File(restoreDirectory, "platoon.db.staged")
        if (stagedDatabase.exists() && !stagedDatabase.delete()) {
            error("Unable to clear a previous staged restore")
        }
        var manifest: Properties? = null
        var databaseSeen = false
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Backup cannot contain directories" }
                    when (entry.name) {
                        MANIFEST_ENTRY -> {
                            require(manifest == null) { "Duplicate backup manifest" }
                            val bytes = ByteArrayOutputStream().also { output ->
                                zip.copyBoundedTo(output, MAX_MANIFEST_BYTES)
                            }.toByteArray()
                            manifest = Properties().apply {
                                ByteArrayInputStream(bytes).use { load(it) }
                            }
                        }
                        DATABASE_ENTRY -> {
                            require(!databaseSeen) { "Duplicate database entry" }
                            databaseSeen = true
                            stagedDatabase.outputStream().use { output ->
                                zip.copyBoundedTo(output, MAX_DATABASE_BYTES)
                            }
                        }
                        else -> error("Unexpected backup entry: ${entry.name}")
                    }
                    zip.closeEntry()
                }
            }
            val metadata = requireNotNull(manifest) { "Backup manifest is missing" }
            require(databaseSeen && stagedDatabase.isFile) { "Backup database is missing" }
            require(metadata.getProperty("formatVersion") == FORMAT_VERSION.toString()) {
                "Unsupported backup version"
            }
            require(
                metadata.getProperty("databaseSha256")
                    ?.equals(stagedDatabase.sha256(), ignoreCase = true) == true,
            ) { "Backup checksum does not match" }
            validateDatabase(stagedDatabase)
            PlatoonRepository.withExclusiveDatabase {
                replaceDatabase(stagedDatabase)
            }
            PlatoonRepository.markLegacyImportComplete(appContext)
        } finally {
            stagedDatabase.delete()
        }
    }

    private fun validateDatabase(file: File) {
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
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                require(!cursor.moveToFirst()) { "Backup database has invalid references" }
            }
        }
    }

    private fun replaceDatabase(staged: File) {
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

    private fun InputStream.copyBoundedTo(output: OutputStream, maximum: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximum) { "Backup database exceeds the size limit" }
            output.write(buffer, 0, count)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FILE_EXTENSION = "gf2backup"
        private const val DATABASE_ENTRY = "platoon.db"
        private const val MANIFEST_ENTRY = "manifest.properties"
        private const val FORMAT_VERSION = 1
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val MAX_DATABASE_BYTES = 50L * 1024 * 1024
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
