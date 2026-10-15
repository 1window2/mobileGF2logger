package dev.gf2log.app.management

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.management.MembershipRows.insertSnapshotBoundaryEvent
import dev.gf2log.app.management.MembershipRows.joinEventType
import dev.gf2log.app.management.MembershipRows.memberExists
import java.time.Instant

/**
 * Replays every retained roster chronologically after late historical imports. Only weak
 * snapshot-derived periods are rebuilt; manual and exact evidence is preserved, and people seen
 * only in historical rosters become inactive members.
 */
internal object RosterHistoryReplay {
    fun reconcile(db: SQLiteDatabase) {
        val snapshots = readRosterFrames(db)
        if (snapshots.isEmpty()) return

        ensureHistoricalMembers(db, snapshots)
        removeWeakSnapshotMembershipPeriods(db)

        val allUids = snapshots.flatMap { frame -> frame.members.keys }.toSortedSet()
        allUids.forEach { uid ->
            val runs = buildRosterPresenceRuns(snapshots, uid)
            runs.forEach { run ->
                if (!reconcilePreservedMembershipPeriodOverlap(db, uid, run)) {
                    insertRosterPresenceRun(db, uid, run)
                }
            }
        }
        MembershipRows.synchronizeAllActiveStates(db)
        ActivityIngestion.resolveUnresolvedActivityUids(db)
    }

