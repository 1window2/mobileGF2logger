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

    fun closeLatestMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        leftAt: Instant,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ): Long {
        val membershipPeriodId = db.rawQuery(
            "SELECT id FROM membership_periods WHERE uid = ? AND left_at IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf(uid.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: error("Active member $uid has no open membership period")
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
        return membershipPeriodId
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
     * omits the member. When rosters and Updates agree this is simply "some period is open",
     * the projection the schema-v13 upgrade repairs.
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
                WHERE latest_roster.captured_at = (SELECT MAX(captured_at) FROM snapshots)
                  AND latest_roster.captured_at > current_period.joined_at
                  AND NOT EXISTS (
                    SELECT 1
                    FROM snapshot_members roster_member
                    JOIN snapshots roster ON roster.id = roster_member.snapshot_id
                    WHERE roster_member.uid = current_period.uid
                      AND roster.captured_at = latest_roster.captured_at
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

    private const val MAX_BOUND_ARGUMENTS = 500
}
