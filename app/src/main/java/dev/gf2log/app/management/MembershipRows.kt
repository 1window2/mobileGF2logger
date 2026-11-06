package dev.gf2log.app.management

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.LocalDate

/** Row-level member, period, and event writes shared by every evidence path. */
internal object MembershipRows {
    fun memberExists(db: SQLiteDatabase, uid: Long): Boolean =
        db.exists("SELECT 1 FROM members WHERE uid = ? LIMIT 1", uid)

    /** Inserts an inactive member; callers derive activity with [synchronizeActiveState]. */
    fun insertMember(
        db: SQLiteDatabase,
        uid: Long,
        name: String,
        level: Long,
        firstSeenAt: Instant,
        lastSeenAt: Instant,
        customName: String? = null,
    ) {
        db.insertOrThrow(
            "members",
            null,
            ContentValues().apply {
                put("uid", uid)
                put("current_name", name)
                if (customName != null) put("custom_name", customName)
                put("current_level", level)
                put("is_active", 0)
                put("first_seen_at", firstSeenAt.toEpochMilli())
                put("last_seen_at", lastSeenAt.toEpochMilli())
                put("note", "")
            },
        )
    }

    fun insertMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        joinedAt: Instant?,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ): Long = db.insertOrThrow(
        "membership_periods",
        null,
        ContentValues().apply {
            put("uid", uid)
            putNullableLong("joined_at", joinedAt?.toEpochMilli())
            put("joined_precision", precision.name)
            put("joined_source", source.name)
        },
    )

    /**
     * Closes the member's open membership at a roster-observed withdrawal [leftAt] and returns
     * the newest open period, whose boundary event the caller records. Weak duplicate open
     * periods are folded first, and any other open period is closed at the same boundary with
     * its own event, so a roster omission never leaves an open period behind.
     */
    fun closeLatestMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        memberName: String,
        leftAt: Instant,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ): Long {
        foldDuplicateOpenPeriods(db, uid)
        val membershipPeriodId = db.rawQuery(
            "SELECT id FROM membership_periods WHERE uid = ? AND left_at IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf(uid.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: error("Active member $uid has no open membership period")
        closeMembershipPeriod(db, membershipPeriodId, leftAt, precision, source)
        closeOpenMembershipPeriods(db, uid, memberName, leftAt, precision, source)
        return membershipPeriodId
    }

    /** Closes every open period of [uid] that began by [leftAt], each with its own LEFT event. */
    fun closeOpenMembershipPeriods(
        db: SQLiteDatabase,
        uid: Long,
        memberName: String,
        leftAt: Instant,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ) {
        openMembershipPeriodIds(db, uid, leftAt).forEach { membershipPeriodId ->
            closeMembershipPeriod(db, membershipPeriodId, leftAt, precision, source)
            insertSnapshotBoundaryEvent(
                db = db,
                membershipPeriodId = membershipPeriodId,
                uid = uid,
                memberName = memberName,
                type = MemberEventType.LEFT,
                observedAt = leftAt,
                source = source,
            )
        }
    }

    /** Open periods, newest first, that a withdrawal at [leftAt] can close. */
    private fun openMembershipPeriodIds(db: SQLiteDatabase, uid: Long, leftAt: Instant): List<Long> =
        db.rawQuery(
            "SELECT id FROM membership_periods WHERE uid = ? AND left_at IS NULL " +
                "AND (joined_at IS NULL OR joined_at <= ?) ORDER BY id DESC",
            arrayOf(uid.toString(), leftAt.toEpochMilli().toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

    private fun closeMembershipPeriod(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        leftAt: Instant,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ) {
        db.update(
            "membership_periods",
            ContentValues().apply {
                put("left_at", leftAt.toEpochMilli())
                putNull("left_date")
                put("left_time_known", 1)
                put("left_precision", precision.name)
                put("left_source", source.name)
            },
            "id = ?",
            arrayOf(membershipPeriodId.toString()),
        )
    }

    /**
     * Two open periods claim the same current membership, which no writer may leave behind.
     * Keeps the open period with the strongest join (exact Updates, then manual, then activity
     * or roster evidence) and drops each other open period whose join rests only on roster or
     * activity evidence, unless a roster between the two joins omitted the member: then both
     * are real and stay for the caller. A dropped period's note moves to the kept one; if both
     * notes cannot fit one note, nothing is dropped. Returns how many periods were dropped.
     */
    fun foldDuplicateOpenPeriods(db: SQLiteDatabase, uid: Long): Int {
        val open = db.rawQuery(
            "SELECT id, joined_at, joined_precision, joined_source, note " +
                "FROM membership_periods WHERE uid = ? AND left_at IS NULL",
            arrayOf(uid.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        OpenPeriod(
                            id = cursor.getLong(0),
                            joinedAt = cursor.getNullableLong(1),
                            precision = EvidencePrecision.valueOf(cursor.getString(2)),
                            source = EvidenceSource.valueOf(cursor.getString(3)),
                            note = cursor.getString(4),
                        ),
                    )
                }
            }
        }
        if (open.size < 2) return 0
        val kept = open.sortedWith(compareBy<OpenPeriod> { it.joinStrength }.thenByDescending { it.id }).first()
        var keptNote = kept.note
        var dropped = 0
        open.filter { it.id != kept.id && it.source in DUPLICATE_OPEN_PERIOD_SOURCES }.forEach { duplicate ->
            if (rosterOmittedBetween(db, uid, duplicate.joinedAt, kept.joinedAt)) return@forEach
            val mergedNote = listOf(keptNote, duplicate.note).filter(String::isNotBlank).joinToString("\n")
            if (mergedNote.length > ManualTextPolicy.MAX_NOTE_CHARS) return@forEach
            keptNote = mergedNote
            val period = arrayOf(duplicate.id.toString())
            deleteSupersededEvents(db, "membership_period_id = ?", period)
            db.delete("membership_periods", "id = ?", period)
            dropped += 1
        }
        if (keptNote != kept.note) {
            db.update(
                "membership_periods",
                ContentValues().apply { put("note", keptNote) },
                "id = ?",
                arrayOf(kept.id.toString()),
            )
        }
        return dropped
    }

    /** Whether a roster captured strictly between the two joins lacked [uid]. */
    private fun rosterOmittedBetween(db: SQLiteDatabase, uid: Long, first: Long?, second: Long?): Boolean {
        if (first == null || second == null || first == second) return false
        return db.exists(
            """
            SELECT 1 FROM snapshots roster
            WHERE roster.captured_at > ? AND roster.captured_at < ?
              AND roster.clock_suspect = 0
              AND NOT EXISTS (
                SELECT 1 FROM snapshot_members member
                WHERE member.snapshot_id = roster.id AND member.uid = ?
              )
            LIMIT 1
            """.trimIndent(),
            minOf(first, second),
            maxOf(first, second),
            uid,
        )
    }

    /** Every uid with more than one open period; the one-time v14 repair folds each of them. */
    fun foldAllDuplicateOpenPeriods(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT uid FROM membership_periods WHERE left_at IS NULL GROUP BY uid HAVING COUNT(*) > 1",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            .forEach { uid -> foldDuplicateOpenPeriods(db, uid) }
    }

    /** Joins that only roster or activity evidence supports; a duplicate of these is dropped. */
    private val DUPLICATE_OPEN_PERIOD_SOURCES = SNAPSHOT_EVENT_SOURCES + EvidenceSource.ACTIVITY

    private data class OpenPeriod(
        val id: Long,
        val joinedAt: Long?,
        val precision: EvidencePrecision,
        val source: EvidenceSource,
        val note: String,
    ) {
        val joinStrength: Int
            get() = when {
                source == EvidenceSource.GAME_UPDATES && precision == EvidencePrecision.EXACT -> 0
                source == EvidenceSource.MANUAL -> 1
                source == EvidenceSource.ACTIVITY -> 2
                joinedAt != null -> 3
                else -> 4
            }
    }

    fun ensureRosterActiveMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        source: EvidenceSource,
    ) {
        if (db.exists("${effectiveOpenPeriod("?")} LIMIT 1", uid)) return
        insertMembershipPeriod(
            db = db,
            uid = uid,
            joinedAt = null,
            precision = EvidencePrecision.UNKNOWN,
            source = source,
        )
    }

    fun hasEarlierMembershipPeriod(db: SQLiteDatabase, uid: Long, membershipPeriodId: Long): Boolean =
        db.exists(
            """
            SELECT 1
            FROM membership_periods current
            JOIN membership_periods other ON other.uid = current.uid AND other.id != current.id
            WHERE current.id = ?
              AND current.uid = ?
              AND (
                (current.joined_at IS NOT NULL AND other.joined_at IS NOT NULL AND
                    (other.joined_at < current.joined_at OR
                        (other.joined_at = current.joined_at AND other.id < current.id)))
                OR
                (current.joined_at IS NULL AND other.id < current.id)
              )
            LIMIT 1
            """.trimIndent(),
            membershipPeriodId,
            uid,
        )

    /** JOINED for a member's first period by join order, otherwise REJOINED. */
    fun joinEventType(db: SQLiteDatabase, uid: Long, membershipPeriodId: Long): MemberEventType =
        if (hasEarlierMembershipPeriod(db, uid, membershipPeriodId)) {
            MemberEventType.REJOINED
        } else {
            MemberEventType.JOINED
        }

    fun insertEvent(
        db: SQLiteDatabase,
        uid: Long,
        type: MemberEventType,
        occurredAt: Instant?,
        eventDate: LocalDate? = null,
        timeKnown: Boolean = true,
        observedAt: Instant,
        precision: EvidencePrecision,
        source: EvidenceSource,
        note: String,
        membershipPeriodId: Long? = null,
    ): Long = db.insertOrThrow(
        "member_events",
        null,
        ContentValues().apply {
            put("uid", uid)
            putNullableLong("membership_period_id", membershipPeriodId)
            put("event_type", type.name)
            putNullableLong("occurred_at", occurredAt?.toEpochMilli())
            putNullableLong("event_date", eventDate?.toEpochDay())
            put("time_known", if (timeKnown) 1 else 0)
            put("observed_at", observedAt.toEpochMilli())
            put("precision", precision.name)
            put("source", source.name)
            put("note", note)
        },
    )

    /** JOINED and REJOINED describe the same boundary, so either one satisfies the other. */
    fun compatibleEventTypes(type: MemberEventType): Array<String> = when (type) {
        MemberEventType.JOINED,
        MemberEventType.REJOINED,
        -> arrayOf(MemberEventType.JOINED.name, MemberEventType.REJOINED.name)
        else -> arrayOf(type.name, type.name)
    }

    fun insertSnapshotBoundaryEvent(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        uid: Long,
        memberName: String,
        type: MemberEventType,
        observedAt: Instant,
        source: EvidenceSource,
    ) {
        val compatibleTypes = compatibleEventTypes(type)
        val exists = db.exists(
            "SELECT 1 FROM member_events " +
                "WHERE membership_period_id = ? AND event_type IN (?, ?) LIMIT 1",
            membershipPeriodId,
            compatibleTypes[0],
            compatibleTypes[1],
        )
        if (exists) return
        insertEvent(
            db = db,
            uid = uid,
            type = type,
            occurredAt = null,
            observedAt = observedAt,
            precision = EvidencePrecision.INFERRED,
            source = source,
            note = memberName,
            membershipPeriodId = membershipPeriodId,
        )
    }

    /**
     * Deletes the selected events after detaching every activity fact citing them. Weekly notes
     * never cite events: notes are manual and keep a NULL event_id, whose foreign key would
     * also clear on delete.
     */
    fun deleteEvents(db: SQLiteDatabase, selection: String, arguments: Array<String>) {
        val selectedIds = "SELECT id FROM member_events WHERE $selection"
        db.update(
            "platoon_activity",
            ContentValues().apply { putNull("member_event_id") },
            "member_event_id IN ($selectedIds)",
            arguments,
        )
        db.delete("member_events", selection, arguments)
    }

    /**
     * [deleteEvents] for boundary events that a stronger boundary replaced. Activity facts that
     * dated them return to plain name-resolved history instead of claiming a boundary that no
     * longer exists, and stay eligible to date another inferred boundary.
     */
    fun deleteSupersededEvents(db: SQLiteDatabase, selection: String, arguments: Array<String>) {
        db.update(
            "platoon_activity",
            ContentValues().apply { put("resolution", ActivityResolution.UNIQUE_ROSTER_NAME.name) },
            "resolution = ? AND member_event_id IN (SELECT id FROM member_events WHERE $selection)",
            arrayOf(ActivityResolution.MEMBERSHIP_CORRELATION.name, *arguments),
        )
        deleteEvents(db, selection, arguments)
    }

    /** Deletes one period with its events; returns whether the period row existed. */
    fun deleteMembershipPeriod(db: SQLiteDatabase, membershipPeriodId: Long): Boolean {
        val id = arrayOf(membershipPeriodId.toString())
        deleteEvents(db, "membership_period_id = ?", id)
        return db.delete("membership_periods", "id = ?", id) == 1
    }

    /**
     * The one rule for `members.is_active`: a member is active while an effective open period
     * remains. An open period started by an exact Updates join is superseded by later exit
     * evidence: another period exiting after that join, or a newer authoritative roster that
     * omits the member. When rosters and Updates agree this is simply "some period is open".
     * Any change to this rule must raise [ACTIVE_STATE_RULE_VERSION]. A roster set aside by
     * [SnapshotClockReview] is never the authoritative one; setting one aside or bringing it
     * back re-derives every flag in the same transaction, so that filter needs no new version.
     */
    private fun effectiveOpenPeriod(uid: String): String =
        """
        SELECT 1 FROM membership_periods current_period
        WHERE current_period.uid = $uid
          AND current_period.left_at IS NULL
          AND NOT (
            current_period.joined_source = '${EvidenceSource.GAME_UPDATES.name}'
            AND (
              EXISTS (
                SELECT 1 FROM membership_periods later_exit
                WHERE later_exit.uid = current_period.uid
                  AND later_exit.id != current_period.id
                  AND later_exit.left_at > current_period.joined_at
              )
              OR EXISTS (
                SELECT 1 FROM snapshots latest_roster
                WHERE latest_roster.captured_at =
                    (SELECT MAX(captured_at) FROM snapshots WHERE clock_suspect = 0)
                  AND latest_roster.clock_suspect = 0
                  AND latest_roster.captured_at > current_period.joined_at
                  AND NOT EXISTS (
                    SELECT 1
                    FROM snapshot_members roster_member
                    JOIN snapshots roster ON roster.id = roster_member.snapshot_id
                    WHERE roster_member.uid = current_period.uid
                      AND roster.captured_at = latest_roster.captured_at
                      AND roster.clock_suspect = 0
                  )
              )
            )
          )
        """.trimIndent()

    private val ACTIVE_STATE_UPDATE =
        "UPDATE members SET is_active = CASE WHEN EXISTS (${effectiveOpenPeriod("members.uid")}) " +
            "THEN 1 ELSE 0 END"

    /** Re-derives the active flag of [uids] inside the caller's transaction; returns rows updated. */
    fun synchronizeActiveState(db: SQLiteDatabase, uids: Collection<Long>): Int =
        uids.distinct().chunked(MAX_BOUND_ARGUMENTS).sumOf { chunk ->
            db.compileStatement(
                "$ACTIVE_STATE_UPDATE WHERE uid IN (${chunk.joinToString { "?" }})",
            ).use { statement ->
                chunk.forEachIndexed { index, uid -> statement.bindLong(index + 1, uid) }
                statement.executeUpdateDelete()
            }
        }

    fun synchronizeAllActiveStates(db: SQLiteDatabase) {
        db.execSQL(ACTIVE_STATE_UPDATE)
    }

    /**
     * Guarantees that every stored active flag was derived at least once with the current rule,
     * independent of the schema version: a v13 database written before 2.5.0 changed the rule,
     * or a restored backup of one, still holds flags from the older any-open-period rule.
     */
    fun ensureActiveStateRule(db: SQLiteDatabase) {
        val derivedWith = db.rawQuery(
            "SELECT value FROM platoon_maintenance_state WHERE key = ?",
            arrayOf(ACTIVE_STATE_RULE_KEY),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (derivedWith != null && derivedWith >= ACTIVE_STATE_RULE_VERSION) return
        db.inTransaction {
            synchronizeAllActiveStates(db)
            db.insertWithOnConflict(
                "platoon_maintenance_state",
                null,
                ContentValues().apply {
                    put("key", ACTIVE_STATE_RULE_KEY)
                    put("value", ACTIVE_STATE_RULE_VERSION)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /** Version 2 is the effective-open-period rule introduced in 2.5.0. */
    const val ACTIVE_STATE_RULE_VERSION = 2L
    const val ACTIVE_STATE_RULE_KEY = "active_state_rule"

    private const val MAX_BOUND_ARGUMENTS = 500
}
