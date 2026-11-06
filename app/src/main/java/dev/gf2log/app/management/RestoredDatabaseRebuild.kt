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

    /** Far beyond any id the app could reach, and exact in every number type that carries it. */
    internal const val MAX_RESTORED_ROW_ID = 1L shl 53

    /**
     * Hard caps well below a cursor window, above anything the app or an older version stored:
     * the editors' own limits are lower and are not applied to restored data.
     */
    internal const val MAX_RESTORED_NOTE_CHARS = 16 * ManualTextPolicy.MAX_NOTE_CHARS
    internal const val MAX_RESTORED_NAME_CHARS = 4 * ManualTextPolicy.MAX_MEMBER_NAME_CHARS

    internal class EnumColumn(val table: String, val column: String, val names: List<String>, val nullable: Boolean)

    internal class TextColumn(val table: String, val column: String, val maxChars: Int)

    /** Every column stored by enum name, with the names its reader parses. */
    internal val ENUM_COLUMNS: List<EnumColumn> = listOf(
        EnumColumn("membership_periods", "joined_precision", EvidencePrecision.entries.map { it.name }, nullable = false),
        EnumColumn("membership_periods", "left_precision", EvidencePrecision.entries.map { it.name }, nullable = true),
        EnumColumn("membership_periods", "joined_source", EvidenceSource.entries.map { it.name }, nullable = false),
        EnumColumn("membership_periods", "left_source", EvidenceSource.entries.map { it.name }, nullable = true),
        EnumColumn("member_events", "event_type", MemberEventType.entries.map { it.name }, nullable = false),
        EnumColumn("member_events", "precision", EvidencePrecision.entries.map { it.name }, nullable = false),
        EnumColumn("member_events", "source", EvidenceSource.entries.map { it.name }, nullable = false),
        EnumColumn("platoon_activity", "resolution", ActivityResolution.entries.map { it.name }, nullable = false),
    )

    /**
     * Every other app TEXT column. Weekly history fingerprints are checked by
     * [WeeklyHistoryIntegrity], and maintenance keys are filtered to the known ones.
     */
    internal val TEXT_COLUMNS: List<TextColumn> = listOf(
        TextColumn("members", "current_name", MAX_RESTORED_NAME_CHARS),
        TextColumn("members", "custom_name", MAX_RESTORED_NAME_CHARS),
        TextColumn("members", "note", MAX_RESTORED_NOTE_CHARS),
        TextColumn("snapshot_members", "name", MAX_RESTORED_NAME_CHARS),
        TextColumn("platoon_activity", "member_name", MAX_RESTORED_NAME_CHARS),
        TextColumn("membership_periods", "note", MAX_RESTORED_NOTE_CHARS),
        TextColumn("member_events", "note", MAX_RESTORED_NOTE_CHARS),
        TextColumn("weekly_notes", "text", MAX_RESTORED_NOTE_CHARS),
        TextColumn("snapshots", "source_file", MAX_RESTORED_NOTE_CHARS),
        TextColumn("snapshots", "game_version", MAX_RESTORED_NOTE_CHARS),
    )

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
                    // Opening the empty copy stamped maintenance state that describes no rows; the
                    // archive's own state describes the rows that arrive, so it replaces it.
                    db.execSQL("DELETE FROM main.platoon_maintenance_state")
                    copyStatements(appColumns(db)).forEach(db::execSQL)
                    // Row content the readers would trip over for good is refused before any
                    // repair reads it; the restore then fails as an invalid backup.
                    requireBoundedRowIds(db)
                    requireReadableRows(db)
                    copySequences(db)
                    // Keys the app never writes would be adopted wholesale otherwise.
                    dropUnknownMaintenanceKeys(db)
                    // Active flags are derived data: the archive's flags and rule stamp are not
                    // trusted, they are derived again with the current rule.
                    MembershipRows.rederiveActiveStates(db)
                    // Unqualified names resolve to main, the rebuilt copy.
                    WeeklyHistoryIntegrity.dropUnreadableRevisions(db)
                    // An archive already at the current version skipped the upgrade's repair.
                    InvalidMemberIdentityRepair.repair(db)
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
            val list = columns.joinToString(", ", transform = ::sqlIdentifier)
            val quoted = sqlIdentifier(table)
            "INSERT INTO main.$quoted ($list) SELECT $list FROM $SOURCE.$quoted"
        }

    internal fun sqliteFiles(database: File): List<File> =
        listOf("", "-wal", "-shm", "-journal").map { File(database.path + it) }

    private fun appColumns(db: SQLiteDatabase): Map<String, List<String>> {
        val tables = db.rawQuery(
            "SELECT name FROM main.sqlite_master WHERE $APP_TABLES_FILTER",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.associateWith { table ->
            db.rawQuery("PRAGMA main.table_info(${sqlIdentifier(table)})", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
        }
    }

    /**
     * An AUTOINCREMENT table whose largest id or archived counter sits at the top of the range
     * could never take another row: SQLite continues from max(counter, largest id).
     */
    private fun requireBoundedRowIds(db: SQLiteDatabase) {
        autoIncrementTables(db).forEach { table ->
            val largest = db.rawQuery("SELECT MAX(id) FROM main.${sqlIdentifier(table)}", null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
            require(largest == null || largest <= MAX_RESTORED_ROW_ID) {
                "Backup table $table has an out-of-range row id"
            }
        }
    }

    /**
     * Every enum-name column holds a name its reader accepts, and every free-text column fits
     * well inside one cursor window. A value the app reads with valueOf, or a cell larger than the
     * window, would otherwise fail every member list or weekly load of the restored Platoon.
     */
    private fun requireReadableRows(db: SQLiteDatabase) {
        ENUM_COLUMNS.forEach { column ->
            val placeholders = column.names.joinToString(", ") { "?" }
            val quoted = sqlIdentifier(column.column)
            val invalid = db.rawQuery(
                "SELECT 1 FROM main.${sqlIdentifier(column.table)} " +
                    "WHERE ($quoted IS NULL AND ${if (column.nullable) 0 else 1}) " +
                    "OR $quoted NOT IN ($placeholders) LIMIT 1",
                column.names.toTypedArray(),
            ).use { it.moveToFirst() }
            require(!invalid) { "Backup column ${column.table}.${column.column} has an unknown value" }
        }
        TEXT_COLUMNS.forEach { column ->
            val quoted = sqlIdentifier(column.column)
            // The cap is inlined: rawQuery binds text, and SQLite orders every integer below text.
            val invalid = db.rawQuery(
                "SELECT 1 FROM main.${sqlIdentifier(column.table)} " +
                    "WHERE $quoted IS NOT NULL AND (typeof($quoted) = 'blob' OR length($quoted) > ${column.maxChars}) LIMIT 1",
                null,
            ).use { it.moveToFirst() }
            require(!invalid) { "Backup column ${column.table}.${column.column} holds an oversized value" }
        }
    }

    private fun dropUnknownMaintenanceKeys(db: SQLiteDatabase) {
        val keys = PlatoonDatabaseSchema.MAINTENANCE_KEYS.toList()
        db.execSQL(
            "DELETE FROM main.platoon_maintenance_state WHERE key NOT IN (${keys.joinToString(", ") { "?" }})",
            keys.toTypedArray(),
        )
    }

    private fun autoIncrementTables(db: SQLiteDatabase): Set<String> = db.rawQuery(
        "SELECT name FROM main.sqlite_master WHERE type = 'table' AND sql LIKE '%AUTOINCREMENT%'",
        null,
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** Keeps AUTOINCREMENT counters, so IDs of rows deleted before the backup are not reused. */
    private fun copySequences(db: SQLiteDatabase) {
        val hasSequences = db.rawQuery(
            "SELECT 1 FROM $SOURCE.sqlite_master WHERE type = 'table' AND name = 'sqlite_sequence'",
            null,
        ).use { it.moveToFirst() }
        if (!hasSequences) return
        val autoIncrementTables = autoIncrementTables(db)
        val sequences = db.rawQuery("SELECT name, seq FROM $SOURCE.sqlite_sequence", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    if (name in autoIncrementTables && !cursor.isNull(1)) put(name, cursor.getLong(1))
                }
            }
        }
        sequences.forEach { (table, sequence) ->
            require(sequence <= MAX_RESTORED_ROW_ID) { "Backup table $table has an out-of-range counter" }
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
