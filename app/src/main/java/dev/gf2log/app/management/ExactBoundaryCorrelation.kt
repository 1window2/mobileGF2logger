package dev.gf2log.app.management

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.management.MembershipRows.ensureRosterActiveMembershipPeriod
import dev.gf2log.app.management.MembershipRows.insertEvent
import dev.gf2log.app.management.MembershipRows.insertMembershipPeriod
import dev.gf2log.app.management.MembershipRows.joinEventType
import dev.gf2log.app.management.MembershipRows.memberExists
import dev.gf2log.app.management.PlatoonDatabase.Companion.DAILY_PATROL_REWARD_ACTION_ID
import java.time.Instant

/** Applies UID-safe 21960 Updates boundaries and folds weaker roster or manual evidence into them. */
internal object ExactBoundaryCorrelation {
    fun ingest(
        db: SQLiteDatabase,
        observations: List<PlatoonUpdateObservation>,
        capturedAt: Instant,
    ): UpdatesIngestResult {
        val acceptedObservations = PlatoonObservationPolicy.updates(observations)
        if (acceptedObservations.isEmpty()) return UpdatesIngestResult(0, 0, 0)
        return db.inTransaction {
            var membershipEvents = 0
            var patrolFacts = 0
            acceptedObservations.forEach { observation ->
                observation.members.forEach {
                    ensureUpdateMember(db, it, observation.occurredAt)
                }
                val affectedMembers = PlatoonUpdateSemantics.affectedMembers(
                    kind = observation.kind,
                    members = observation.members,
                )
                val effect = PlatoonUpdateSemantics.effect(observation.kind)
                val exactBoundary = EXACT_BOUNDARIES[effect]
                if (exactBoundary != null) {
                    val (type, boundary) = exactBoundary
                    affectedMembers.forEach { member ->
                        if (applyExactUpdateBoundary(
                                db = db,
                                member = member,
                                type = type,
                                boundary = boundary,
                                occurredAt = observation.occurredAt,
                                observedAt = capturedAt,
                            )
                        ) {
                            membershipEvents += 1
                        }
                        // The exact boundary supersedes a weak open period for the same change.
                        MembershipRows.foldDuplicateOpenPeriods(db, member.uid)
                        synchronizeCurrentMembershipFromUpdate(db, member.uid)
                    }
                } else if (effect == PlatoonUpdateEffect.DAILY_PATROL) {
                    affectedMembers.forEach { member ->
                        val inserted = db.insertWithOnConflict(
                            "platoon_activity",
                            null,
                            ContentValues().apply {
                                put("occurred_at", observation.occurredAt.toEpochMilli())
                                put("action_id", DAILY_PATROL_REWARD_ACTION_ID)
                                put("kind", observation.kind)
                                put("member_name", member.name)
                                put("captured_at", capturedAt.toEpochMilli())
                                put("resolved_uid", member.uid)
                                put("resolution", ActivityResolution.EXACT_UPDATE.name)
                            },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                        if (inserted != -1L) patrolFacts += 1
                    }
                }
            }
            ActivityIngestion.trimPlatoonActivity(db)
            UpdatesIngestResult(acceptedObservations.size, membershipEvents, patrolFacts)
        }
    }

    private fun synchronizeCurrentMembershipFromUpdate(
        db: SQLiteDatabase,
        uid: Long,
    ) {
        MembershipRows.synchronizeActiveState(db, listOf(uid))
        val latestSnapshotAt = SnapshotIngestion.latestSnapshotCapturedAt(db)
        val snapshotFilter = if (latestSnapshotAt == null) "" else "AND occurred_at > ?"
        val arguments = mutableListOf(
            uid.toString(),
            EvidenceSource.GAME_UPDATES.name,
            EvidencePrecision.EXACT.name,
            MemberEventType.JOINED.name,
            MemberEventType.REJOINED.name,
            MemberEventType.LEFT.name,
            MemberEventType.REMOVED.name,
        ).apply {
            latestSnapshotAt?.let { add(it.toEpochMilli().toString()) }
        }
        val latestBoundary = db.rawQuery(
            """
            SELECT note, occurred_at
            FROM member_events
            WHERE uid = ?
              AND source = ?
              AND precision = ?
              AND event_type IN (?, ?, ?, ?)
              AND occurred_at IS NOT NULL
              $snapshotFilter
            ORDER BY occurred_at DESC,
                     CASE event_type
                       WHEN '${MemberEventType.REMOVED.name}' THEN 0
                       WHEN '${MemberEventType.LEFT.name}' THEN 1
                       WHEN '${MemberEventType.REJOINED.name}' THEN 2
                       ELSE 3
                     END,
                     id DESC
            LIMIT 1
            """.trimIndent(),
            arguments.toTypedArray(),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            LatestMembershipBoundary(
                name = cursor.getString(0),
                occurredAt = Instant.ofEpochMilli(cursor.getLong(1)),
            )
        }
        db.update(
            "members",
            ContentValues().apply {
                put("current_name", latestBoundary.name)
                put("last_seen_at", latestBoundary.occurredAt.toEpochMilli())
            },
            "uid = ?",
            arrayOf(uid.toString()),
        )
    }

    private data class LatestMembershipBoundary(
        val name: String,
        val occurredAt: Instant,
    )

    private fun ensureUpdateMember(
        db: SQLiteDatabase,
        member: PlatoonUpdateMemberObservation,
        observedAt: Instant,
    ) {
        if (memberExists(db, member.uid)) return
        MembershipRows.insertMember(
            db = db,
            uid = member.uid,
            name = member.name,
            level = 0,
            firstSeenAt = observedAt,
            lastSeenAt = observedAt,
        )
    }

    fun findRosterConfirmedExactMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        boundary: MembershipBoundary,
        from: Instant?,
        observedAt: Instant,
    ): Long? {
        val boundaryColumn = boundary.atColumn
        val sourceColumn = boundary.sourceColumn
        val precisionColumn = boundary.precisionColumn
        val compatibility = if (boundary == MembershipBoundary.JOIN) {
            "(left_at IS NULL OR left_at >= ?)"
        } else {
            "(joined_at IS NULL OR joined_at <= ?)"
        }
        val fromClause = if (from == null) "" else "AND $boundaryColumn > ?"
        val arguments = buildList {
            add(uid.toString())
            add(EvidenceSource.GAME_UPDATES.name)
            add(EvidencePrecision.EXACT.name)
            add(observedAt.toEpochMilli().toString())
            if (from != null) add(from.toEpochMilli().toString())
            add(observedAt.toEpochMilli().toString())
        }.toTypedArray()
        val candidates = db.rawQuery(
            """
            SELECT id, $boundaryColumn
            FROM membership_periods
            WHERE uid = ?
              AND $sourceColumn = ?
              AND $precisionColumn = ?
              AND $boundaryColumn <= ?
              $fromClause
              AND $compatibility
            ORDER BY $boundaryColumn DESC, id DESC
            """.trimIndent(),
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(0) to Instant.ofEpochMilli(cursor.getLong(1)))
                }
            }
        }
        return candidates.firstOrNull { (_, boundaryAt) ->
            !hasLaterOppositeBoundaryThrough(
                db = db,
                uid = uid,
                boundary = boundary,
                boundaryAt = boundaryAt,
                observedAt = observedAt,
            )
        }?.first
    }

    private fun applyExactUpdateBoundary(
        db: SQLiteDatabase,
        member: PlatoonUpdateMemberObservation,
        type: MemberEventType,
        boundary: MembershipBoundary,
        occurredAt: Instant,
        observedAt: Instant,
    ): Boolean {
        val boundaryTypes = boundary.eventTypes
        val exactExists = db.rawQuery(
            """
            SELECT 1
            FROM member_events
            WHERE uid = ?
              AND source = ?
              AND occurred_at = ?
              AND event_type IN (?, ?)
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                member.uid.toString(),
                EvidenceSource.GAME_UPDATES.name,
                occurredAt.toEpochMilli().toString(),
                boundaryTypes[0].name,
                boundaryTypes[1].name,
            ),
        ).use(Cursor::moveToFirst)
        if (exactExists) return false

        val candidates = db.rawQuery(
            """
            SELECT id, membership_period_id, COALESCE(occurred_at, observed_at),
                   ABS(COALESCE(occurred_at, observed_at) - ?) AS distance
            FROM member_events
            WHERE uid = ?
              AND event_type IN (?, ?)
              AND source != ?
              AND ABS(COALESCE(occurred_at, observed_at) - ?) <= CAST(? AS INTEGER)
            ORDER BY distance,
                     CASE source WHEN '${EvidenceSource.MANUAL.name}' THEN 0 ELSE 1 END,
                     id DESC
            """.trimIndent(),
            arrayOf(
                occurredAt.toEpochMilli().toString(),
                member.uid.toString(),
                boundaryTypes[0].name,
                boundaryTypes[1].name,
                EvidenceSource.GAME_UPDATES.name,
                occurredAt.toEpochMilli().toString(),
                EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        BoundaryEventCandidate(
                            id = cursor.getLong(0),
                            membershipPeriodId = cursor.getNullableLong(1),
                            boundaryAt = Instant.ofEpochMilli(cursor.getLong(2)),
                        ),
                    )
                }
            }
        }
        val correlatableCandidates = candidates.filter { candidate ->
            !hasOppositeBoundaryBetween(
                db = db,
                uid = member.uid,
                boundary = boundary,
                first = candidate.boundaryAt,
                second = occurredAt,
            )
        }
        val membershipPeriodId = correlatableCandidates
            .firstNotNullOfOrNull(BoundaryEventCandidate::membershipPeriodId)
            ?: findOrCreateUpdateMembershipPeriod(db, member.uid, boundary, occurredAt)

        correlatableCandidates.forEach { candidate ->
            MembershipRows.deleteSupersededEvents(db, "id = ?", arrayOf(candidate.id.toString()))
        }
        correlatableCandidates.mapNotNull(BoundaryEventCandidate::membershipPeriodId)
            .filter { it != membershipPeriodId }
            .distinct()
            .forEach { candidateMembershipPeriodId ->
                mergeShadowInferredMembershipPeriod(
                    db = db,
                    selectedMembershipPeriodId = membershipPeriodId,
                    shadowMembershipPeriodId = candidateMembershipPeriodId,
                    boundary = boundary,
                    occurredAt = occurredAt,
                )
            }

        applyExactMembershipBoundary(
            db = db,
            membershipPeriodId = membershipPeriodId,
            uid = member.uid,
            memberName = member.name,
            type = if (boundary == MembershipBoundary.JOIN) {
                joinEventType(db, member.uid, membershipPeriodId)
            } else {
                type
            },
            boundary = boundary,
            occurredAt = occurredAt,
            observedAt = observedAt,
            source = EvidenceSource.GAME_UPDATES,
            activityIds = emptyList(),
        )
        if (boundary == MembershipBoundary.WITHDRAW &&
            MembershipChronology.shouldRestoreRosterActiveMembershipPeriod(
                memberIsActive = isActiveMember(db, member.uid),
                withdrawalPredatesRoster = hasRosterPresenceAfter(db, member.uid, occurredAt),
            )
        ) {
            /*
             * The historical boundary and a later authoritative roster are
             * both true. Close the compatible historical membership period above, then
             * preserve exactly one unknown current period for the later roster.
             * A subsequent exact rejoin in the replay will refine this period.
             */
            ensureRosterActiveMembershipPeriod(db, member.uid, EvidenceSource.SNAPSHOT)
        }
        return true
    }

    private fun hasOppositeBoundaryBetween(
        db: SQLiteDatabase,
        uid: Long,
        boundary: MembershipBoundary,
        first: Instant,
        second: Instant,
    ): Boolean {
        if (first == second) return false
        val oppositeTypes = boundary.opposite.eventTypes
        val start = minOf(first, second).toEpochMilli()
        val end = maxOf(first, second).toEpochMilli()
        return db.rawQuery(
            """
            SELECT 1
            FROM member_events
            WHERE uid = ?
              AND event_type IN (?, ?)
              AND COALESCE(occurred_at, observed_at) > CAST(? AS INTEGER)
              AND COALESCE(occurred_at, observed_at) < CAST(? AS INTEGER)
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                uid.toString(),
                oppositeTypes[0].name,
                oppositeTypes[1].name,
                start.toString(),
                end.toString(),
            ),
        ).use(Cursor::moveToFirst)
    }

    private fun hasLaterOppositeBoundaryThrough(
        db: SQLiteDatabase,
        uid: Long,
        boundary: MembershipBoundary,
        boundaryAt: Instant,
        observedAt: Instant,
    ): Boolean {
        if (!observedAt.isAfter(boundaryAt)) return false
        val oppositeTypes = boundary.opposite.eventTypes
        val oppositeBoundaryAt = db.rawQuery(
            """
            SELECT MIN(COALESCE(occurred_at, observed_at))
            FROM member_events
            WHERE uid = ?
              AND event_type IN (?, ?)
              AND COALESCE(occurred_at, observed_at) > CAST(? AS INTEGER)
              AND COALESCE(occurred_at, observed_at) <= CAST(? AS INTEGER)
            """.trimIndent(),
            arrayOf(
                uid.toString(),
                oppositeTypes[0].name,
                oppositeTypes[1].name,
                boundaryAt.toEpochMilli().toString(),
                observedAt.toEpochMilli().toString(),
            ),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getNullableInstant(0) else null
        }
        return MembershipChronology.hasSupersedingOppositeBoundary(
            boundaryAt = boundaryAt,
            observedAt = observedAt,
            oppositeBoundaryTimes = listOfNotNull(oppositeBoundaryAt),
        )
    }

    private fun findOrCreateUpdateMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        boundary: MembershipBoundary,
        occurredAt: Instant,
    ): Long {
        val occurredAtMillis = occurredAt.toEpochMilli()
        val nearby = when (boundary) {
            MembershipBoundary.JOIN -> db.rawQuery(
                """
                SELECT id
                FROM membership_periods
                WHERE uid = ?
                  AND (left_at IS NULL OR left_at >= ?)
                  AND (
                    joined_at IS NULL
                    OR (
                      (joined_precision != ? OR joined_source = ?)
                      AND ABS(joined_at - ?) <= CAST(? AS INTEGER)
                    )
                    OR ${weakBoundaryInSameRosterGap(MembershipBoundary.JOIN)}
                  )
                ORDER BY
                  CASE WHEN left_at IS NULL THEN 0 ELSE 1 END,
                  CASE WHEN joined_at IS NULL THEN 0 ELSE 1 END,
                  ABS(COALESCE(joined_at, ?) - ?),
                  id DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    uid.toString(),
                    occurredAtMillis.toString(),
                    EvidencePrecision.EXACT.name,
                    EvidenceSource.ACTIVITY.name,
                    occurredAtMillis.toString(),
                    EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
                    *weakBoundaryInSameRosterGapArguments(occurredAtMillis),
                    occurredAtMillis.toString(),
                    occurredAtMillis.toString(),
                ),
            )
            MembershipBoundary.WITHDRAW -> db.rawQuery(
                """
                SELECT id
                FROM membership_periods
                WHERE uid = ?
                  AND (joined_at IS NULL OR joined_at <= ?)
                  AND (
                    left_at IS NULL
                    OR (
                      (left_precision != ? OR left_source = ?)
                      AND ABS(left_at - ?) <= CAST(? AS INTEGER)
                    )
                    OR ${weakBoundaryInSameRosterGap(MembershipBoundary.WITHDRAW)}
                  )
                ORDER BY
                  CASE WHEN left_at IS NULL THEN 0 ELSE 1 END,
                  CASE WHEN joined_at IS NULL THEN 1 ELSE 0 END,
                  joined_at DESC,
                  ABS(COALESCE(left_at, ?) - ?),
                  id DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    uid.toString(),
                    occurredAtMillis.toString(),
                    EvidencePrecision.EXACT.name,
                    EvidenceSource.ACTIVITY.name,
                    occurredAtMillis.toString(),
                    EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
                    *weakBoundaryInSameRosterGapArguments(occurredAtMillis),
                    occurredAtMillis.toString(),
                    occurredAtMillis.toString(),
                ),
            )
        }.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (nearby != null) return nearby
        return insertMembershipPeriod(
            db = db,
            uid = uid,
            joinedAt = if (boundary == MembershipBoundary.JOIN) occurredAt else null,
            precision = if (boundary == MembershipBoundary.JOIN) {
                EvidencePrecision.EXACT
            } else {
                EvidencePrecision.UNKNOWN
            },
            source = EvidenceSource.GAME_UPDATES,
        )
    }

    /**
     * A roster-inferred or activity-dated [boundary] that records the same change as an Updates
     * boundary at the bound instant, however late the Updates feed arrives: no roster was
     * captured between the two instants, so the first roster after the real change revealed it.
     * An inferred boundary sits on that revealing roster, so it cannot precede the change. Bind
     * [weakBoundaryInSameRosterGapArguments] in place; the instant arrives as text, and MIN and
     * MAX compare an integer below any text, so it is cast before either sees it.
     */
    private fun weakBoundaryInSameRosterGap(boundary: MembershipBoundary): String {
        val at = boundary.atColumn
        return """
            (
              $at IS NOT NULL
              AND (
                (${boundary.sourceColumn} IN (?, ?) AND ${boundary.precisionColumn} = ? AND $at >= ?)
                OR ${boundary.sourceColumn} = ?
              )
              AND NOT EXISTS (
                SELECT 1 FROM snapshots roster
                WHERE roster.captured_at >= MIN($at, CAST(? AS INTEGER))
                  AND roster.captured_at < MAX($at, CAST(? AS INTEGER))
                  AND roster.clock_suspect = 0
              )
            )
        """.trimIndent()
    }

    private fun weakBoundaryInSameRosterGapArguments(occurredAtMillis: Long): Array<String> = arrayOf(
        EvidenceSource.SNAPSHOT.name,
        EvidenceSource.LEGACY_IMPORT.name,
        EvidencePrecision.INFERRED.name,
        occurredAtMillis.toString(),
        EvidenceSource.ACTIVITY.name,
        occurredAtMillis.toString(),
        occurredAtMillis.toString(),
    )

    private fun isActiveMember(db: SQLiteDatabase, uid: Long): Boolean =
        db.rawQuery(
            "SELECT is_active FROM members WHERE uid = ? LIMIT 1",
            arrayOf(uid.toString()),
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }

    private fun hasRosterPresenceAfter(
        db: SQLiteDatabase,
        uid: Long,
        occurredAt: Instant,
    ): Boolean {
        val latestRosterPresenceAt = db.rawQuery(
            """
            SELECT MAX(snapshot.captured_at)
            FROM snapshots snapshot
            JOIN snapshot_members member ON member.snapshot_id = snapshot.id
            WHERE member.uid = ? AND snapshot.clock_suspect = 0
            """.trimIndent(),
            arrayOf(uid.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getNullableInstant(0) else null
        }
        return MembershipChronology.withdrawalPredatesRosterPresence(
            withdrewAt = occurredAt,
            latestRosterPresenceAt = latestRosterPresenceAt,
        )
    }

    /**
     * Folds a weaker inferred period that recorded the same boundary into the selected period.
     * Its opposite boundary moves over only when the selected period has none of its own.
     */
    private fun mergeShadowInferredMembershipPeriod(
        db: SQLiteDatabase,
        selectedMembershipPeriodId: Long,
        shadowMembershipPeriodId: Long,
        boundary: MembershipBoundary,
        occurredAt: Instant,
    ) {
        val opposite = boundary.opposite.prefix
        val shadowOpposite = db.rawQuery(
            """
            SELECT ${opposite}_at, ${opposite}_date, ${opposite}_time_known,
                   ${opposite}_precision, ${opposite}_source
            FROM membership_periods
            WHERE id = ?
              AND (${boundary.precisionColumn} IN (?, ?) OR ${boundary.sourceColumn} = ?)
              AND (
                ${boundary.atColumn} IS NULL
                OR ABS(${boundary.atColumn} - ?) <= CAST(? AS INTEGER)
              )
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                shadowMembershipPeriodId.toString(),
                EvidencePrecision.INFERRED.name,
                EvidencePrecision.UNKNOWN.name,
                EvidenceSource.ACTIVITY.name,
                occurredAt.toEpochMilli().toString(),
                EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ShadowBoundary(
                    at = cursor.getNullableLong(0),
                    date = cursor.getNullableLong(1),
                    timeKnown = cursor.getNullableInt(2),
                    precision = cursor.getNullableString(3),
                    source = cursor.getNullableString(4),
                )
            }
        } ?: return

        if (shadowOpposite.at != null) {
            val selectedHasOpposite = db.rawQuery(
                "SELECT ${opposite}_at FROM membership_periods WHERE id = ?",
                arrayOf(selectedMembershipPeriodId.toString()),
            ).use { cursor -> cursor.moveToFirst() && !cursor.isNull(0) }
            if (selectedHasOpposite) return

            db.update(
                "membership_periods",
                ContentValues().apply {
                    put("${opposite}_at", shadowOpposite.at)
                    putNullableLong("${opposite}_date", shadowOpposite.date)
                    putNullableInt("${opposite}_time_known", shadowOpposite.timeKnown)
                    put("${opposite}_precision", shadowOpposite.precision)
                    put("${opposite}_source", shadowOpposite.source)
                },
                "id = ?",
                arrayOf(selectedMembershipPeriodId.toString()),
            )
            val oppositeTypes = boundary.opposite.eventTypes
            db.update(
                "member_events",
                ContentValues().apply { put("membership_period_id", selectedMembershipPeriodId) },
                "membership_period_id = ? AND event_type IN (?, ?)",
                arrayOf(
                    shadowMembershipPeriodId.toString(),
                    oppositeTypes[0].name,
                    oppositeTypes[1].name,
                ),
            )
        }
        MembershipRows.deleteSupersededEvents(
            db,
            "membership_period_id = ?",
            arrayOf(shadowMembershipPeriodId.toString()),
        )
        db.delete("membership_periods", "id = ?", arrayOf(shadowMembershipPeriodId.toString()))
    }

    /**
     * Dates [boundary] of a period at the instant [occurredAt] and replaces the weaker roster or
     * activity event that recorded it. [source] is [EvidenceSource.GAME_UPDATES] only for a
     * 21960 boundary; an activity-dated roster boundary is [EvidenceSource.ACTIVITY], so it is
     * never presented as Updates evidence and a later Updates boundary still replaces it.
     */
    fun applyExactMembershipBoundary(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        uid: Long,
        memberName: String,
        type: MemberEventType,
        boundary: MembershipBoundary,
        occurredAt: Instant,
        observedAt: Instant,
        source: EvidenceSource,
        activityIds: List<Long>,
    ) {
        require(source == EvidenceSource.GAME_UPDATES || source == EvidenceSource.ACTIVITY)
        val values = ContentValues().apply {
            put(boundary.atColumn, occurredAt.toEpochMilli())
            putNull("${boundary.prefix}_date")
            put("${boundary.prefix}_time_known", 1)
            put(boundary.precisionColumn, EvidencePrecision.EXACT.name)
            put(boundary.sourceColumn, source.name)
        }
        db.update("membership_periods", values, "id = ?", arrayOf(membershipPeriodId.toString()))
        val inferredTypes = boundary.eventTypes.map(MemberEventType::name)
        MembershipRows.deleteSupersededEvents(
            db,
            "membership_period_id = ? AND source IN (?, ?, ?) AND event_type IN (?, ?)",
            arrayOf(
                membershipPeriodId.toString(),
                EvidenceSource.SNAPSHOT.name,
                EvidenceSource.LEGACY_IMPORT.name,
                EvidenceSource.ACTIVITY.name,
                inferredTypes[0],
                inferredTypes[1],
            ),
        )
        val eventId = insertEvent(
            db = db,
            uid = uid,
            type = type,
            occurredAt = occurredAt,
            observedAt = observedAt,
            precision = EvidencePrecision.EXACT,
            source = source,
            note = memberName,
            membershipPeriodId = membershipPeriodId,
        )
        activityIds.forEach { activityId ->
            db.update(
                "platoon_activity",
                ContentValues().apply {
                    put("resolved_uid", uid)
                    put("resolution", ActivityResolution.MEMBERSHIP_CORRELATION.name)
                    put("member_event_id", eventId)
                },
                "id = ? AND member_event_id IS NULL",
                arrayOf(activityId.toString()),
            )
        }
    }

    private data class BoundaryEventCandidate(
        val id: Long,
        val membershipPeriodId: Long?,
        val boundaryAt: Instant,
    )

    private data class ShadowBoundary(
        val at: Long?,
        val date: Long?,
        val timeKnown: Int?,
        val precision: String?,
        val source: String?,
    )

    private val EXACT_BOUNDARIES = mapOf(
        PlatoonUpdateEffect.JOIN to (MemberEventType.JOINED to MembershipBoundary.JOIN),
        PlatoonUpdateEffect.WITHDRAW to (MemberEventType.LEFT to MembershipBoundary.WITHDRAW),
        PlatoonUpdateEffect.REMOVED to (MemberEventType.REMOVED to MembershipBoundary.WITHDRAW),
    )

    private const val EXACT_UPDATE_CORRELATION_WINDOW_MILLIS = 48L * 60L * 60L * 1000L
}
