package dev.gf2log.app.management

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant

/**
 * Sets aside stored rosters whose capture time lies ahead of the clock, and brings them back
 * when that proves wrong; see [SnapshotClockPolicy]. A set-aside roster keeps every row and
 * stays countable ([setAsideCount]); it only stops taking part in roster history, so a roster
 * captured with the phone clock set ahead never stays the current roster and never hides the
 * real rosters captured after it. Every read that orders or picks rosters (the latest roster,
 * comparison baselines, weekly inputs, replay frames, evidence days and membership
 * correlation) selects `clock_suspect = 0`; only source-file deduplication and the evidence
 * digest still see every row.
 */
internal object SnapshotClockReview {
    /**
     * Reviews every stored roster against [deviceNow] and the newest server time known, which
     * includes [incomingNewestLogin] from the roster being ingested. Runs inside the caller's
     * ingest transaction before the roster is placed. When a roster moves either way, what it
     * inferred is withdrawn or restored by replaying the remaining history; returns whether
     * anything moved.
     */
    fun review(db: SQLiteDatabase, deviceNow: Instant, incomingNewestLogin: Instant?): Boolean {
        val reference = SnapshotClockPolicy.reference(
            deviceNow,
            listOfNotNull(newestServerTime(db), incomingNewestLogin).maxOrNull(),
        )
        val ahead = rosters(db, suspect = false).filter { (_, capturedAt) ->
            SnapshotClockPolicy.isAhead(capturedAt, reference)
        }
        val returning = rosters(db, suspect = true).filter { (id, capturedAt) ->
            SnapshotClockPolicy.mayReturn(capturedAt, reference) { isContradicted(db, id, capturedAt) }
        }
        if (ahead.isEmpty() && returning.isEmpty()) return false
        ahead.forEach { (id, _) -> setSuspect(db, id, suspect = true) }
        returning.forEach { (id, _) -> setSuspect(db, id, suspect = false) }
        ahead.map { it.second }.distinct().forEach { instant -> withdrawInferences(db, instant) }
        RosterHistoryReplay.reconcile(db)
        MembershipRows.foldAllDuplicateOpenPeriods(db)
        MembershipRows.synchronizeAllActiveStates(db)
        return true
    }

    fun setAsideCount(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM snapshots WHERE clock_suspect != 0", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /** The newest server-sourced instant stored: a roster login, an activity, or an Updates time. */
    private fun newestServerTime(db: SQLiteDatabase): Instant? = db.rawQuery(
        """
        SELECT MAX(server_time) FROM (
            SELECT MAX(last_login) * 1000 AS server_time FROM snapshot_members WHERE last_login > 0
            UNION ALL SELECT MAX(occurred_at) FROM platoon_activity
            UNION ALL SELECT MAX(occurred_at) FROM member_events WHERE source = ?
        )
        """.trimIndent(),
        arrayOf(EvidenceSource.GAME_UPDATES.name),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getNullableInstant(0) else null }

    private fun rosters(db: SQLiteDatabase, suspect: Boolean): List<Pair<Long, Instant>> = db.rawQuery(
        "SELECT id, captured_at FROM snapshots WHERE clock_suspect ${if (suspect) "!=" else "="} 0",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getLong(0) to Instant.ofEpochMilli(cursor.getLong(1)))
        }
    }

    /**
     * Whether a roster dated earlier than set-aside roster [id], kept in the history and not
     * itself dated behind its own logins ([SnapshotClockPolicy.isBehind]), shows a newer login
     * for one of its members ([SnapshotClockPolicy.contradicts]).
     */
    private fun isContradicted(db: SQLiteDatabase, id: Long, capturedAt: Instant): Boolean = db.rawQuery(
        """
        SELECT own.last_login, other.last_login
        FROM snapshot_members own
        JOIN snapshot_members other ON other.uid = own.uid AND other.last_login > own.last_login
        JOIN snapshots earlier ON earlier.id = other.snapshot_id
        WHERE own.snapshot_id = ?
          AND own.last_login > 0
          AND earlier.clock_suspect = 0
          AND earlier.captured_at < ?
          AND (
            SELECT MAX(login.last_login) FROM snapshot_members login
            WHERE login.snapshot_id = earlier.id
          ) * 1000 <= earlier.captured_at + ?
        """.trimIndent(),
        arrayOf(
            id.toString(),
            capturedAt.toEpochMilli().toString(),
            SnapshotClockPolicy.MAX_CLOCK_SKEW.toMillis().toString(),
        ),
    ).use { cursor ->
        var contradicted = false
        while (!contradicted && cursor.moveToNext()) {
            contradicted = SnapshotClockPolicy.contradicts(cursor.getLong(0), cursor.getLong(1))
        }
        contradicted
    }

    private fun setSuspect(db: SQLiteDatabase, id: Long, suspect: Boolean) {
        db.update(
            "snapshots",
            ContentValues().apply { put("clock_suspect", if (suspect) 1 else 0) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    /**
     * Withdraws what a roster set aside at [instant] inferred when it was the current roster and
     * the replay cannot rebuild: renames, and withdrawals it closed on periods the replay keeps
     * (exact, manual or annotated ones). Those periods reopen, so the replay or the next roster
     * closes them at a real absence. Weak periods are rebuilt by the replay itself.
     */
    private fun withdrawInferences(db: SQLiteDatabase, instant: Instant) {
        val at = instant.toEpochMilli().toString()
        if (db.exists("SELECT 1 FROM snapshots WHERE captured_at = ? AND clock_suspect = 0 LIMIT 1", at)) return
        val sources = SNAPSHOT_EVENT_SOURCES.map(EvidenceSource::name)
        MembershipRows.deleteEvents(
            db,
            "event_type = ? AND observed_at = ? AND source IN (?, ?)",
            arrayOf(MemberEventType.RENAMED.name, at, sources[0], sources[1]),
        )
        val closed = db.rawQuery(
            "SELECT id FROM membership_periods " +
                "WHERE left_at = ? AND left_precision = ? AND left_source IN (?, ?)",
            arrayOf(at, EvidencePrecision.INFERRED.name, sources[0], sources[1]),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        closed.forEach { membershipPeriodId ->
            val period = arrayOf(membershipPeriodId.toString())
            MembershipRows.deleteSupersededEvents(
                db,
                "membership_period_id = ? AND event_type IN (?, ?) AND source IN (?, ?)",
                arrayOf(
                    period[0],
                    MemberEventType.LEFT.name,
                    MemberEventType.REMOVED.name,
                    sources[0],
                    sources[1],
                ),
            )
            db.update(
                "membership_periods",
                ContentValues().apply {
                    clearMembershipBoundary("left")
                    putNull("left_precision")
                    putNull("left_source")
                },
                "id = ?",
                period,
            )
        }
    }
}
