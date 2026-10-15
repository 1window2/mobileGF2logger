package dev.gf2log.app.management

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.management.ExactBoundaryCorrelation.applyExactMembershipBoundary
import dev.gf2log.app.management.PlatoonDatabase.Companion.DAILY_PATROL_REWARD_ACTION_ID
import dev.gf2log.app.management.PlatoonDatabase.Companion.MAX_STORED_ACTIVITY_OBSERVATIONS
import java.time.Instant

/** Stores 21935 activity facts, resolves names to UIDs, and correlates them with inferred boundaries. */
internal object ActivityIngestion {
    fun ingest(
        db: SQLiteDatabase,
        observations: List<PlatoonActivityObservation>,
        capturedAt: Instant,
    ): ActivityIngestResult {
        val acceptedObservations = PlatoonObservationPolicy.activity(observations)
        if (acceptedObservations.isEmpty()) return ActivityIngestResult(0, 0, 0)
        return db.inTransaction {
            var inserted = 0
            var resolved = 0
            acceptedObservations.forEach { observation ->
                val resolvedUid = resolveUidForName(
                    db,
                    observation.memberName,
                    observation.occurredAt,
                )
                val result = db.insertWithOnConflict(
                    "platoon_activity",
                    null,
                    ContentValues().apply {
                        put("occurred_at", observation.occurredAt.toEpochMilli())
                        put("action_id", observation.actionId)
                        put("kind", observation.kind)
                        put("member_name", observation.memberName)
                        put("captured_at", capturedAt.toEpochMilli())
                        putNullableLong("resolved_uid", resolvedUid)
                        put(
                            "resolution",
                            if (resolvedUid == null) {
                                ActivityResolution.UNRESOLVED.name
                            } else {
                                ActivityResolution.UNIQUE_ROSTER_NAME.name
                            },
                        )
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (result != -1L) {
                    inserted += 1
                    if (resolvedUid != null) resolved += 1
                }
            }
            trimPlatoonActivity(db)
            resolveUnresolvedActivityUids(db)
            reconcileInferredMembershipBoundaries(db)
            ActivityIngestResult(acceptedObservations.size, inserted, resolved)
        }
    }

    private fun resolveUidForName(
        db: SQLiteDatabase,
        memberName: String,
        occurredAt: Instant,
    ): Long? {
        val windowStart = occurredAt.minusMillis(NAME_RESOLUTION_WINDOW_MILLIS).toEpochMilli()
        val windowEnd = occurredAt.plusMillis(NAME_RESOLUTION_WINDOW_MILLIS).toEpochMilli()
        val historical = db.rawQuery(
            """
            SELECT DISTINCT sm.uid
            FROM snapshot_members sm
            JOIN snapshots s ON s.id = sm.snapshot_id
            WHERE sm.name = ? COLLATE BINARY
              AND s.captured_at >= ?
              AND s.captured_at <= ?
            LIMIT 2
            """.trimIndent(),
            arrayOf(memberName, windowStart.toString(), windowEnd.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        if (historical.size == 1) return historical.single()
        if (historical.size > 1) return null

        val current = db.rawQuery(
            "SELECT uid FROM members WHERE current_name = ? COLLATE BINARY LIMIT 2",
            arrayOf(memberName),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        return current.singleOrNull()
    }

    fun resolveUnresolvedActivityUids(db: SQLiteDatabase) {
        PlatoonDatabaseSchema.createPlatoonMaintenanceStateTable(db)
        val cursorId = db.rawQuery(
            "SELECT value FROM platoon_maintenance_state WHERE key = ?",
            arrayOf(ACTIVITY_RESOLUTION_CURSOR_KEY),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        val unresolved = readUnresolvedActivityBatch(
            db = db,
            selection = "resolved_uid IS NULL AND id > ?",
            selectionArgs = arrayOf(cursorId.toString()),
            limit = MAX_UNRESOLVED_ACTIVITY_RESOLUTIONS,
        ).toMutableList()
        if (unresolved.size < MAX_UNRESOLVED_ACTIVITY_RESOLUTIONS) {
            unresolved += readUnresolvedActivityBatch(
                db = db,
                selection = "resolved_uid IS NULL AND id <= ?",
                selectionArgs = arrayOf(cursorId.toString()),
                limit = MAX_UNRESOLVED_ACTIVITY_RESOLUTIONS - unresolved.size,
            )
        }
        unresolved.forEach { activity ->
            val uid = resolveUidForName(db, activity.memberName, activity.occurredAt)
                ?: return@forEach
            val updated = db.updateWithOnConflict(
                "platoon_activity",
                ContentValues().apply {
                    put("resolved_uid", uid)
                    put("resolution", ActivityResolution.UNIQUE_ROSTER_NAME.name)
                },
                "id = ? AND resolved_uid IS NULL",
                arrayOf(activity.id.toString()),
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (updated == 0) {
                db.delete(
                    "platoon_activity",
                    """
                    id = ? AND EXISTS (
                        SELECT 1
                        FROM platoon_activity existing
                        WHERE existing.id != ?
                          AND existing.occurred_at = ?
                          AND existing.action_id = ?
                          AND existing.kind = ?
                          AND existing.resolved_uid = ?
                    )
                    """.trimIndent(),
                    arrayOf(
                        activity.id.toString(),
                        activity.id.toString(),
                        activity.occurredAt.toEpochMilli().toString(),
                        activity.actionId.toString(),
                        activity.kind.toString(),
                        uid.toString(),
                    ),
                )
            }
        }
        unresolved.lastOrNull()?.let { activity ->
            db.insertWithOnConflict(
                "platoon_maintenance_state",
                null,
                ContentValues().apply {
                    put("key", ACTIVITY_RESOLUTION_CURSOR_KEY)
                    put("value", activity.id)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private fun readUnresolvedActivityBatch(
        db: SQLiteDatabase,
        selection: String,
        selectionArgs: Array<String>,
        limit: Int,
    ): List<UnresolvedActivity> {
        if (limit <= 0) return emptyList()
        return db.query(
            "platoon_activity",
            arrayOf("id", "occurred_at", "member_name", "action_id", "kind"),
            selection,
            selectionArgs,
            null,
            null,
            "id",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        UnresolvedActivity(
                            id = cursor.getLong(0),
                            occurredAt = Instant.ofEpochMilli(cursor.getLong(1)),
                            memberName = cursor.getString(2),
                            actionId = cursor.getLong(3),
                            kind = cursor.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Caps network-derived activity evidence to the newest bounded history. Runs on open and after
     * each ingest so an older oversized database self-heals.
     */
    fun trimPlatoonActivity(db: SQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM platoon_activity
            WHERE id IN (
                SELECT id
                FROM platoon_activity
                ORDER BY captured_at DESC, id DESC
                LIMIT -1 OFFSET $MAX_STORED_ACTIVITY_OBSERVATIONS
            )
            """.trimIndent(),
        )
    }

    fun correlateMembershipBoundary(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        uid: Long,
        memberName: String,
        type: MemberEventType,
        boundary: MembershipBoundary,
        observedAt: Instant,
        from: Instant?,
        nameIsUnique: Boolean,
    ) {
        if (!nameIsUnique || from == null) return
        val candidates = readMembershipActivityCandidates(
            db = db,
            memberName = memberName,
            from = from,
            until = observedAt,
        )
        val occurredAt = candidates.map(ActivityCandidate::occurredAt).distinct().singleOrNull()
            ?: return
        applyExactMembershipBoundary(
            db = db,
            membershipPeriodId = membershipPeriodId,
            uid = uid,
            memberName = memberName,
            type = type,
            boundary = boundary,
            occurredAt = occurredAt,
            observedAt = observedAt,
            activityIds = candidates.map(ActivityCandidate::id),
        )
    }

    private fun readMembershipActivityCandidates(
        db: SQLiteDatabase,
        memberName: String,
        from: Instant,
        until: Instant,
    ): List<ActivityCandidate> = db.rawQuery(
        """
        SELECT id, occurred_at
        FROM platoon_activity
        WHERE member_name = ? COLLATE BINARY
          AND occurred_at > ?
          AND occurred_at <= ?
          AND action_id NOT IN (?, ?)
          AND member_event_id IS NULL
        ORDER BY occurred_at, id
        """.trimIndent(),
        arrayOf(
            memberName,
            from.toEpochMilli().toString(),
            until.toEpochMilli().toString(),
            DAILY_PATROL_REWARD_ACTION_ID.toString(),
            DAILY_PATROL_RELATED_ACTION_ID.toString(),
        ),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(ActivityCandidate(cursor.getLong(0), Instant.ofEpochMilli(cursor.getLong(1))))
            }
        }
    }

    /**
     * Upgrades an inferred roster boundary to an exact one when a single linked activity fact
     * falls within the correlation window. Candidate boundaries are loaded once per ingest; an
     * applied boundary becomes exact and therefore leaves the candidate set.
     */
    private fun reconcileInferredMembershipBoundaries(db: SQLiteDatabase) {
        val boundariesByUid = readInferredBoundaries(db)
        if (boundariesByUid.isEmpty()) return
        val activities = db.rawQuery(
            """
            SELECT id, occurred_at, member_name, resolved_uid
            FROM platoon_activity
            WHERE member_event_id IS NULL
              AND resolved_uid IS NOT NULL
              AND action_id NOT IN (?, ?)
              AND resolved_uid IN (
                SELECT uid FROM membership_periods
                WHERE joined_precision = ? OR left_precision = ?
              )
            ORDER BY occurred_at, id
            """.trimIndent(),
            arrayOf(
                DAILY_PATROL_REWARD_ACTION_ID.toString(),
                DAILY_PATROL_RELATED_ACTION_ID.toString(),
                EvidencePrecision.INFERRED.name,
                EvidencePrecision.INFERRED.name,
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ResolvedActivityCandidate(
                            id = cursor.getLong(0),
                            occurredAt = Instant.ofEpochMilli(cursor.getLong(1)),
                            memberName = cursor.getString(2),
                            uid = cursor.getLong(3),
                        ),
                    )
                }
            }
        }
        activities.forEach { activity ->
            val candidates = boundariesByUid[activity.uid] ?: return@forEach
            val boundary = candidates.singleOrNull { candidate ->
                kotlin.math.abs(
                    candidate.inferredAt.toEpochMilli() - activity.occurredAt.toEpochMilli(),
                ) <= MEMBERSHIP_CORRELATION_WINDOW_MILLIS
            } ?: return@forEach
            val eventType = when (boundary.boundary) {
                MembershipBoundary.WITHDRAW -> MemberEventType.LEFT
                MembershipBoundary.JOIN ->
                    MembershipRows.joinEventType(db, boundary.uid, boundary.membershipPeriodId)
            }
            applyExactMembershipBoundary(
                db = db,
                membershipPeriodId = boundary.membershipPeriodId,
                uid = boundary.uid,
                memberName = activity.memberName,
                type = eventType,
                boundary = boundary.boundary,
                occurredAt = activity.occurredAt,
                observedAt = boundary.inferredAt,
                activityIds = listOf(activity.id),
            )
            candidates.remove(boundary)
        }
    }

    private fun readInferredBoundaries(db: SQLiteDatabase): Map<Long, MutableList<InferredBoundary>> =
        db.rawQuery(
            """
            SELECT id, uid, joined_at, left_at, joined_precision, left_precision
            FROM membership_periods
            WHERE (joined_at IS NOT NULL AND joined_precision = ?)
               OR (left_at IS NOT NULL AND left_precision = ?)
            """.trimIndent(),
            arrayOf(EvidencePrecision.INFERRED.name, EvidencePrecision.INFERRED.name),
        ).use { cursor ->
            val boundaries = mutableMapOf<Long, MutableList<InferredBoundary>>()
            while (cursor.moveToNext()) {
                val membershipPeriodId = cursor.getLong(0)
                val uid = cursor.getLong(1)
                val forMember = boundaries.getOrPut(uid, ::mutableListOf)
                val joinedAt = cursor.getNullableInstant(2)
                if (joinedAt != null && cursor.getString(4) == EvidencePrecision.INFERRED.name) {
                    forMember += InferredBoundary(membershipPeriodId, uid, MembershipBoundary.JOIN, joinedAt)
                }
                val leftAt = cursor.getNullableInstant(3)
                if (leftAt != null && cursor.getNullableString(5) == EvidencePrecision.INFERRED.name) {
                    forMember += InferredBoundary(membershipPeriodId, uid, MembershipBoundary.WITHDRAW, leftAt)
                }
            }
            boundaries
        }

    private data class ActivityCandidate(
        val id: Long,
        val occurredAt: Instant,
    )

    private data class ResolvedActivityCandidate(
        val id: Long,
        val occurredAt: Instant,
        val memberName: String,
        val uid: Long,
    )

    private data class UnresolvedActivity(
        val id: Long,
        val occurredAt: Instant,
        val memberName: String,
        val actionId: Long,
        val kind: Long,
    )

    private data class InferredBoundary(
        val membershipPeriodId: Long,
        val uid: Long,
        val boundary: MembershipBoundary,
        val inferredAt: Instant,
    )

    private const val MAX_UNRESOLVED_ACTIVITY_RESOLUTIONS = 250
    private const val ACTIVITY_RESOLUTION_CURSOR_KEY = "activity_resolution_cursor"
    private const val DAILY_PATROL_RELATED_ACTION_ID = 801005L
    private const val NAME_RESOLUTION_WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1000L
    private const val MEMBERSHIP_CORRELATION_WINDOW_MILLIS = 12L * 60L * 60L * 1000L
}
