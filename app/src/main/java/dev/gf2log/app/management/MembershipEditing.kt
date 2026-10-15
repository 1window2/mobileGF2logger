package dev.gf2log.app.management

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.management.MembershipRows.insertEvent
import dev.gf2log.app.management.MembershipRows.joinEventType
import dev.gf2log.app.management.MembershipRows.memberExists
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Manual member and membership-period edits, each verified against the canonical timeline. */
internal object MembershipEditing {
    fun addWithdrawnMember(
        db: SQLiteDatabase,
        uid: Long,
        name: String,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue,
        note: String,
    ): Boolean {
        require(uid > 0)
        require(name.isNotBlank())
        require(isValidMembershipRange(joined, withdrew))
        return db.inTransaction {
            if (memberExists(db, uid)) return false
            MembershipRows.insertMember(
                db = db,
                uid = uid,
                name = name.trim(),
                level = 0,
                isActive = false,
                firstSeenAt = joined.instant,
                lastSeenAt = withdrew.instant,
                customName = name.trim(),
            )
            val membershipPeriodId = insertManualMembershipPeriod(db, uid, joined, withdrew, note)
            replaceManualMembershipPeriodEvents(db, membershipPeriodId, uid, name.trim(), joined, withdrew)
            synchronizeAndVerifyMembershipState(db, uid)
            true
        }
    }

    fun addMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Boolean {
        require(isValidMembershipRange(joined, withdrew))
        return db.inTransaction {
            val memberName = memberName(db, uid) ?: return false
            if (!canStoreMembershipPeriod(db, uid, null, joined, withdrew)) return false
            val membershipPeriodId = insertManualMembershipPeriod(db, uid, joined, withdrew, note)
            replaceManualMembershipPeriodEvents(db, membershipPeriodId, uid, memberName, joined, withdrew)
            synchronizeAndVerifyMembershipState(db, uid)
            true
        }
    }

    fun updateMember(db: SQLiteDatabase, uid: Long, name: String, note: String): Boolean {
        require(name.isNotBlank())
        val values = ContentValues().apply {
            put("custom_name", name.trim())
            put("note", note.trim())
        }
        return db.update("members", values, "uid = ?", arrayOf(uid.toString())) == 1
    }

    fun deleteMember(db: SQLiteDatabase, uid: Long): Boolean {
        require(uid > 0)
        return db.inTransaction {
            if (!memberExists(db, uid)) return false
            val uidArgument = arrayOf(uid.toString())
            db.delete("weekly_overrides", "uid = ?", uidArgument)
            db.delete(
                "platoon_activity",
                "resolved_uid = ? OR member_event_id IN " +
                    "(SELECT id FROM member_events WHERE uid = ?)",
                arrayOf(uid.toString(), uid.toString()),
            )
            db.delete(
                "weekly_notes",
                "is_automatic = 1 AND event_id IN " +
                    "(SELECT id FROM member_events WHERE uid = ?)",
                uidArgument,
            )
            db.delete("member_events", "uid = ?", uidArgument)
            db.delete("membership_periods", "uid = ?", uidArgument)
            db.delete("snapshot_members", "uid = ?", uidArgument)
            val deleted = db.delete("members", "uid = ?", uidArgument) == 1
            check(deleted) { "Member disappeared during deletion" }
            true
        }
    }

