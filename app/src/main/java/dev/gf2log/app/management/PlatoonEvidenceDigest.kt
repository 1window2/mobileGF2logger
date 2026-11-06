package dev.gf2log.app.management

import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Digests the primary Platoon evidence of a database for the CSV import undo guard.
 *
 * Every table counts except the derived weekly history, its restored selection, and app, SQLite,
 * or Android bookkeeping, so recording or selecting a weekly revision never makes an otherwise valid
 * undo look like newer work, while any evidence, membership, note, or manual-value change does.
 * Rows are hashed in content order, independent of where SQLite stores them.
 */
internal object PlatoonEvidenceDigest {
    /**
     * Derived tables that the undo checkpoint restores as well but that never hold evidence. The
     * maintenance state keeps bookkeeping only: the activity-resolution cursor, the history zone
     * and the version of the active-state rule.
     */
    private val DERIVED_TABLES = setOf(
        "weekly_report_history",
        "weekly_report_history_state",
        "platoon_maintenance_state",
    )

    /** Whether [table] holds evidence the digest covers. */
    fun covers(table: String): Boolean =
        table !in DERIVED_TABLES &&
            table != "android_metadata" &&
            !table.startsWith("sqlite_")

    /**
     * Opens [databaseFile] read-only; the caller holds the exclusive database section. The caller
     * names the [errorHandler]: the platform default would delete a live database it finds corrupt.
     */
    fun sha256(databaseFile: File, errorHandler: DatabaseErrorHandler): String =
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY, errorHandler)
            .use(::sha256)

    fun sha256(db: SQLiteDatabase): String {
        val hasher = Hasher(db.version)
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
            null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        tables.filter(::covers).forEach { table ->
            val quoted = sqlIdentifier(table)
            val columns = db.rawQuery("SELECT * FROM $quoted LIMIT 0", null).use { it.columnNames.toList() }
            hasher.table(table, columns)
            val order = columns.indices.joinToString(", ") { (it + 1).toString() }
            db.rawQuery("SELECT * FROM $quoted ORDER BY $order", null).use { cursor ->
                while (cursor.moveToNext()) {
                    hasher.row(List(cursor.columnCount) { index -> cursor.value(index) })
                }
            }
        }
        return hasher.hex()
    }

    private fun Cursor.value(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> getBlob(index)
        else -> getString(index)
    }

    /**
     * Feeds tables and rows into SHA-256 with a type tag and length for every value, so no two
     * different databases share an encoding.
     */
    class Hasher(schemaVersion: Int) {
        private val digest = MessageDigest.getInstance("SHA-256")

        init {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(schemaVersion).array())
        }

        fun table(name: String, columns: List<String>) {
            tag(TABLE)
            text(name)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(columns.size).array())
            columns.forEach(::text)
        }

        fun row(values: List<Any?>) {
            tag(ROW)
            values.forEach { value ->
                when (value) {
                    null -> tag(NULL)
                    is Long -> {
                        tag(INTEGER)
                        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
                    }
                    is Double -> {
                        tag(FLOAT)
                        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value.toRawBits()).array())
                    }
                    is ByteArray -> {
                        tag(BLOB)
                        bytes(value)
                    }
                    else -> {
                        tag(TEXT)
                        text(value.toString())
                    }
                }
            }
        }

        fun hex(): String = digest.digest().toHex()

        private fun tag(value: Byte) = digest.update(value)

        private fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))

        private fun bytes(value: ByteArray) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
            digest.update(value)
        }
    }

    private const val TABLE: Byte = 1
    private const val ROW: Byte = 2
    private const val NULL: Byte = 3
    private const val INTEGER: Byte = 4
    private const val FLOAT: Byte = 5
    private const val TEXT: Byte = 6
    private const val BLOB: Byte = 7
}
