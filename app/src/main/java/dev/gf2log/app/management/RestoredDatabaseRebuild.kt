package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Rebuilds a validated backup database into app-authored DDL. A restored file can carry table
 * constraints that schema introspection does not report (CHECK, ON CONFLICT, COLLATE, STRICT,
 * WITHOUT ROWID), so its schema is never installed: only its rows are copied, column by column,
 * into a database that the current schema code created.
 */
internal object RestoredDatabaseRebuild {
    private const val SOURCE = "restored_source"

    /**
     * Upgrades [source], a private staged copy, to the current schema, then writes the rebuilt
     * database to [target]. Both files are the caller's to delete.
     */
    fun rebuild(context: Context, scope: PlatoonStorageScope, source: File, target: File) {
        val version = SQLiteDatabase.openDatabase(
            source.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
            PlatoonDatabaseDamage.keepFiles,
        ).use { it.version }
        if (version < PlatoonSchema.CURRENT_VERSION) {
            // The same migrations an installed older database would run; only this copy changes.
            PlatoonDatabase(context, source.absolutePath, scope).use { it.writableDatabase }
        }
        sqliteFiles(target).forEach { check(!it.exists() || it.delete()) { "Unable to clear a stale rebuild" } }
        PlatoonDatabase(context, target.absolutePath, scope).use { it.writableDatabase }
        // A plain connection keeps foreign keys off while rows arrive in any table order; the
        // complete result is checked before it commits.
        SQLiteDatabase.openDatabase(
            target.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            PlatoonDatabaseDamage.keepFiles,
        ).use { db ->
            db.execSQL("ATTACH DATABASE ? AS $SOURCE", arrayOf<Any>(source.path))
            try {
                db.inTransaction {
                    copyStatements(appColumns(db)).forEach(db::execSQL)
                    copySequences(db)
                    // Unqualified names resolve to main, the rebuilt copy.
                    WeeklyHistoryIntegrity.dropUnreadableRevisions(db)
                    db.rawQuery("PRAGMA main.foreign_key_check", null).use { cursor ->
                        require(!cursor.moveToFirst()) { "Backup database has invalid references" }
                    }
                }
            } finally {
                db.execSQL("DETACH DATABASE $SOURCE")
            }
        }
        File(target.path + "-shm").delete()
        // A closed connection leaves at most an empty journal (TRUNCATE mode); anything else would
        // hold committed state that the main file lacks and must not be separated from it.
        listOf("-wal", "-journal").map { File(target.path + it) }.forEach { journal ->
            if (!journal.exists()) return@forEach
            check(journal.length() == 0L && journal.delete()) {
                "The rebuilt Platoon database was not checkpointed"
            }
        }
    }

    /** One explicit-column copy per app table; a source column the app does not define is ignored. */
    internal fun copyStatements(columnsByTable: Map<String, List<String>>): List<String> =
        columnsByTable.toSortedMap().map { (table, columns) ->
            require(columns.isNotEmpty()) { "Table $table has no columns" }
            val list = columns.joinToString(", ", transform = ::identifier)
            "INSERT INTO main.${identifier(table)} ($list) SELECT $list FROM $SOURCE.${identifier(table)}"
        }

    internal fun identifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    internal fun sqliteFiles(database: File): List<File> =
        listOf("", "-wal", "-shm", "-journal").map { File(database.path + it) }

    private fun appColumns(db: SQLiteDatabase): Map<String, List<String>> {
        val tables = db.rawQuery(
            "SELECT name FROM main.sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.associateWith { table ->
            db.rawQuery("PRAGMA main.table_info(${identifier(table)})", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
        }
    }

    /** Keeps AUTOINCREMENT counters, so IDs of rows deleted before the backup are not reused. */
    private fun copySequences(db: SQLiteDatabase) {
        val hasSequences = db.rawQuery(
            "SELECT 1 FROM $SOURCE.sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'",
            null,
        ).use { it.moveToFirst() }
        if (!hasSequences) return
        val autoIncrementTables = db.rawQuery(
            "SELECT name FROM main.sqlite_master WHERE type = 'table' AND sql LIKE '%AUTOINCREMENT%'",
            null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val sequences = db.rawQuery("SELECT name, seq FROM $SOURCE.sqlite_sequence", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (name in autoIncrementTables && !cursor.isNull(1)) put(name, cursor.getLong(1))
                }
            }
        }
        sequences.forEach { (table, sequence) ->
            val updated = db.compileStatement(
                "UPDATE main.sqlite_sequence SET seq = MAX(seq, ?) WHERE name = ?",
            ).use { statement ->
                statement.bindLong(1, sequence)
                statement.bindString(2, table)
                statement.executeUpdateDelete()
            }
            if (updated == 0) {
                db.execSQL(
                    "INSERT INTO main.sqlite_sequence (name, seq) VALUES (?, ?)",
                    arrayOf<Any>(table, sequence),
                )
            }
        }
    }
}
