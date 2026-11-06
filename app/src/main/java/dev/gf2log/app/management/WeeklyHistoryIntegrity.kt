package dev.gf2log.app.management

import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.management.PlatoonDatabase.Companion.MAX_WEEKLY_REPORT_HISTORY
import java.io.ByteArrayOutputStream

/**
 * Whole-table checks over stored weekly revisions: what a restored database may contain, and what
 * a member deletion must remove. Payloads are read in bounded chunks, so a blob larger than a
 * cursor window is rejected or deleted instead of failing every later read of its week.
 */
internal object WeeklyHistoryIntegrity {
    private const val CHUNK_BYTES = 256 * 1024

    /**
     * Requires every history row and selection of a restored database to have a shape the app
     * itself writes: at most [MAX_WEEKLY_REPORT_HISTORY] revisions per week, a bounded payload
     * and a SHA-256 fingerprint, and selections that point within their own week. These limits
     * hold for every revision any app version stored, so a violation proves a crafted file.
     */
    fun requireRestorable(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT 1 FROM weekly_report_history GROUP BY period_start HAVING COUNT(*) > ? LIMIT 1",
            arrayOf(MAX_WEEKLY_REPORT_HISTORY.toString()),
        ).use { cursor -> require(!cursor.moveToFirst()) { "Backup keeps too many weekly revisions" } }
        db.rawQuery(
            "SELECT 1 FROM weekly_report_history WHERE typeof(period_start) != 'integer' " +
                "OR typeof(report_blob) != 'blob' OR length(report_blob) NOT BETWEEN 1 AND ? " +
                "OR typeof(fingerprint) != 'text' OR length(fingerprint) != 64 " +
                "OR fingerprint GLOB '*[^0-9a-f]*' LIMIT 1",
            arrayOf(WeeklyReportHistoryCodec.MAX_PAYLOAD_BYTES.toString()),
        ).use { cursor -> require(!cursor.moveToFirst()) { "Backup has an invalid weekly revision" } }
        db.rawQuery(
            "SELECT 1 FROM weekly_report_history_state state " +
                "LEFT JOIN weekly_report_history history ON history.id = state.active_history_id " +
                "WHERE state.active_history_id IS NOT NULL " +
                "AND (history.id IS NULL OR history.period_start != state.period_start) LIMIT 1",
            null,
        ).use { cursor -> require(!cursor.moveToFirst()) { "Backup selects a missing weekly revision" } }
    }

    /**
     * Deletes from a database being rebuilt for restore every revision that does not decode, or
     * decodes to another week, then any selection left without its revision. History is derived
     * and the weekly screen already ignores such rows, and a decoder that later grows stricter
     * must not turn the user's own backups, or a CSV import's rollback, into failures.
     */
    fun dropUnreadableRevisions(db: SQLiteDatabase) {
        rows(db).forEach { row ->
            val readable = runCatching {
                WeeklyReportHistoryCodec.decodeRevision(payload(db, row.id, row.length))
                    .report.periodStart.toEpochDay() == row.periodStart
            }.getOrDefault(false)
            if (!readable) db.delete("weekly_report_history", "id = ?", arrayOf(row.id.toString()))
        }
        deleteDanglingSelections(db)
    }

    /**
     * Deletes every revision that shows or names [uid], and every revision that no longer decodes,
     * then every selection left pointing at a deleted revision, across all weeks. Runs inside the
     * caller's deletion transaction so the member and every copy of their details go together.
     */
    fun purgeRevisionsReferencing(db: SQLiteDatabase, uid: Long) {
        rows(db).forEach { row ->
            val referencing = row.length !in 1L..WeeklyReportHistoryCodec.MAX_PAYLOAD_BYTES ||
                runCatching {
                    references(WeeklyReportHistoryCodec.decodeRevision(payload(db, row.id, row.length)), uid)
                }.getOrDefault(true)
            if (referencing) {
                db.delete("weekly_report_history", "id = ?", arrayOf(row.id.toString()))
            }
        }
        deleteDanglingSelections(db)
    }

    /** Explicit rather than relying on ON DELETE SET NULL, which needs foreign keys enabled. */
    private fun deleteDanglingSelections(db: SQLiteDatabase) {
        db.delete(
            "weekly_report_history_state",
            "active_history_id IS NOT NULL AND active_history_id NOT IN " +
                "(SELECT id FROM weekly_report_history)",
            null,
        )
    }

    /** Whether any part of [revision] can render [uid]: a table row, an event, a name or a note. */
    internal fun references(revision: WeeklyTableRevision, uid: Long): Boolean =
        revision.report.members.any { it.uid == uid } ||
            revision.membershipEvents.any { it.uid == uid } ||
            uid in revision.memberNamesByUid ||
            uid in revision.memberPrivateNotesByUid

    /** The 1-based `substr` ranges that read [length] bytes in cursor-window-safe chunks. */
    internal fun chunkRanges(length: Long, chunkBytes: Int = CHUNK_BYTES): List<LongRange> =
        (1L..length step chunkBytes.toLong()).map { start ->
            start..minOf(length, start + chunkBytes - 1)
        }

    private fun payload(db: SQLiteDatabase, id: Long, length: Long): ByteArray {
        require(length in 1L..WeeklyReportHistoryCodec.MAX_PAYLOAD_BYTES) {
            "Invalid weekly history payload size"
        }
        val output = ByteArrayOutputStream(length.toInt())
        chunkRanges(length).forEach { range ->
            db.rawQuery(
                "SELECT substr(report_blob, ?, ?) FROM weekly_report_history WHERE id = ?",
                arrayOf(
                    range.first.toString(),
                    (range.last - range.first + 1).toString(),
                    id.toString(),
                ),
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Weekly revision disappeared while it was read" }
                output.write(cursor.getBlob(0))
            }
        }
        return output.toByteArray()
    }

    private fun rows(db: SQLiteDatabase): List<StoredRow> = db.rawQuery(
        "SELECT id, period_start, length(report_blob) FROM weekly_report_history ORDER BY id",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(StoredRow(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2)))
            }
        }
    }

    private class StoredRow(val id: Long, val periodStart: Long, val length: Long)
}
