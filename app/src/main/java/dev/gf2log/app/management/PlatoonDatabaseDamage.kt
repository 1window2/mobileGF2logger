package dev.gf2log.app.management

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.AtomicFile
import android.util.Log
import java.io.File

/**
 * A Platoon database that SQLite reported corrupt, or that failed its integrity check. Nothing
 * is rebuilt or exported over it until a backup is restored or the user chooses to rebuild.
 */
internal class PlatoonDatabaseDamagedException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Never deletes a corrupt Platoon database. The platform default handler removes the file and its
 * journals, after which the next open silently creates an empty schema and the retained roster CSVs
 * repopulate only part of it. Instead the files move aside under a `.corrupt-<time>` name and a
 * durable per-scope marker refuses every open until a restore installs a database again or the
 * user explicitly acknowledges the damage.
 */
internal object PlatoonDatabaseDamage {
    private const val TAG = "GF2DatabaseDamage"
    private const val MARKER_FILE = "database-damaged"
    private const val CORRUPT_SUFFIX = ".corrupt-"

    /** Whether opening the live database would silently create an empty one over lost data. */
    fun isDamaged(context: Context, scope: PlatoonStorageScope): Boolean {
        // Every helper call checks this, so the common case costs a single stat.
        if (!marker(context, scope).isFile) return false
        return blocksOpen(
            markerExists = true,
            databaseExists = context.applicationContext.getDatabasePath(scope.databaseName).exists(),
        )
    }

    /**
     * A marker only blocks while the live file is missing: a restore installs a database before its
     * commit clears the marker, and a quarantine that could not move the file away leaves it in
     * place rather than deleting it.
     */
    internal fun blocksOpen(markerExists: Boolean, databaseExists: Boolean): Boolean =
        markerExists && !databaseExists

    fun requireUsable(context: Context, scope: PlatoonStorageScope) {
        if (isDamaged(context, scope)) {
            throw PlatoonDatabaseDamagedException("The Platoon database is damaged")
        }
    }

    /**
     * The handler for the live database of [scope]; other files are only closed, never deleted.
     *
     * Corruption found while the platform opens a file calls this handler and then retries the
     * open on the same object. That object is not open yet, so closing it would release it for
     * good and the retry would hand back a closed database; and once the live file has moved
     * aside the retry would create an empty one in its place, which the marker no longer blocks.
     * So a live database found corrupt while opening throws the typed damage instead.
     */
    fun errorHandler(context: Context, scope: PlatoonStorageScope, databaseName: String?) =
        DatabaseErrorHandler { database ->
            val path = database.path
            val opening = !database.isOpen
            closeIfOpen(database)
            if (databaseName == scope.databaseName && path != null) {
                runCatching { quarantine(context, scope, File(path)) }
                    .onFailure { Log.e(TAG, "Unable to quarantine a corrupt Platoon database", it) }
                if (opening) throw PlatoonDatabaseDamagedException("The Platoon database is damaged")
            }
        }

    /**
     * A handler that keeps every file, for read-only checks of files the app does not own. A file
     * found corrupt while opening fails the platform's retry with the corruption itself.
     */
    val keepFiles = DatabaseErrorHandler(::closeIfOpen)

    private fun closeIfOpen(database: SQLiteDatabase) {
        if (database.isOpen) runCatching { database.close() }
    }

    /**
     * Writes the marker first, then moves the journals and finally the main file, so a process death
     * at any point leaves either the original files in place or a marker that blocks re-creation.
     */
    fun quarantine(context: Context, scope: PlatoonStorageScope, database: File) {
        val targets = quarantineTargets(database, System.currentTimeMillis())
        writeMarker(marker(context, scope), targets.last().second.name)
        targets.forEach { (live, kept) ->
            if (live.exists() && !live.renameTo(kept)) {
                error("Unable to move the corrupt Platoon database aside")
            }
        }
        File(database.path + "-shm").delete()
    }

    /** Journals before the main file, the order the restore rollback copy also uses. */
    internal fun quarantineTargets(database: File, stamp: Long): List<Pair<File, File>> {
        val kept = File(database.parentFile, database.name + CORRUPT_SUFFIX + stamp)
        return listOf("-wal", "-journal").map { suffix ->
            File(database.path + suffix) to File(kept.path + suffix)
        } + (database to kept)
    }

    /** Quarantined copies of [scope]'s database, which a Platoon deletion also removes. */
    fun quarantinedFiles(context: Context, scope: PlatoonStorageScope): List<File> {
        val database = context.applicationContext.getDatabasePath(scope.databaseName)
        val prefix = database.name + CORRUPT_SUFFIX
        return database.parentFile?.listFiles().orEmpty().filter { it.name.startsWith(prefix) }
    }

    /**
     * The user's choice to continue without the damaged data. A database that failed its
     * integrity check but was never quarantined moves aside first; the quarantined copy stays,
     * and the next access creates a fresh database that retained roster CSVs repopulate.
     */
    fun setAsideForRebuild(context: Context, scope: PlatoonStorageScope) {
        PlatoonRepository.withExclusiveDatabase(scope) {
            // An import's rollback would otherwise install its checkpoint over the rebuilt copy.
            CsvImportCheckpointManager.requireAccessAllowed(scope)
            val database = context.applicationContext.getDatabasePath(scope.databaseName)
            if (database.exists()) quarantine(context, scope, database)
            clear(context, scope)
        }
    }

    /**
     * The rebuild every screen offers: sets the damaged database aside, then repopulates a fresh
     * one from the retained roster CSVs. Everything the rosters do not carry starts empty.
     */
    fun rebuildFromRetainedCsv(context: Context, scope: PlatoonStorageScope): PlatoonRepository.ImportResult {
        setAsideForRebuild(context, scope)
        return PlatoonRepository(context, scope).reconcileRetainedCsvFiles()
    }

    /** Removes the marker; callers hold the exclusive gate or have just installed a database. */
    fun clear(context: Context, scope: PlatoonStorageScope) {
        val marker = marker(context, scope)
        check(!marker.exists() || marker.delete()) { "Unable to clear the damaged-database marker" }
    }

    /**
     * Fails unless SQLite's quick check passes, without letting a failure delete the file. Used
     * before a backup or CSV checkpoint copies the database, so neither is written from damage.
     */
    fun requireIntact(context: Context, scope: PlatoonStorageScope, database: File) {
        val intact = runCatching {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY, keepFiles)
                .use { db ->
                    db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                        cursor.moveToFirst() && cursor.count == 1 &&
                            cursor.getString(0).equals("ok", ignoreCase = true)
                    }
                }
        }.getOrDefault(false)
        if (!intact) {
            throw PlatoonDatabaseDamagedException("The Platoon database failed its integrity check")
        }
    }

    /** Reports SQLite corruption from [block] as the typed damage the UI explains. */
    inline fun <T> reporting(block: () -> T): T = try {
        block()
    } catch (error: SQLiteDatabaseCorruptException) {
        throw PlatoonDatabaseDamagedException("The Platoon database is damaged", error)
    }

    private fun marker(context: Context, scope: PlatoonStorageScope) =
        File(scope.rootDirectory(context), MARKER_FILE)

    private fun writeMarker(file: File, quarantinedName: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(quarantinedName.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}