    private fun readRosterFrames(db: SQLiteDatabase): List<RosterFrame> {
        val frames = linkedMapOf<Long, MutableMap<Long, RosterIdentity>>()
        db.rawQuery(
            """
            SELECT s.id, s.captured_at, sm.uid, sm.name, sm.level
            FROM snapshots s
            JOIN snapshot_members sm ON sm.snapshot_id = s.id
            ORDER BY s.captured_at, s.id, sm.uid
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val snapshotId = cursor.getLong(0)
                val capturedAt = Instant.ofEpochMilli(cursor.getLong(1))
                frames.getOrPut(snapshotId) { linkedMapOf() }[cursor.getLong(2)] =
                    RosterIdentity(cursor.getString(3), cursor.getInt(4), capturedAt)
            }
        }
        return frames.map { (_, members) ->
            RosterFrame(members.values.first().capturedAt, members)
        }
    }

    private fun ensureHistoricalMembers(db: SQLiteDatabase, snapshots: List<RosterFrame>) {
        snapshots.flatMap { frame ->
            frame.members.map { (uid, identity) -> Triple(uid, identity, frame.capturedAt) }
        }.groupBy { it.first }.forEach { (uid, observations) ->
            val firstSeenAt = observations.minOf { it.third }
            val latest = observations.maxBy { it.third }
            if (!memberExists(db, uid)) {
                MembershipRows.insertMember(
                    db = db,
                    uid = uid,
                    name = latest.second.name,
                    level = latest.second.level.toLong(),
                    firstSeenAt = firstSeenAt,
                    lastSeenAt = latest.third,
                )
            } else {
                db.execSQL(
                    "UPDATE members SET first_seen_at = MIN(first_seen_at, ?) WHERE uid = ?",
                    arrayOf(firstSeenAt.toEpochMilli(), uid),
                )
            }
        }
    }

    private fun removeWeakSnapshotMembershipPeriods(db: SQLiteDatabase) {
        val sourceNames = SNAPSHOT_EVENT_SOURCES.map(EvidenceSource::name)
        val weakIds = db.rawQuery(
            """
            SELECT id
            FROM membership_periods
            WHERE joined_source IN (?, ?)
              AND (left_source IS NULL OR left_source IN (?, ?))
              AND joined_precision IN (?, ?)
              AND (left_precision IS NULL OR left_precision = ?)
              AND note = ''
            """.trimIndent(),
            arrayOf(
                sourceNames[0],
                sourceNames[1],
                sourceNames[0],
                sourceNames[1],
                EvidencePrecision.UNKNOWN.name,
                EvidencePrecision.INFERRED.name,
                EvidencePrecision.INFERRED.name,
            ),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }

        weakIds.forEach { membershipPeriodId ->
            MembershipRows.deleteMembershipPeriod(db, membershipPeriodId)
        }
    }

    private fun buildRosterPresenceRuns(
        snapshots: List<RosterFrame>,
        uid: Long,
    ): List<RosterPresenceRun> {
        val runs = mutableListOf<RosterPresenceRun>()
        var index = 0
        while (index < snapshots.size) {
            if (uid !in snapshots[index].members) {
                index += 1
                continue
            }
            val firstIndex = index
            while (index + 1 < snapshots.size && uid in snapshots[index + 1].members) index += 1
            val lastIndex = index
            val first = snapshots[firstIndex]
            val last = snapshots[lastIndex]
            runs += RosterPresenceRun(
                firstSeenAt = first.capturedAt,
                lastSeenAt = last.capturedAt,
                joinedAt = first.capturedAt.takeIf { firstIndex > 0 },
                leftAt = snapshots.getOrNull(lastIndex + 1)?.capturedAt,
                name = first.members.getValue(uid).name,
            )
            index += 1
        }
        return runs
    }

    /**
     * Preserves exact, manual, or user-annotated periods overlapping one roster-presence run.
     * Every overlapping open period closes at an observed absence, so a later run becomes a
     * separate inferred rejoin. Returns false when no preserved period covers the run.
     */
    private fun reconcilePreservedMembershipPeriodOverlap(
        db: SQLiteDatabase,
        uid: Long,
        run: RosterPresenceRun,
    ): Boolean {
        val overlaps = db.rawQuery(
            """
            SELECT id, joined_at, joined_precision, joined_source,
                   left_at, left_precision, left_source
            FROM membership_periods
            WHERE uid = ?
              AND (joined_at IS NULL OR joined_at <= ?)
              AND (left_at IS NULL OR left_at > ?)
            ORDER BY COALESCE(joined_at, -9223372036854775808), id
            """.trimIndent(),
            arrayOf(
                uid.toString(),
                run.lastSeenAt.toEpochMilli().toString(),
                run.firstSeenAt.toEpochMilli().toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PreservedMembershipPeriodOverlap(
                            id = cursor.getLong(0),
                            joinedAt = cursor.getNullableInstant(1),
                            joinedPrecision = EvidencePrecision.valueOf(cursor.getString(2)),
                            joinedSource = EvidenceSource.valueOf(cursor.getString(3)),
                            leftAt = cursor.getNullableInstant(4),
                            leftPrecision = cursor.getNullableString(5)
                                ?.let(EvidencePrecision::valueOf),
                            leftSource = cursor.getNullableString(6)
                                ?.let(EvidenceSource::valueOf),
                        ),
                    )
                }
            }
        }
        overlaps.forEach { membershipPeriod ->
            expandWeakJoinToRun(db, uid, membershipPeriod, run)
            reconcileRunAbsence(db, uid, membershipPeriod, run)
        }
        return overlaps.isNotEmpty()
    }

    /** Expands a weak inferred join to the earliest roster presence discovered later. */
    private fun expandWeakJoinToRun(
        db: SQLiteDatabase,
        uid: Long,
        membershipPeriod: PreservedMembershipPeriodOverlap,
        run: RosterPresenceRun,
    ) {
        val joinedAt = membershipPeriod.joinedAt
        if (
            joinedAt != null &&
            run.firstSeenAt.isBefore(joinedAt) &&
            isWeakSnapshotBoundary(
                membershipPeriod.joinedPrecision,
                membershipPeriod.joinedSource,
            )
        ) {
            val updated = db.update(
                "membership_periods",
                ContentValues().apply {
                    put("joined_at", run.firstSeenAt.toEpochMilli())
                    putNull("joined_date")
                    put("joined_time_known", 1)
                    put("joined_precision", EvidencePrecision.INFERRED.name)
                    put("joined_source", EvidenceSource.LEGACY_IMPORT.name)
                },
                "id = ?",
                arrayOf(membershipPeriod.id.toString()),
            )
            if (updated == 1) {
                updateSnapshotBoundaryEvent(
                    db = db,
                    membershipPeriodId = membershipPeriod.id,
                    uid = uid,
                    memberName = run.name,
                    type = joinEventType(db, uid, membershipPeriod.id),
                    observedAt = run.firstSeenAt,
                )
            }
        }
    }

    /** Closes a preserved period at an observed roster absence, or reopens a weak closure. */
    private fun reconcileRunAbsence(
        db: SQLiteDatabase,
        uid: Long,
        membershipPeriod: PreservedMembershipPeriodOverlap,
        run: RosterPresenceRun,
    ) {
        val observedLeftAt = run.leftAt
        val weakLeftBoundary = membershipPeriod.leftPrecision != null &&
            membershipPeriod.leftSource != null &&
            isWeakSnapshotBoundary(
                membershipPeriod.leftPrecision,
                membershipPeriod.leftSource,
            )
        if (
            observedLeftAt != null &&
            (
                membershipPeriod.leftAt == null ||
                    (weakLeftBoundary && membershipPeriod.leftAt != observedLeftAt)
                )
        ) {
            val updated = db.update(
                "membership_periods",
                ContentValues().apply {
                    put("left_at", observedLeftAt.toEpochMilli())
                    putNull("left_date")
                    put("left_time_known", 1)
                    put("left_precision", EvidencePrecision.INFERRED.name)
                    put("left_source", EvidenceSource.LEGACY_IMPORT.name)
                },
                "id = ?",
                arrayOf(membershipPeriod.id.toString()),
            )
            if (updated == 1) {
                updateSnapshotBoundaryEvent(
                    db = db,
                    membershipPeriodId = membershipPeriod.id,
                    uid = uid,
                    memberName = run.name,
                    type = MemberEventType.LEFT,
                    observedAt = observedLeftAt,
                )
            }
        } else if (
            observedLeftAt == null &&
            membershipPeriod.leftAt != null &&
            weakLeftBoundary &&
            !run.lastSeenAt.isBefore(membershipPeriod.leftAt)
        ) {
            db.update(
                "membership_periods",
                ContentValues().apply {
                    putNull("left_at")
                    putNull("left_date")
                    putNull("left_time_known")
                    putNull("left_precision")
                    putNull("left_source")
                },
                "id = ?",
                arrayOf(membershipPeriod.id.toString()),
            )
            deleteSnapshotBoundaryEvents(
                db = db,
                membershipPeriodId = membershipPeriod.id,
                type = MemberEventType.LEFT,
            )
        }
    }

    private fun isWeakSnapshotBoundary(
        precision: EvidencePrecision,
        source: EvidenceSource,
    ): Boolean = precision in setOf(EvidencePrecision.UNKNOWN, EvidencePrecision.INFERRED) &&
        source in SNAPSHOT_EVENT_SOURCES

    private fun updateSnapshotBoundaryEvent(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        uid: Long,
        memberName: String,
        type: MemberEventType,
        observedAt: Instant,
    ) {
        val compatibleTypes = MembershipRows.compatibleEventTypes(type)
        val updated = db.update(
            "member_events",
            ContentValues().apply {
                put("event_type", type.name)
                putNull("occurred_at")
                putNull("event_date")
                put("time_known", 1)
                put("observed_at", observedAt.toEpochMilli())
                put("precision", EvidencePrecision.INFERRED.name)
                put("source", EvidenceSource.LEGACY_IMPORT.name)
                put("note", memberName)
            },
            "membership_period_id = ? AND event_type IN (?, ?) " +
                "AND source IN (?, ?)",
            arrayOf(
                membershipPeriodId.toString(),
                compatibleTypes[0],
                compatibleTypes[1],
                EvidenceSource.SNAPSHOT.name,
                EvidenceSource.LEGACY_IMPORT.name,
            ),
        )
        if (updated == 0) {
            insertSnapshotBoundaryEvent(
                db = db,
                membershipPeriodId = membershipPeriodId,
                uid = uid,
                memberName = memberName,
                type = type,
                observedAt = observedAt,
                source = EvidenceSource.LEGACY_IMPORT,
            )
        }
    }

    private fun deleteSnapshotBoundaryEvents(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        type: MemberEventType,
    ) {
        MembershipRows.deleteEvents(
            db,
            "membership_period_id = ? AND event_type = ? AND source IN (?, ?)",
            arrayOf(
                membershipPeriodId.toString(),
                type.name,
                EvidenceSource.SNAPSHOT.name,
                EvidenceSource.LEGACY_IMPORT.name,
            ),
        )
    }

    private fun insertRosterPresenceRun(
        db: SQLiteDatabase,
        uid: Long,
        run: RosterPresenceRun,
    ) {
        val membershipPeriodId = db.insertOrThrow(
            "membership_periods",
            null,
            ContentValues().apply {
                put("uid", uid)
                putNullableLong("joined_at", run.joinedAt?.toEpochMilli())
                putNullableLong("left_at", run.leftAt?.toEpochMilli())
                put("joined_time_known", 1)
                putNullableInt("left_time_known", run.leftAt?.let { 1 })
                put(
                    "joined_precision",
                    if (run.joinedAt == null) EvidencePrecision.UNKNOWN.name else EvidencePrecision.INFERRED.name,
                )
                put(
                    "left_precision",
                    run.leftAt?.let { EvidencePrecision.INFERRED.name },
                )
                put("joined_source", EvidenceSource.LEGACY_IMPORT.name)
                put("left_source", run.leftAt?.let { EvidenceSource.LEGACY_IMPORT.name })
            },
        )
        if (run.joinedAt != null) {
            insertSnapshotBoundaryEvent(
                db,
                membershipPeriodId,
                uid,
                run.name,
                joinEventType(db, uid, membershipPeriodId),
                run.joinedAt,
                EvidenceSource.LEGACY_IMPORT,
            )
        }
        if (run.leftAt != null) {
            insertSnapshotBoundaryEvent(
                db,
                membershipPeriodId,
                uid,
                run.name,
                MemberEventType.LEFT,
                run.leftAt,
                EvidenceSource.LEGACY_IMPORT,
            )
        }
    }

    private data class RosterIdentity(
        val name: String,
        val level: Int,
        val capturedAt: Instant,
    )

    private data class RosterFrame(
        val capturedAt: Instant,
        val members: Map<Long, RosterIdentity>,
    )

    private data class PreservedMembershipPeriodOverlap(
        val id: Long,
        val joinedAt: Instant?,
        val joinedPrecision: EvidencePrecision,
        val joinedSource: EvidenceSource,
        val leftAt: Instant?,
        val leftPrecision: EvidencePrecision?,
        val leftSource: EvidenceSource?,
    )

    private data class RosterPresenceRun(
        val firstSeenAt: Instant,
        val lastSeenAt: Instant,
        val joinedAt: Instant?,
        val leftAt: Instant?,
        val name: String,
    )
}