    /**
     * Deletes one membership period and its derived boundary events atomically. The member's
     * final period is protected; returns false when the period is missing or is the only one.
     */
    fun deleteMembershipPeriod(db: SQLiteDatabase, membershipPeriodId: Long): Boolean {
        require(membershipPeriodId > 0)
        return db.inTransaction {
            val uid = db.rawQuery(
                "SELECT uid FROM membership_periods WHERE id = ?",
                arrayOf(membershipPeriodId.toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: return false
            val periodCount = db.rawQuery(
                "SELECT COUNT(*) FROM membership_periods WHERE uid = ?",
                arrayOf(uid.toString()),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
            if (periodCount <= 1L) return false
            if (!MembershipRows.deleteMembershipPeriod(db, membershipPeriodId)) return false
            synchronizeAndVerifyMembershipState(db, uid)
            true
        }
    }

    fun updateMembershipPeriod(
        db: SQLiteDatabase,
        gameZone: () -> ZoneId,
        membershipPeriodId: Long,
        joined: MembershipBoundaryValue,
        left: MembershipBoundaryValue?,
        note: String,
    ): Boolean = db.inTransaction {
        val period = readMembershipPeriodForUpdate(db, membershipPeriodId, gameZone)
            ?: return false
        // Exact packet-linked boundaries stay immutable; only editable sides take the request.
        val effectiveJoined = if (period.joinedSource.isImmutableMembershipBoundary()) {
            period.joined
        } else {
            joined
        } ?: return false
        val effectiveLeft = if (period.leftSource?.isImmutableMembershipBoundary() == true) {
            period.left
        } else {
            left
        }
        require(isValidMembershipRange(effectiveJoined, effectiveLeft))
        if (!canStoreMembershipPeriod(db, period.uid, membershipPeriodId, effectiveJoined, effectiveLeft)) {
            return false
        }
        val joinedChanged = editableMembershipBoundaryChanged(
            original = period.joined,
            requested = joined,
            source = period.joinedSource,
        )
        val leftChanged = editableMembershipBoundaryChanged(
            original = period.left,
            requested = left,
            source = period.leftSource,
        )
        val updated = db.update(
            "membership_periods",
            ContentValues().apply {
                if (joinedChanged) putManualBoundary("joined", joined)
                if (leftChanged) putManualBoundary("left", left)
                put("note", note.trim())
            },
            "id = ?",
            arrayOf(membershipPeriodId.toString()),
        ) == 1
        if (updated && (joinedChanged || leftChanged)) {
            val joinedIsManual = joinedChanged || period.joinedSource == EvidenceSource.MANUAL
            val leftIsManual = if (leftChanged) {
                left != null
            } else {
                period.leftSource == EvidenceSource.MANUAL
            }
            replaceManualMembershipPeriodEvents(
                db,
                membershipPeriodId,
                period.uid,
                memberName(db, period.uid).orEmpty(),
                effectiveJoined.takeIf { joinedIsManual },
                effectiveLeft.takeIf { leftIsManual },
            )
        }
        if (updated) synchronizeAndVerifyMembershipState(db, period.uid)
        updated
    }

    /**
     * Evaluates a proposed manual period together with every persisted period for the member,
     * rejecting overlaps and multiple open periods before any row changes.
     */
    private fun canStoreMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        replacedPeriodId: Long?,
        joined: MembershipBoundaryValue,
        left: MembershipBoundaryValue?,
    ): Boolean {
        val proposed = MembershipInterval(
            id = replacedPeriodId ?: Long.MAX_VALUE,
            joinedAt = joined.instant,
            leftAt = left?.instant,
            joinedDate = joined.date,
            leftDate = left?.date,
            joinedTimeKnown = joined.timeKnown,
            leftTimeKnown = left?.timeKnown ?: true,
        )
        val periods = readMembershipIntervals(db, uid)
            .filterNot { it.id == replacedPeriodId } + proposed
        return MembershipConsistencyPolicy.violation(periods) == null
    }

    /**
     * Derives current activity from the canonical period timeline, then verifies chronology and
     * event ownership before the surrounding transaction may commit.
     */
    private fun synchronizeAndVerifyMembershipState(db: SQLiteDatabase, uid: Long) {
        val periods = readMembershipIntervals(db, uid)
        checkNotNull(periods.takeIf { it.isNotEmpty() }) { "A member must retain a membership period" }
        val violation = MembershipConsistencyPolicy.violation(periods)
        check(violation == null) { violation.orEmpty() }
        val updated = db.update(
            "members",
            ContentValues().apply {
                put("is_active", if (MembershipConsistencyPolicy.isActive(periods)) 1 else 0)
            },
            "uid = ?",
            arrayOf(uid.toString()),
        )
        check(updated == 1) { "Membership state has no owning member" }
        val mismatchedEvent = db.rawQuery(
            "SELECT 1 FROM member_events event " +
                "JOIN membership_periods period ON period.id = event.membership_period_id " +
                "WHERE event.uid != period.uid AND (event.uid = ? OR period.uid = ?) LIMIT 1",
            arrayOf(uid.toString(), uid.toString()),
        ).use(Cursor::moveToFirst)
        check(!mismatchedEvent) { "Membership event belongs to a different member" }
    }

    private fun readMembershipIntervals(db: SQLiteDatabase, uid: Long): List<MembershipInterval> =
        db.rawQuery(
            "SELECT id, joined_at, left_at, joined_date, left_date, " +
                "joined_time_known, left_time_known " +
                "FROM membership_periods WHERE uid = ? ORDER BY id",
            arrayOf(uid.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MembershipInterval(
                            id = cursor.getLong(0),
                            joinedAt = cursor.getNullableInstant(1),
                            leftAt = cursor.getNullableInstant(2),
                            joinedDate = cursor.getNullableLong(3)?.let(LocalDate::ofEpochDay),
                            leftDate = cursor.getNullableLong(4)?.let(LocalDate::ofEpochDay),
                            joinedTimeKnown = cursor.getNullableInt(5)?.let { it != 0 } ?: true,
                            leftTimeKnown = cursor.getNullableInt(6)?.let { it != 0 } ?: true,
                        ),
                    )
                }
            }
        }

