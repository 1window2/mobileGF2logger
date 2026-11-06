package dev.gf2log.app.management

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.management.PlatoonDatabase.Companion.MAX_WEEKLY_REPORT_HISTORY
import java.time.Instant
import java.time.LocalDate

internal data class WeeklyReportHistoryReplacement(
    val periodStartEpochDay: Long,
    val recordedAt: Instant,
    val fingerprint: String,
    val payload: ByteArray,
)

/** Derived weekly-table revisions and the restored-selection state. */
internal object WeeklyHistoryStore {
    /**
     * Appends one changed immutable weekly projection and retains the newest
     * [MAX_WEEKLY_REPORT_HISTORY] revisions. A restored selection is cleared only when
     * [clearActiveOnChange] and the live fingerprint changed. Returns the existing or new ID.
     * Recency is the AUTOINCREMENT ID, never the wall-clock [recordedAt]: after the device clock
     * steps backwards a new revision must still be the latest rather than be trimmed at once.
     */
    fun record(
        db: SQLiteDatabase,
        periodStartEpochDay: Long,
        recordedAt: Instant,
        fingerprint: String,
        payload: ByteArray,
        clearActiveOnChange: Boolean,
    ): Long {
        requireValidRevision(fingerprint, payload)
        return db.inTransaction {
            val latest = db.rawQuery(
                "SELECT id, fingerprint FROM weekly_report_history " +
                    "WHERE period_start = ? ORDER BY id DESC LIMIT 1",
                arrayOf(periodStartEpochDay.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
            }
            if (latest?.second == fingerprint) return@inTransaction latest.first
            val id = db.insertOrThrow(
                "weekly_report_history",
                null,
                historyRow(periodStartEpochDay, recordedAt, fingerprint, payload),
            )
            if (clearActiveOnChange) {
                db.delete(
                    "weekly_report_history_state",
                    "period_start = ?",
                    arrayOf(periodStartEpochDay.toString()),
                )
            }
            db.delete(
                "weekly_report_history",
                "period_start = ? AND id NOT IN (" +
                    "SELECT id FROM weekly_report_history WHERE period_start = ? " +
                    "ORDER BY id DESC LIMIT ?)",
                arrayOf(
                    periodStartEpochDay.toString(),
                    periodStartEpochDay.toString(),
                    MAX_WEEKLY_REPORT_HISTORY.toString(),
                ),
            )
            id
        }
    }

    fun list(db: SQLiteDatabase, periodStartEpochDay: Long): List<WeeklyReportHistoryEntry> =
        db.rawQuery(
            "SELECT history.id, history.recorded_at, " +
                "CASE WHEN state.active_history_id = history.id THEN 1 ELSE 0 END " +
                "FROM weekly_report_history history " +
                "LEFT JOIN weekly_report_history_state state " +
                "ON state.period_start = history.period_start " +
                "WHERE history.period_start = ? " +
                "ORDER BY history.id DESC LIMIT ?",
            arrayOf(periodStartEpochDay.toString(), MAX_WEEKLY_REPORT_HISTORY.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WeeklyReportHistoryEntry(
                            id = cursor.getLong(0),
                            periodStart = LocalDate.ofEpochDay(periodStartEpochDay),
                            recordedAt = Instant.ofEpochMilli(cursor.getLong(1)),
                            active = cursor.getInt(2) != 0,
                        ),
                    )
                }
            }
        }

    /**
     * The stored revision, null when absent, or an empty [INVALID_PAYLOAD] when its blob is outside
     * the payload limit, which is never loaded into the cursor window.
     */
    fun payload(db: SQLiteDatabase, id: Long, periodStartEpochDay: Long): ByteArray? =
        db.rawQuery(
            "SELECT $BOUNDED_PAYLOAD FROM weekly_report_history WHERE id = ? AND period_start = ?",
            arrayOf(id.toString(), periodStartEpochDay.toString()),
        ).use(::boundedPayload)

    /** The selected revision, null when none is selected, or [INVALID_PAYLOAD] as in [payload]. */
    fun activePayload(db: SQLiteDatabase, periodStartEpochDay: Long): ByteArray? =
        db.rawQuery(
            "SELECT $BOUNDED_PAYLOAD FROM weekly_report_history_state state " +
                "JOIN weekly_report_history history ON history.id = state.active_history_id " +
                "WHERE state.period_start = ? AND history.period_start = state.period_start",
            arrayOf(periodStartEpochDay.toString()),
        ).use(::boundedPayload)

    private fun boundedPayload(cursor: Cursor): ByteArray? = when {
        !cursor.moveToFirst() -> null
        cursor.isNull(0) -> INVALID_PAYLOAD
        else -> cursor.getBlob(0)
    }

    fun activate(db: SQLiteDatabase, id: Long, periodStartEpochDay: Long): Boolean {
        val exists = db.exists(
            "SELECT 1 FROM weekly_report_history WHERE id = ? AND period_start = ?",
            id,
            periodStartEpochDay,
        )
        if (!exists) return false
        db.insertWithOnConflict(
            "weekly_report_history_state",
            null,
            ContentValues().apply {
                put("period_start", periodStartEpochDay)
                put("active_history_id", id)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return true
    }

    fun clearActive(db: SQLiteDatabase, periodStartEpochDay: Long): Boolean =
        db.delete(
            "weekly_report_history_state",
            "period_start = ?",
            arrayOf(periodStartEpochDay.toString()),
        ) > 0

    /**
     * Replaces every derived weekly revision in one SQLite transaction, validating the complete
     * replacement before deleting existing history.
     */
    fun replace(db: SQLiteDatabase, replacements: List<WeeklyReportHistoryReplacement>) {
        require(replacements.map { it.periodStartEpochDay }.distinct().size == replacements.size)
        replacements.forEach { requireValidRevision(it.fingerprint, it.payload) }
        db.inTransaction {
            deleteAll(db)
            replacements.forEach { replacement ->
                db.insertOrThrow(
                    "weekly_report_history",
                    null,
                    historyRow(
                        replacement.periodStartEpochDay,
                        replacement.recordedAt,
                        replacement.fingerprint,
                        replacement.payload,
                    ),
                )
            }
        }
    }

    private fun deleteAll(db: SQLiteDatabase) {
        db.delete("weekly_report_history_state", null, null)
        db.delete("weekly_report_history", null, null)
    }

    private fun historyRow(
        periodStartEpochDay: Long,
        recordedAt: Instant,
        fingerprint: String,
        payload: ByteArray,
    ) = ContentValues().apply {
        put("period_start", periodStartEpochDay)
        put("recorded_at", recordedAt.toEpochMilli())
        put("fingerprint", fingerprint)
        put("report_blob", payload)
    }

    private fun requireValidRevision(fingerprint: String, payload: ByteArray) {
        require(fingerprint.matches(FINGERPRINT))
        require(payload.size in 1..WeeklyReportHistoryCodec.MAX_PAYLOAD_BYTES)
    }

    private val FINGERPRINT = Regex("[0-9a-f]{64}")

    /** Selects report_blob only within the payload limit, otherwise NULL. */
    private val BOUNDED_PAYLOAD =
        "CASE WHEN length(report_blob) BETWEEN 1 AND ${WeeklyReportHistoryCodec.MAX_PAYLOAD_BYTES} " +
            "THEN report_blob END"

    /** A payload no codec accepts, standing for a stored blob outside the payload limit. */
    val INVALID_PAYLOAD = ByteArray(0)
}
