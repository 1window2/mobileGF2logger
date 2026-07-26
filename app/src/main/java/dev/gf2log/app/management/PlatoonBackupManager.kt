package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
        get() = appContext.getDatabasePath(DATABASE_NAME)

    fun export(output: OutputStream) {
        PlatoonRepository.closeDatabaseForFileCopy()
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

    fun restore(input: InputStream) {
        val restoreDirectory = File(appContext.cacheDir, "platoon-restore").apply { mkdirs() }
        val stagedDatabase = File(restoreDirectory, "platoon.db.staged")
        if (stagedDatabase.exists() && !stagedDatabase.delete()) {
            error("Unable to clear a previous staged restore")
        }
        var manifest: Properties? = null
        var databaseSeen = false
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Backup cannot contain directories" }
                when (entry.name) {
                    MANIFEST_ENTRY -> {
                        require(manifest == null) { "Duplicate backup manifest" }
                        manifest = Properties().apply { load(zip) }
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
        replaceDatabase(stagedDatabase)
        PlatoonRepository.markLegacyImportComplete(appContext)
    }

    private fun validateDatabase(file: File) {
        file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            require(input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)) {
                "Backup is not a SQLite database"
            }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) == DATABASE_VERSION) {
                    "Unsupported Platoon database schema"
                }
            }
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='members'",
                null,
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Platoon members table is missing" }
            }
        }
    }

    private fun replaceDatabase(staged: File) {
        PlatoonRepository.closeDatabaseForFileCopy()
        databaseFile.parentFile?.mkdirs()
        val previous = File(databaseFile.parentFile, "$DATABASE_NAME.pre_restore")
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
        previous.delete()
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
        private const val DATABASE_NAME = "platoon.db"
        private const val DATABASE_ENTRY = "platoon.db"
        private const val MANIFEST_ENTRY = "manifest.properties"
        private const val FORMAT_VERSION = 1
        private const val DATABASE_VERSION = 1
        private const val MAX_DATABASE_BYTES = 50L * 1024 * 1024
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