    private fun insertManualMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Long = db.insertOrThrow(
        "membership_periods",
        null,
        ContentValues().apply {
            put("uid", uid)
            putManualBoundary("joined", joined)
            putManualBoundary("left", withdrew)
            put("note", note.trim())
        },
    )

    /** A manual boundary, or a cleared one when [value] is null. */
    private fun ContentValues.putManualBoundary(prefix: String, value: MembershipBoundaryValue?) {
        if (value == null) {
            clearMembershipBoundary(prefix)
            putNull("${prefix}_precision")
            putNull("${prefix}_source")
        } else {
            putMembershipBoundary(prefix, value)
            put("${prefix}_precision", EvidencePrecision.MANUAL.name)
            put("${prefix}_source", EvidenceSource.MANUAL.name)
        }
    }

    private fun replaceManualMembershipPeriodEvents(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        uid: Long,
        memberName: String,
        joined: MembershipBoundaryValue?,
        withdrew: MembershipBoundaryValue?,
    ) {
        db.delete(
            "member_events",
            "membership_period_id = ? AND source = ?",
            arrayOf(membershipPeriodId.toString(), EvidenceSource.MANUAL.name),
        )
        val observedAt = Instant.now()
        listOfNotNull(
            joined?.let { joinEventType(db, uid, membershipPeriodId) to it },
            withdrew?.let { MemberEventType.LEFT to it },
        ).forEach { (type, boundary) ->
            insertEvent(
                db = db,
                uid = uid,
                type = type,
                occurredAt = boundary.instant,
                eventDate = boundary.date,
                timeKnown = boundary.timeKnown,
                observedAt = observedAt,
                precision = EvidencePrecision.MANUAL,
                source = EvidenceSource.MANUAL,
                note = memberName,
                membershipPeriodId = membershipPeriodId,
            )
        }
    }

    private fun memberName(db: SQLiteDatabase, uid: Long): String? =
        db.rawQuery(
            "SELECT COALESCE(custom_name, current_name) FROM members WHERE uid = ?",
            arrayOf(uid.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun readMembershipPeriodForUpdate(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        gameZone: () -> ZoneId,
    ): MembershipPeriodForUpdate? = db.rawQuery(
        """
        SELECT uid, joined_at, left_at, joined_date, left_date,
               joined_time_known, left_time_known, joined_source, left_source
        FROM membership_periods
        WHERE id = ?
        """.trimIndent(),
        arrayOf(membershipPeriodId.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            val zoneId = gameZone()
            MembershipPeriodForUpdate(
                uid = cursor.getLong(0),
                joined = cursor.membershipBoundaryValue(
                    instantIndex = 1,
                    dateIndex = 3,
                    timeKnownIndex = 5,
                    zoneId = zoneId,
                ),
                left = cursor.membershipBoundaryValue(
                    instantIndex = 2,
                    dateIndex = 4,
                    timeKnownIndex = 6,
                    zoneId = zoneId,
                ),
                joinedSource = EvidenceSource.valueOf(cursor.getString(7)),
                leftSource = cursor.getNullableString(8)?.let(EvidenceSource::valueOf),
            )
        }
    }

    private data class MembershipPeriodForUpdate(
        val uid: Long,
        val joined: MembershipBoundaryValue?,
        val left: MembershipBoundaryValue?,
        val joinedSource: EvidenceSource,
        val leftSource: EvidenceSource?,
    )
}
