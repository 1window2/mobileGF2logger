package dev.gf2log.app.management

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.LocalDate

/** Row-level member, period, and event writes shared by every evidence path. */
internal object MembershipRows {
    fun memberExists(db: SQLiteDatabase, uid: Long): Boolean =
        db.exists("SELECT 1 FROM members WHERE uid = ? LIMIT 1", uid)

    fun insertMember(
        db: SQLiteDatabase,
        uid: Long,
        name: String,
        level: Long,
        isActive: Boolean,
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
                put("is_active", if (isActive) 1 else 0)
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
        if (db.exists("SELECT 1 FROM membership_periods WHERE uid = ? AND left_at IS NULL LIMIT 1", uid)) {
            return
        }
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

    /** Deletes the selected events after detaching every note and activity fact citing them. */
    fun deleteEvents(db: SQLiteDatabase, selection: String, arguments: Array<String>) {
        val selectedIds = "SELECT id FROM member_events WHERE $selection"
        db.delete("weekly_notes", "is_automatic = 1 AND event_id IN ($selectedIds)", arguments)
        db.update(
            "weekly_notes",
            ContentValues().apply { putNull("event_id") },
            "event_id IN ($selectedIds)",
            arguments,
        )
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
     * Repairs the derived active flag while upgrading databases created before schema v13.
     * Leaves every evidence period intact and derives only the current-state projection.
     */
    fun synchronizeAllMembershipStates(db: SQLiteDatabase) {
        db.execSQL(
            """
            UPDATE members
            SET is_active = CASE WHEN EXISTS (
                SELECT 1
                FROM membership_periods period
                WHERE period.uid = members.uid AND period.left_at IS NULL
            ) THEN 1 ELSE 0 END
            """.trimIndent(),
        )
    }
}
