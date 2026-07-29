package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class PlatoonDatabase(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        PlatoonSchema.DATABASE_NAME,
        null,
        PlatoonSchema.CURRENT_VERSION,
    ) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at INTEGER NOT NULL,
                source_file TEXT UNIQUE,
                game_version TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX snapshots_captured_at ON snapshots(captured_at DESC)")
        db.execSQL(
            """
            CREATE TABLE snapshot_members (
                snapshot_id INTEGER NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
                uid INTEGER NOT NULL,
                name TEXT NOT NULL,
                level INTEGER NOT NULL,
                weekly_merit INTEGER NOT NULL,
                total_merit INTEGER NOT NULL,
                high_score INTEGER NOT NULL,
                total_score INTEGER NOT NULL,
                last_login INTEGER NOT NULL,
                PRIMARY KEY(snapshot_id, uid)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE members (
                uid INTEGER PRIMARY KEY,
                current_name TEXT NOT NULL,
                custom_name TEXT,
                current_level INTEGER NOT NULL,
                is_active INTEGER NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE tenures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid INTEGER NOT NULL REFERENCES members(uid),
                joined_at INTEGER,
                left_at INTEGER,
                joined_date INTEGER,
                left_date INTEGER,
                joined_time_known INTEGER NOT NULL DEFAULT 1,
                left_time_known INTEGER,
                joined_precision TEXT NOT NULL,
                left_precision TEXT,
                joined_source TEXT NOT NULL,
                left_source TEXT,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX tenures_uid ON tenures(uid, id DESC)")
        db.execSQL(
            """
            CREATE TABLE member_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid INTEGER NOT NULL REFERENCES members(uid),
                tenure_id INTEGER REFERENCES tenures(id),
                event_type TEXT NOT NULL,
                occurred_at INTEGER,
                event_date INTEGER,
                time_known INTEGER NOT NULL DEFAULT 1,
                observed_at INTEGER NOT NULL,
                precision TEXT NOT NULL,
                source TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX member_events_time ON member_events(observed_at DESC)")
        createPlatoonActivityTable(db)
        db.execSQL(
            """
            CREATE TABLE weekly_notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                period_start INTEGER NOT NULL,
                game_day INTEGER NOT NULL,
                text TEXT NOT NULL,
                event_id INTEGER REFERENCES member_events(id) ON DELETE SET NULL,
                is_automatic INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        createWeeklyOverridesTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val needsManualCalendarDateBackfill = oldVersion < 9
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE tenures ADD COLUMN joined_date INTEGER")
            db.execSQL("ALTER TABLE tenures ADD COLUMN left_date INTEGER")
            db.execSQL(
                "ALTER TABLE tenures ADD COLUMN joined_time_known INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL("ALTER TABLE tenures ADD COLUMN left_time_known INTEGER")
            db.execSQL("ALTER TABLE member_events ADD COLUMN event_date INTEGER")
            db.execSQL(
                "ALTER TABLE member_events ADD COLUMN time_known INTEGER NOT NULL DEFAULT 1",
            )
        }
        if (oldVersion < 2) {
            val correctedPeriods = mutableListOf<Pair<Long, Long>>()
            db.query(
                "weekly_notes",
                arrayOf("id", "game_day"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    correctedPeriods += cursor.getLong(0) to
                        PlatoonPeriods.weekStart(
                            java.time.LocalDate.ofEpochDay(cursor.getLong(1)),
                        ).toEpochDay()
                }
            }
            correctedPeriods.forEach { (id, periodStart) ->
                db.update(
                    "weekly_notes",
                    ContentValues().apply { put("period_start", periodStart) },
                    "id = ?",
                    arrayOf(id.toString()),
                )
            }
        }
        if (oldVersion < 3) createWeeklyOverridesTable(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE members ADD COLUMN custom_name TEXT")
        }
        if (oldVersion < 5) {
            val historicalNames = mutableListOf<Pair<Long, String>>()
            db.query(
                "weekly_notes",
                arrayOf("event_id", "text"),
                "is_automatic = 1 AND event_id IS NOT NULL",
                null,
                null,
                null,
                "id",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val parts = cursor.getString(1).split(':', limit = 3)
                    if (parts.size == 3 && parts[1].isNotBlank()) {
                        historicalNames += cursor.getLong(0) to parts[1]
                    }
                }
            }
            historicalNames.forEach { (eventId, name) ->
                db.update(
                    "member_events",
                    ContentValues().apply { put("note", name) },
                    "id = ? AND note = ''",
                    arrayOf(eventId.toString()),
                )
            }
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE member_events ADD COLUMN tenure_id INTEGER REFERENCES tenures(id)")
            createPlatoonActivityTable(db)
            db.delete("weekly_notes", "is_automatic = 1", null)
            backfillManualTenureEvents(db)
        }
        if (oldVersion < 7) {
            backfillSnapshotTenureEvents(db)
        }
        if (oldVersion in 6 until 8) {
            migratePlatoonActivityIdentity(db)
        }
        if (needsManualCalendarDateBackfill) {
            backfillManualCalendarDates(db, ZoneId.systemDefault())
        }
    }

    @Synchronized
    fun ingestSnapshot(
        snapshot: PlatoonSnapshot,
        source: EvidenceSource,
    ): SnapshotIngestResult {
        require(snapshot.members.isNotEmpty()) { "A Platoon snapshot cannot be empty" }
        require(snapshot.members.map(SnapshotMember::uid).distinct().size == snapshot.members.size) {
            "A Platoon snapshot cannot contain duplicate UIDs"
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            if (snapshot.sourceFile != null && sourceFileExists(db, snapshot.sourceFile)) {
                return SnapshotIngestResult.duplicate()
            }

            val hasPriorSnapshot = count(db, "snapshots") > 0
            val priorCapturedAt = latestSnapshotCapturedAt(db)
            val known = readKnownMembers(db)
            val changes = SnapshotReconciler.reconcile(
                known = known,
                incoming = snapshot.members,
                hasPriorSnapshot = hasPriorSnapshot,
            )
            val snapshotId = db.insertOrThrow(
                "snapshots",
                null,
                ContentValues().apply {
                    put("captured_at", snapshot.capturedAt.toEpochMilli())
                    put("source_file", snapshot.sourceFile)
                    put("game_version", snapshot.gameVersion)
                },
            )

            snapshot.members.forEach { member ->
                insertSnapshotMember(db, snapshotId, member)
                upsertMember(db, member, snapshot.capturedAt)
            }

            if (hasPriorSnapshot) {
                val changedNameCounts = (
                    changes.joined.map(SnapshotMember::name) +
                        changes.rejoined.map(SnapshotMember::name) +
                        changes.left.map(SnapshotReconciler.KnownMember::name)
                    ).groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
                val rosterNameUidCounts = (
                    known.map { it.uid to it.name } +
                        snapshot.members.map { it.uid to it.name }
                    ).groupBy { it.second.lowercase(Locale.ROOT) }
                    .mapValues { (_, identities) -> identities.map { it.first }.distinct().size }
                changes.joined.forEach { member ->
                    recordSnapshotJoin(
                        db = db,
                        member = member,
                        type = MemberEventType.JOINED,
                        observedAt = snapshot.capturedAt,
                        priorCapturedAt = priorCapturedAt,
                        source = source,
                        nameIsUnique = changedNameCounts[member.name.lowercase(Locale.ROOT)] == 1 &&
                            rosterNameUidCounts[member.name.lowercase(Locale.ROOT)] == 1,
                    )
                }
                changes.rejoined.forEach { member ->
                    recordSnapshotJoin(
                        db = db,
                        member = member,
                        type = MemberEventType.REJOINED,
                        observedAt = snapshot.capturedAt,
                        priorCapturedAt = priorCapturedAt,
                        source = source,
                        nameIsUnique = changedNameCounts[member.name.lowercase(Locale.ROOT)] == 1 &&
                            rosterNameUidCounts[member.name.lowercase(Locale.ROOT)] == 1,
                    )
                }
                changes.left.forEach { member ->
                    val exactTenureId = findRosterConfirmedExactTenure(
                        db = db,
                        uid = member.uid,
                        boundary = MembershipBoundary.WITHDRAW,
                        from = priorCapturedAt,
                        observedAt = snapshot.capturedAt,
                    )
                    val tenureId = exactTenureId ?: closeLatestTenure(
                        db = db,
                        uid = member.uid,
                        leftAt = snapshot.capturedAt,
                        precision = EvidencePrecision.INFERRED,
                        source = source,
                    )
                    markInactive(db, member.uid, snapshot.capturedAt)
                    if (exactTenureId == null) {
                        insertSnapshotBoundaryEvent(
                            db,
                            tenureId,
                            member.uid,
                            member.name,
                            MemberEventType.LEFT,
                            snapshot.capturedAt,
                            source,
                        )
                        correlateMembershipBoundary(
                            db = db,
                            tenureId = tenureId,
                            member = SnapshotMember(member.uid, member.name, 0, 0, 0, 0, 0, 0),
                            type = MemberEventType.LEFT,
                            boundary = MembershipBoundary.WITHDRAW,
                            observedAt = snapshot.capturedAt,
                            from = priorCapturedAt,
                            nameIsUnique =
                                changedNameCounts[member.name.lowercase(Locale.ROOT)] == 1 &&
                                    rosterNameUidCounts[
                                        member.name.lowercase(Locale.ROOT)
                                    ] == 1,
                        )
                    }
                }
                changes.renamed.forEach { rename ->
                    insertEvent(
                        db = db,
                        uid = rename.uid,
                        type = MemberEventType.RENAMED,
                        occurredAt = snapshot.capturedAt,
                        observedAt = snapshot.capturedAt,
                        precision = EvidencePrecision.INFERRED,
                        source = source,
                        note = "${rename.oldName} -> ${rename.newName}",
                    )
                }
            }
            // A roster is authoritative for current presence. Repair any missing open
            // tenure left by an incomplete historical Updates feed.
            snapshot.members.forEach { member ->
                ensureRosterActiveTenure(db, member.uid, source)
            }

            resolveUnresolvedActivityUids(db)
            db.setTransactionSuccessful()
            return SnapshotIngestResult(
                snapshotId = snapshotId,
                duplicate = false,
                initialRoster = changes.initialRoster,
                joined = changes.joined.size,
                rejoined = changes.rejoined.size,
                left = changes.left.size,
                renamed = changes.renamed.size,
            )
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun ingestPlatoonActivity(
        observations: List<PlatoonActivityObservation>,
        capturedAt: Instant,
    ): ActivityIngestResult {
        if (observations.isEmpty()) return ActivityIngestResult(0, 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            var inserted = 0
            var resolved = 0
            observations
                .filter { it.memberName.isNotBlank() }
                .distinctBy { listOf(it.occurredAt, it.actionId, it.kind, it.memberName) }
                .forEach { observation ->
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
            resolveUnresolvedActivityUids(db)
            reconcileInferredMembershipBoundaries(db)
            db.setTransactionSuccessful()
            return ActivityIngestResult(inserted, resolved)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun ingestPlatoonUpdates(
        observations: List<PlatoonUpdateObservation>,
        capturedAt: Instant,
    ): UpdatesIngestResult {
        if (observations.isEmpty()) return UpdatesIngestResult(0, 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            var membershipEvents = 0
            var patrolFacts = 0
            observations
                .filter { it.members.isNotEmpty() }
                .distinctBy { observation ->
                    listOf(
                        observation.kind,
                        observation.occurredAt,
                        observation.members.map { listOf(it.role, it.uid, it.name) },
                    )
                }
                .sortedBy(PlatoonUpdateObservation::occurredAt)
                .forEach { observation ->
                    observation.members.forEach {
                        ensureUpdateMember(db, it, observation.occurredAt)
                    }
                    val affectedMembers = PlatoonUpdateSemantics.affectedMembers(
                        kind = observation.kind,
                        members = observation.members,
                    )
                    when (PlatoonUpdateSemantics.effect(observation.kind)) {
                        PlatoonUpdateEffect.JOIN -> affectedMembers.forEach { member ->
                            if (applyExactUpdateBoundary(
                                    db = db,
                                    member = member,
                                    type = MemberEventType.JOINED,
                                    boundary = MembershipBoundary.JOIN,
                                    occurredAt = observation.occurredAt,
                                    observedAt = capturedAt,
                                )
                            ) {
                                membershipEvents += 1
                            }
                        }
                        PlatoonUpdateEffect.WITHDRAW -> affectedMembers.forEach { member ->
                            if (applyExactUpdateBoundary(
                                    db = db,
                                    member = member,
                                    type = MemberEventType.LEFT,
                                    boundary = MembershipBoundary.WITHDRAW,
                                    occurredAt = observation.occurredAt,
                                    observedAt = capturedAt,
                                )
                            ) {
                                membershipEvents += 1
                            }
                        }
                        PlatoonUpdateEffect.REMOVED -> affectedMembers.forEach { member ->
                            if (applyExactUpdateBoundary(
                                    db = db,
                                    member = member,
                                    type = MemberEventType.REMOVED,
                                    boundary = MembershipBoundary.WITHDRAW,
                                    occurredAt = observation.occurredAt,
                                    observedAt = capturedAt,
                                )
                            ) {
                                membershipEvents += 1
                            }
                        }
                        PlatoonUpdateEffect.DAILY_PATROL -> affectedMembers.forEach { member ->
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
                        PlatoonUpdateEffect.IGNORE -> Unit
                    }
                }
            db.setTransactionSuccessful()
            return UpdatesIngestResult(membershipEvents, patrolFacts)
        } finally {
            db.endTransaction()
        }
    }

    private fun ensureUpdateMember(
        db: SQLiteDatabase,
        member: PlatoonUpdateMemberObservation,
        observedAt: Instant,
    ) {
        if (memberExists(db, member.uid)) return
        db.insertOrThrow(
            "members",
            null,
            ContentValues().apply {
                put("uid", member.uid)
                put("current_name", member.name)
                put("current_level", 0)
                put("is_active", 0)
                put("first_seen_at", observedAt.toEpochMilli())
                put("last_seen_at", observedAt.toEpochMilli())
                put("note", "")
            },
        )
    }

    private fun recordSnapshotJoin(
        db: SQLiteDatabase,
        member: SnapshotMember,
        type: MemberEventType,
        observedAt: Instant,
        priorCapturedAt: Instant?,
        source: EvidenceSource,
        nameIsUnique: Boolean,
    ) {
        val exactTenureId = findRosterConfirmedExactTenure(
            db = db,
            uid = member.uid,
            boundary = MembershipBoundary.JOIN,
            from = priorCapturedAt,
            observedAt = observedAt,
        )
        if (exactTenureId != null) return

        val tenureId = insertTenure(
            db = db,
            uid = member.uid,
            joinedAt = observedAt,
            precision = EvidencePrecision.INFERRED,
            source = source,
        )
        insertSnapshotBoundaryEvent(
            db = db,
            tenureId = tenureId,
            uid = member.uid,
            memberName = member.name,
            type = type,
            observedAt = observedAt,
            source = source,
        )
        correlateMembershipBoundary(
            db = db,
            tenureId = tenureId,
            member = member,
            type = type,
            boundary = MembershipBoundary.JOIN,
            observedAt = observedAt,
            from = priorCapturedAt,
            nameIsUnique = nameIsUnique,
        )
    }

    private fun findRosterConfirmedExactTenure(
        db: SQLiteDatabase,
        uid: Long,
        boundary: MembershipBoundary,
        from: Instant?,
        observedAt: Instant,
    ): Long? {
        val boundaryColumn = if (boundary == MembershipBoundary.JOIN) "joined_at" else "left_at"
        val sourceColumn = if (boundary == MembershipBoundary.JOIN) {
            "joined_source"
        } else {
            "left_source"
        }
        val precisionColumn = if (boundary == MembershipBoundary.JOIN) {
            "joined_precision"
        } else {
            "left_precision"
        }
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
            FROM tenures
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
        val boundaryTypes = when (boundary) {
            MembershipBoundary.JOIN -> listOf(MemberEventType.JOINED, MemberEventType.REJOINED)
            MembershipBoundary.WITHDRAW -> listOf(MemberEventType.LEFT, MemberEventType.REMOVED)
        }
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
            SELECT id, tenure_id, COALESCE(occurred_at, observed_at),
                   ABS(COALESCE(occurred_at, observed_at) - ?) AS distance
            FROM member_events
            WHERE uid = ?
              AND event_type IN (?, ?)
              AND source != ?
              AND ABS(COALESCE(occurred_at, observed_at) - ?) <= ?
            ORDER BY distance,
                     CASE source
                       WHEN '${EvidenceSource.GAME_UPDATES.name}' THEN 0
                       WHEN '${EvidenceSource.MANUAL.name}' THEN 1
                       ELSE 2
                     END,
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
                            tenureId = cursor.getNullableLong(1),
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
        val tenureId = correlatableCandidates
            .firstNotNullOfOrNull(BoundaryEventCandidate::tenureId)
            ?: findOrCreateUpdateTenure(db, member.uid, boundary, occurredAt)

        correlatableCandidates.forEach { candidate ->
            db.delete("member_events", "id = ?", arrayOf(candidate.id.toString()))
        }
        correlatableCandidates.mapNotNull(BoundaryEventCandidate::tenureId)
            .filter { it != tenureId }
            .distinct()
            .forEach { candidateTenureId ->
                mergeShadowInferredTenure(
                    db = db,
                    selectedTenureId = tenureId,
                    shadowTenureId = candidateTenureId,
                    boundary = boundary,
                    occurredAt = occurredAt,
                )
            }

        applyExactMembershipBoundary(
            db = db,
            tenureId = tenureId,
            uid = member.uid,
            memberName = member.name,
            type = if (boundary == MembershipBoundary.JOIN &&
                hasEarlierTenure(db, member.uid, tenureId)
            ) {
                MemberEventType.REJOINED
            } else {
                type
            },
            boundary = boundary,
            occurredAt = occurredAt,
            observedAt = observedAt,
            activityIds = emptyList(),
        )
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
        val oppositeTypes = when (boundary) {
            MembershipBoundary.JOIN -> listOf(MemberEventType.LEFT, MemberEventType.REMOVED)
            MembershipBoundary.WITHDRAW -> listOf(
                MemberEventType.JOINED,
                MemberEventType.REJOINED,
            )
        }
        val start = minOf(first, second).toEpochMilli()
        val end = maxOf(first, second).toEpochMilli()
        return db.rawQuery(
            """
            SELECT 1
            FROM member_events
            WHERE uid = ?
              AND event_type IN (?, ?)
              AND COALESCE(occurred_at, observed_at) > ?
              AND COALESCE(occurred_at, observed_at) < ?
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
        val oppositeTypes = when (boundary) {
            MembershipBoundary.JOIN -> listOf(MemberEventType.LEFT, MemberEventType.REMOVED)
            MembershipBoundary.WITHDRAW -> listOf(
                MemberEventType.JOINED,
                MemberEventType.REJOINED,
            )
        }
        val oppositeBoundaryAt = db.rawQuery(
            """
            SELECT MIN(COALESCE(occurred_at, observed_at))
            FROM member_events
            WHERE uid = ?
              AND event_type IN (?, ?)
              AND COALESCE(occurred_at, observed_at) > ?
              AND COALESCE(occurred_at, observed_at) <= ?
            """.trimIndent(),
            arrayOf(
                uid.toString(),
                oppositeTypes[0].name,
                oppositeTypes[1].name,
                boundaryAt.toEpochMilli().toString(),
                observedAt.toEpochMilli().toString(),
            ),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getNullableLong(0)?.let(Instant::ofEpochMilli) else null
        }
        return MembershipChronology.hasSupersedingOppositeBoundary(
            boundaryAt = boundaryAt,
            observedAt = observedAt,
            oppositeBoundaryTimes = listOfNotNull(oppositeBoundaryAt),
        )
    }

    private fun findOrCreateUpdateTenure(
        db: SQLiteDatabase,
        uid: Long,
        boundary: MembershipBoundary,
        occurredAt: Instant,
    ): Long {
        val occurredAtMillis = occurredAt.toEpochMilli()
        val preserveLaterRosterTenure =
            boundary == MembershipBoundary.WITHDRAW &&
                hasRosterPresenceAfter(db, uid, occurredAt)
        val nearby = when (boundary) {
            MembershipBoundary.JOIN -> db.rawQuery(
                """
                SELECT id
                FROM tenures
                WHERE uid = ?
                  AND (left_at IS NULL OR left_at >= ?)
                  AND (
                    joined_at IS NULL
                    OR (
                      joined_precision != ?
                      AND ABS(joined_at - ?) <= ?
                    )
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
                    occurredAtMillis.toString(),
                    EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
                    occurredAtMillis.toString(),
                    occurredAtMillis.toString(),
                ),
            )
            MembershipBoundary.WITHDRAW -> db.rawQuery(
                """
                SELECT id
                FROM tenures
                WHERE uid = ?
                  AND (joined_at IS NULL OR joined_at <= ?)
                  AND (? = 0 OR left_at IS NOT NULL)
                  AND (
                    left_at IS NULL
                    OR (
                      left_precision != ?
                      AND ABS(left_at - ?) <= ?
                    )
                  )
                ORDER BY
                  CASE WHEN left_at IS NULL THEN 0 ELSE 1 END,
                  ABS(COALESCE(left_at, ?) - ?),
                  id DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    uid.toString(),
                    occurredAtMillis.toString(),
                    if (preserveLaterRosterTenure) "1" else "0",
                    EvidencePrecision.EXACT.name,
                    occurredAtMillis.toString(),
                    EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
                    occurredAtMillis.toString(),
                    occurredAtMillis.toString(),
                ),
            )
        }.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (nearby != null) return nearby
        return insertTenure(
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
            WHERE member.uid = ?
            """.trimIndent(),
            arrayOf(uid.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getNullableLong(0)?.let(Instant::ofEpochMilli) else null
        }
        return MembershipChronology.withdrawalPredatesRosterPresence(
            withdrewAt = occurredAt,
            latestRosterPresenceAt = latestRosterPresenceAt,
        )
    }

    private fun mergeShadowInferredTenure(
        db: SQLiteDatabase,
        selectedTenureId: Long,
        shadowTenureId: Long,
        boundary: MembershipBoundary,
        occurredAt: Instant,
    ) {
        val boundaryColumn = if (boundary == MembershipBoundary.JOIN) "joined_at" else "left_at"
        val precisionColumn = if (boundary == MembershipBoundary.JOIN) {
            "joined_precision"
        } else {
            "left_precision"
        }
        val shadow = db.rawQuery(
            """
            SELECT joined_at, left_at,
                   joined_date, left_date,
                   joined_time_known, left_time_known,
                   joined_precision, left_precision,
                   joined_source, left_source
            FROM tenures
            WHERE id = ?
              AND $precisionColumn IN (?, ?)
              AND (
                $boundaryColumn IS NULL
                OR ABS($boundaryColumn - ?) <= ?
              )
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                shadowTenureId.toString(),
                EvidencePrecision.INFERRED.name,
                EvidencePrecision.UNKNOWN.name,
                occurredAt.toEpochMilli().toString(),
                EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ShadowTenure(
                    joinedAt = cursor.getNullableLong(0),
                    leftAt = cursor.getNullableLong(1),
                    joinedDate = cursor.getNullableLong(2),
                    leftDate = cursor.getNullableLong(3),
                    joinedTimeKnown = cursor.getNullableInt(4),
                    leftTimeKnown = cursor.getNullableInt(5),
                    joinedPrecision = cursor.getNullableString(6),
                    leftPrecision = cursor.getNullableString(7),
                    joinedSource = cursor.getNullableString(8),
                    leftSource = cursor.getNullableString(9),
                )
            }
        } ?: return

        val oppositePrefix = if (boundary == MembershipBoundary.JOIN) "left" else "joined"
        val oppositeAt = if (boundary == MembershipBoundary.JOIN) shadow.leftAt else shadow.joinedAt
        if (oppositeAt != null) {
            val selectedHasOpposite = db.rawQuery(
                "SELECT ${oppositePrefix}_at FROM tenures WHERE id = ?",
                arrayOf(selectedTenureId.toString()),
            ).use { cursor -> cursor.moveToFirst() && !cursor.isNull(0) }
            if (selectedHasOpposite) return

            val values = ContentValues().apply {
                put("${oppositePrefix}_at", oppositeAt)
                if (boundary == MembershipBoundary.JOIN) {
                    putNullableLong("${oppositePrefix}_date", shadow.leftDate)
                    putNullableInt("${oppositePrefix}_time_known", shadow.leftTimeKnown)
                    put("${oppositePrefix}_precision", shadow.leftPrecision)
                    put("${oppositePrefix}_source", shadow.leftSource)
                } else {
                    putNullableLong("${oppositePrefix}_date", shadow.joinedDate)
                    putNullableInt("${oppositePrefix}_time_known", shadow.joinedTimeKnown)
                    put("${oppositePrefix}_precision", shadow.joinedPrecision)
                    put("${oppositePrefix}_source", shadow.joinedSource)
                }
            }
            db.update(
                "tenures",
                values,
                "id = ?",
                arrayOf(selectedTenureId.toString()),
            )
            val oppositeTypes = if (boundary == MembershipBoundary.JOIN) {
                listOf(MemberEventType.LEFT, MemberEventType.REMOVED)
            } else {
                listOf(MemberEventType.JOINED, MemberEventType.REJOINED)
            }
            db.update(
                "member_events",
                ContentValues().apply { put("tenure_id", selectedTenureId) },
                "tenure_id = ? AND event_type IN (?, ?)",
                arrayOf(
                    shadowTenureId.toString(),
                    oppositeTypes[0].name,
                    oppositeTypes[1].name,
                ),
            )
        }
        db.delete("member_events", "tenure_id = ?", arrayOf(shadowTenureId.toString()))
        db.delete("tenures", "id = ?", arrayOf(shadowTenureId.toString()))
    }

    @Synchronized
    fun listDailyPatrolFacts(from: Instant, until: Instant): List<DailyPatrolFact> {
        require(!until.isBefore(from))
        return readableDatabase.query(
            true,
            "platoon_activity",
            arrayOf("resolved_uid", "occurred_at"),
            "action_id = ? AND resolved_uid IS NOT NULL AND occurred_at >= ? AND occurred_at < ?",
            arrayOf(
                DAILY_PATROL_REWARD_ACTION_ID.toString(),
                from.toEpochMilli().toString(),
                until.toEpochMilli().toString(),
            ),
            null,
            null,
            "occurred_at, resolved_uid",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DailyPatrolFact(
                            uid = cursor.getLong(0),
                            occurredAt = Instant.ofEpochMilli(cursor.getLong(1)),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun addWithdrawnMember(
        uid: Long,
        name: String,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue,
        note: String,
    ): Boolean {
        require(uid > 0)
        require(name.isNotBlank())
        require(isValidMembershipRange(joined, withdrew))
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (memberExists(db, uid)) return false
            db.insertOrThrow(
                "members",
                null,
                ContentValues().apply {
                    put("uid", uid)
                    put("current_name", name.trim())
                    put("custom_name", name.trim())
                    put("current_level", 0)
                    put("is_active", 0)
                    put("first_seen_at", joined.instant.toEpochMilli())
                    put("last_seen_at", withdrew.instant.toEpochMilli())
                    put("note", "")
                },
            )
            val tenureId = insertManualTenure(db, uid, joined, withdrew, note)
            replaceManualTenureEvents(db, tenureId, uid, name.trim(), joined, withdrew)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addTenure(
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Boolean {
        require(isValidMembershipRange(joined, withdrew))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val memberName = memberName(db, uid) ?: return false
            val tenureId = insertManualTenure(db, uid, joined, withdrew, note)
            replaceManualTenureEvents(db, tenureId, uid, memberName, joined, withdrew)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun listSnapshots(limit: Int = 100): List<PlatoonSnapshot> {
        require(limit in 1..1000)
        return querySnapshots(
            selectedSnapshotsSql =
                """
                SELECT id, captured_at, source_file, game_version
                FROM snapshots
                ORDER BY captured_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
            selectionArgs = arrayOf(limit.toString()),
        )
    }

    /**
     * Loads only the snapshots capable of affecting a reporting period plus
     * the nearest prior roster/baseline. This avoids an arbitrary historical
     * cap and keeps weekly rendering independent of database growth.
     */
    @Synchronized
    fun listSnapshotsForPeriod(from: Instant, until: Instant): List<PlatoonSnapshot> {
        require(until.isAfter(from))
        return querySnapshots(
            selectedSnapshotsSql =
                """
                SELECT id, captured_at, source_file, game_version
                FROM snapshots
                WHERE (captured_at >= ? AND captured_at < ?)
                   OR id = (
                       SELECT id
                       FROM snapshots
                       WHERE captured_at < ?
                       ORDER BY captured_at DESC, id DESC
                       LIMIT 1
                   )
                ORDER BY captured_at DESC, id DESC
                """.trimIndent(),
            selectionArgs = arrayOf(
                from.toEpochMilli().toString(),
                until.toEpochMilli().toString(),
                from.toEpochMilli().toString(),
            ),
        )
    }

    @Synchronized
    fun listMemberStatuses(activeOnly: Boolean = false): List<MemberStatus> {
        val db = readableDatabase
        val selection = if (activeOnly) "is_active = 1" else null
        return db.query(
            "members",
            MEMBER_COLUMNS,
            selection,
            null,
            null,
            null,
            "is_active DESC, COALESCE(custom_name, current_name) COLLATE NOCASE, uid",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uid = cursor.getLong(0)
                    add(
                        MemberStatus(
                            uid = uid,
                            name = cursor.getString(1),
                            level = cursor.getLong(2),
                            isActive = cursor.getInt(3) != 0,
                            firstSeenAt = Instant.ofEpochMilli(cursor.getLong(4)),
                            lastSeenAt = Instant.ofEpochMilli(cursor.getLong(5)),
                            note = cursor.getString(6),
                            tenures = readTenures(db, uid),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun listEvents(
        from: Instant,
        until: Instant,
        fromDate: LocalDate,
        untilDate: LocalDate,
    ): List<MemberEvent> {
        require(!until.isBefore(from))
        require(!untilDate.isBefore(fromDate))
        return readableDatabase.query(
            "member_events",
            EVENT_COLUMNS,
            """
            (
                event_date IS NOT NULL
                AND event_date >= CAST(? AS INTEGER)
                AND event_date < CAST(? AS INTEGER)
            )
            OR
            (
                event_date IS NULL
                AND COALESCE(occurred_at, observed_at) >= CAST(? AS INTEGER)
                AND COALESCE(occurred_at, observed_at) < CAST(? AS INTEGER)
            )
            """.trimIndent(),
            arrayOf(
                fromDate.toEpochDay().toString(),
                untilDate.toEpochDay().toString(),
                from.toEpochMilli().toString(),
                until.toEpochMilli().toString(),
            ),
            null,
            null,
            "COALESCE(occurred_at, observed_at), id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toMemberEvent())
            }
        }
    }

    @Synchronized
    fun updateMember(uid: Long, name: String, note: String): Boolean {
        require(name.isNotBlank())
        val values = ContentValues().apply {
            put("custom_name", name.trim())
            put("note", note.trim())
        }
        return writableDatabase.update("members", values, "uid = ?", arrayOf(uid.toString())) == 1
    }

    @Synchronized
    fun updateTenure(
        tenureId: Long,
        joined: MembershipBoundaryValue,
        left: MembershipBoundaryValue?,
        note: String,
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val tenure = readTenureForUpdate(db, tenureId) ?: return false
            val effectiveJoined = if (tenure.joinedSource.isImmutableMembershipBoundary()) {
                tenure.joined
            } else {
                joined
            } ?: return false
            val effectiveLeft = if (tenure.leftSource?.isImmutableMembershipBoundary() == true) {
                tenure.left
            } else {
                left
            }
            require(isValidMembershipRange(effectiveJoined, effectiveLeft))
            val updated = db.update(
                "tenures",
                ContentValues().apply {
                    if (!tenure.joinedSource.isImmutableMembershipBoundary()) {
                        putMembershipBoundary("joined", joined)
                        put("joined_precision", EvidencePrecision.MANUAL.name)
                        put("joined_source", EvidenceSource.MANUAL.name)
                    }
                    if (tenure.leftSource?.isImmutableMembershipBoundary() != true) {
                        if (left == null) {
                            clearMembershipBoundary("left")
                            putNull("left_precision")
                            putNull("left_source")
                        } else {
                            putMembershipBoundary("left", left)
                            put("left_precision", EvidencePrecision.MANUAL.name)
                            put("left_source", EvidenceSource.MANUAL.name)
                        }
                    }
                    put("note", note.trim())
                },
                "id = ?",
                arrayOf(tenureId.toString()),
            ) == 1
            if (updated) {
                replaceManualTenureEvents(
                    db,
                    tenureId,
                    tenure.uid,
                    memberName(db, tenure.uid).orEmpty(),
                    if (tenure.joinedSource.isImmutableMembershipBoundary()) {
                        null
                    } else {
                        joined
                    },
                    if (tenure.leftSource?.isImmutableMembershipBoundary() == true) null else left,
                )
            }
            db.setTransactionSuccessful()
            return updated
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addWeeklyNote(periodStartEpochDay: Long, gameDayEpochDay: Long, text: String): Long {
        require(text.isNotBlank())
        return writableDatabase.insertOrThrow(
            "weekly_notes",
            null,
            ContentValues().apply {
                put("period_start", periodStartEpochDay)
                put("game_day", gameDayEpochDay)
                put("text", text.trim())
                putNull("event_id")
                put("is_automatic", 0)
            },
        )
    }

    @Synchronized
    fun listWeeklyNotes(periodStartEpochDay: Long): List<WeeklyNote> =
        readableDatabase.query(
            "weekly_notes",
            WEEKLY_NOTE_COLUMNS,
            "period_start = ?",
            arrayOf(periodStartEpochDay.toString()),
            null,
            null,
            "game_day, id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WeeklyNote(
                            id = cursor.getLong(0),
                            periodStart = java.time.LocalDate.ofEpochDay(cursor.getLong(1)),
                            gameDay = java.time.LocalDate.ofEpochDay(cursor.getLong(2)),
                            text = cursor.getString(3),
                            eventId = cursor.getNullableLong(4),
                            isAutomatic = cursor.getInt(5) != 0,
                        ),
                    )
                }
            }
        }

    @Synchronized
    fun deleteWeeklyNote(id: Long): Boolean =
        writableDatabase.delete(
            "weekly_notes",
            "id = ? AND is_automatic = 0",
            arrayOf(id.toString()),
        ) == 1

    @Synchronized
    fun listWeeklyOverrides(periodStartEpochDay: Long): List<WeeklyCellOverride> =
        readableDatabase.query(
            "weekly_overrides",
            WEEKLY_OVERRIDE_COLUMNS,
            "period_start = ?",
            arrayOf(periodStartEpochDay.toString()),
            null,
            null,
            "game_day, uid",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WeeklyCellOverride(
                            uid = cursor.getLong(0),
                            periodStart = java.time.LocalDate.ofEpochDay(cursor.getLong(1)),
                            gameDay = java.time.LocalDate.ofEpochDay(cursor.getLong(2)),
                            meritDelta = cursor.getNullableLong(3),
                            scoreDelta = cursor.getNullableLong(4),
                            attempts = cursor.getNullableInt(5),
                            attended = cursor.getNullableBoolean(6),
                            dailyPatrol = cursor.getNullableBoolean(7),
                        ),
                    )
                }
            }
        }

    @Synchronized
    fun replaceWeeklyOverrides(
        periodStartEpochDay: Long,
        overrides: List<WeeklyCellOverride>,
    ) {
        require(overrides.all { it.periodStart.toEpochDay() == periodStartEpochDay })
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "weekly_overrides",
                "period_start = ?",
                arrayOf(periodStartEpochDay.toString()),
            )
            overrides.forEach { override ->
                db.insertOrThrow(
                    "weekly_overrides",
                    null,
                    ContentValues().apply {
                        put("uid", override.uid)
                        put("period_start", periodStartEpochDay)
                        put("game_day", override.gameDay.toEpochDay())
                        putNullableLong("merit_delta", override.meritDelta)
                        putNullableLong("score_delta", override.scoreDelta)
                        putNullableInt("attempts", override.attempts)
                        putNullableBoolean("attended", override.attended)
                        putNullableBoolean("daily_patrol", override.dailyPatrol)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createWeeklyOverridesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS weekly_overrides (
                uid INTEGER NOT NULL,
                period_start INTEGER NOT NULL,
                game_day INTEGER NOT NULL,
                merit_delta INTEGER,
                score_delta INTEGER,
                attempts INTEGER,
                attended INTEGER,
                daily_patrol INTEGER,
                PRIMARY KEY(uid, game_day)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS weekly_overrides_period " +
                "ON weekly_overrides(period_start, game_day)",
        )
    }

    private fun createPlatoonActivityTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS platoon_activity (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                occurred_at INTEGER NOT NULL,
                action_id INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                member_name TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                resolved_uid INTEGER REFERENCES members(uid),
                resolution TEXT NOT NULL DEFAULT 'UNRESOLVED',
                member_event_id INTEGER REFERENCES member_events(id)
            )
            """.trimIndent(),
        )
        createPlatoonActivityIndexes(db)
    }

    private fun createPlatoonActivityIndexes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS platoon_activity_member_time " +
                "ON platoon_activity(member_name, occurred_at)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS platoon_activity_action_time " +
                "ON platoon_activity(action_id, occurred_at)",
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS platoon_activity_exact_identity
            ON platoon_activity(occurred_at, action_id, kind, resolved_uid)
            WHERE resolved_uid IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS platoon_activity_unresolved_identity
            ON platoon_activity(occurred_at, action_id, kind, member_name)
            WHERE resolved_uid IS NULL
            """.trimIndent(),
        )
    }

    private fun migratePlatoonActivityIdentity(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE platoon_activity RENAME TO platoon_activity_legacy")
        db.execSQL(
            """
            CREATE TABLE platoon_activity (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                occurred_at INTEGER NOT NULL,
                action_id INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                member_name TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                resolved_uid INTEGER REFERENCES members(uid),
                resolution TEXT NOT NULL DEFAULT 'UNRESOLVED',
                member_event_id INTEGER REFERENCES member_events(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX platoon_activity_exact_identity
            ON platoon_activity(occurred_at, action_id, kind, resolved_uid)
            WHERE resolved_uid IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX platoon_activity_unresolved_identity
            ON platoon_activity(occurred_at, action_id, kind, member_name)
            WHERE resolved_uid IS NULL
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO platoon_activity(
                id, occurred_at, action_id, kind, member_name, captured_at,
                resolved_uid, resolution, member_event_id
            )
            SELECT id, occurred_at, action_id, kind, member_name, captured_at,
                   resolved_uid, resolution, member_event_id
            FROM platoon_activity_legacy
            ORDER BY CASE WHEN member_event_id IS NULL THEN 1 ELSE 0 END, id
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE platoon_activity_legacy")
        createPlatoonActivityIndexes(db)
    }

    private fun latestSnapshotCapturedAt(db: SQLiteDatabase): Instant? =
        db.rawQuery("SELECT MAX(captured_at) FROM snapshots", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                Instant.ofEpochMilli(cursor.getLong(0))
            } else {
                null
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

    private fun resolveUnresolvedActivityUids(db: SQLiteDatabase) {
        val unresolved = db.query(
            "platoon_activity",
            arrayOf("id", "occurred_at", "member_name", "action_id", "kind"),
            "resolved_uid IS NULL",
            null,
            null,
            null,
            "occurred_at, id",
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
    }

    private fun ensureRosterActiveTenure(
        db: SQLiteDatabase,
        uid: Long,
        source: EvidenceSource,
    ) {
        val hasOpenTenure = db.rawQuery(
            "SELECT 1 FROM tenures WHERE uid = ? AND left_at IS NULL LIMIT 1",
            arrayOf(uid.toString()),
        ).use(Cursor::moveToFirst)
        if (hasOpenTenure) return
        insertTenure(
            db = db,
            uid = uid,
            joinedAt = null,
            precision = EvidencePrecision.UNKNOWN,
            source = source,
        )
    }

    private fun correlateMembershipBoundary(
        db: SQLiteDatabase,
        tenureId: Long,
        member: SnapshotMember,
        type: MemberEventType,
        boundary: MembershipBoundary,
        observedAt: Instant,
        from: Instant?,
        nameIsUnique: Boolean,
    ) {
        if (!nameIsUnique || from == null) return
        val candidates = readMembershipActivityCandidates(
            db = db,
            memberName = member.name,
            from = from,
            until = observedAt,
        )
        val occurredAt = candidates.map(ActivityCandidate::occurredAt).distinct().singleOrNull()
            ?: return
        applyExactMembershipBoundary(
            db = db,
            tenureId = tenureId,
            uid = member.uid,
            memberName = member.name,
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

    private fun reconcileInferredMembershipBoundaries(db: SQLiteDatabase) {
        val activities = db.rawQuery(
            """
            SELECT id, occurred_at, member_name, resolved_uid
            FROM platoon_activity
            WHERE member_event_id IS NULL
              AND resolved_uid IS NOT NULL
              AND action_id NOT IN (?, ?)
            ORDER BY occurred_at, id
            """.trimIndent(),
            arrayOf(
                DAILY_PATROL_REWARD_ACTION_ID.toString(),
                DAILY_PATROL_RELATED_ACTION_ID.toString(),
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
            val boundaries = readNearbyInferredBoundaries(db, activity)
            val boundary = boundaries.singleOrNull() ?: return@forEach
            val eventType = when (boundary.boundary) {
                MembershipBoundary.WITHDRAW -> MemberEventType.LEFT
                MembershipBoundary.JOIN -> {
                    if (hasEarlierTenure(db, boundary.uid, boundary.tenureId)) {
                        MemberEventType.REJOINED
                    } else {
                        MemberEventType.JOINED
                    }
                }
            }
            applyExactMembershipBoundary(
                db = db,
                tenureId = boundary.tenureId,
                uid = boundary.uid,
                memberName = activity.memberName,
                type = eventType,
                boundary = boundary.boundary,
                occurredAt = activity.occurredAt,
                observedAt = boundary.inferredAt,
                activityIds = listOf(activity.id),
            )
        }
    }

    private fun readNearbyInferredBoundaries(
        db: SQLiteDatabase,
        activity: ResolvedActivityCandidate,
    ): List<InferredBoundary> = db.rawQuery(
        """
        SELECT id, uid, joined_at, left_at, joined_precision, left_precision
        FROM tenures
        WHERE uid = ?
          AND (
            (joined_at IS NOT NULL AND joined_precision = ? AND ABS(joined_at - ?) <= ?)
            OR
            (left_at IS NOT NULL AND left_precision = ? AND ABS(left_at - ?) <= ?)
          )
        """.trimIndent(),
        arrayOf(
            activity.uid.toString(),
            EvidencePrecision.INFERRED.name,
            activity.occurredAt.toEpochMilli().toString(),
            MEMBERSHIP_CORRELATION_WINDOW_MILLIS.toString(),
            EvidencePrecision.INFERRED.name,
            activity.occurredAt.toEpochMilli().toString(),
            MEMBERSHIP_CORRELATION_WINDOW_MILLIS.toString(),
        ),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val tenureId = cursor.getLong(0)
                val uid = cursor.getLong(1)
                val joinedAt = cursor.getNullableLong(2)
                val leftAt = cursor.getNullableLong(3)
                if (joinedAt != null &&
                    cursor.getString(4) == EvidencePrecision.INFERRED.name &&
                    kotlin.math.abs(joinedAt - activity.occurredAt.toEpochMilli()) <=
                    MEMBERSHIP_CORRELATION_WINDOW_MILLIS
                ) {
                    add(
                        InferredBoundary(
                            tenureId,
                            uid,
                            MembershipBoundary.JOIN,
                            Instant.ofEpochMilli(joinedAt),
                        ),
                    )
                }
                if (leftAt != null &&
                    cursor.getNullableString(5) == EvidencePrecision.INFERRED.name &&
                    kotlin.math.abs(leftAt - activity.occurredAt.toEpochMilli()) <=
                    MEMBERSHIP_CORRELATION_WINDOW_MILLIS
                ) {
                    add(
                        InferredBoundary(
                            tenureId,
                            uid,
                            MembershipBoundary.WITHDRAW,
                            Instant.ofEpochMilli(leftAt),
                        ),
                    )
                }
            }
        }
    }

    private fun applyExactMembershipBoundary(
        db: SQLiteDatabase,
        tenureId: Long,
        uid: Long,
        memberName: String,
        type: MemberEventType,
        boundary: MembershipBoundary,
        occurredAt: Instant,
        observedAt: Instant,
        activityIds: List<Long>,
    ) {
        val values = ContentValues().apply {
            when (boundary) {
                MembershipBoundary.JOIN -> {
                    put("joined_at", occurredAt.toEpochMilli())
                    putNull("joined_date")
                    put("joined_time_known", 1)
                    put("joined_precision", EvidencePrecision.EXACT.name)
                    put("joined_source", EvidenceSource.GAME_UPDATES.name)
                }
                MembershipBoundary.WITHDRAW -> {
                    put("left_at", occurredAt.toEpochMilli())
                    putNull("left_date")
                    put("left_time_known", 1)
                    put("left_precision", EvidencePrecision.EXACT.name)
                    put("left_source", EvidenceSource.GAME_UPDATES.name)
                }
            }
        }
        db.update("tenures", values, "id = ?", arrayOf(tenureId.toString()))
        val inferredTypes = when (boundary) {
            MembershipBoundary.JOIN -> arrayOf(
                MemberEventType.JOINED.name,
                MemberEventType.REJOINED.name,
            )
            MembershipBoundary.WITHDRAW -> arrayOf(
                MemberEventType.LEFT.name,
                MemberEventType.REMOVED.name,
            )
        }
        db.delete(
            "member_events",
            "tenure_id = ? AND source IN (?, ?) AND event_type IN (?, ?)",
            arrayOf(
                tenureId.toString(),
                EvidenceSource.SNAPSHOT.name,
                EvidenceSource.LEGACY_IMPORT.name,
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
            source = EvidenceSource.GAME_UPDATES,
            note = memberName,
            tenureId = tenureId,
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

    private fun insertManualTenure(
        db: SQLiteDatabase,
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Long = db.insertOrThrow(
        "tenures",
        null,
        ContentValues().apply {
            put("uid", uid)
            putMembershipBoundary("joined", joined)
            put("joined_precision", EvidencePrecision.MANUAL.name)
            put("joined_source", EvidenceSource.MANUAL.name)
            if (withdrew == null) {
                clearMembershipBoundary("left")
                putNull("left_precision")
                putNull("left_source")
            } else {
                putMembershipBoundary("left", withdrew)
                put("left_precision", EvidencePrecision.MANUAL.name)
                put("left_source", EvidenceSource.MANUAL.name)
            }
            put("note", note.trim())
        },
    )

    private fun replaceManualTenureEvents(
        db: SQLiteDatabase,
        tenureId: Long,
        uid: Long,
        memberName: String,
        joined: MembershipBoundaryValue?,
        withdrew: MembershipBoundaryValue?,
    ) {
        db.delete(
            "member_events",
            "tenure_id = ? AND source = ?",
            arrayOf(tenureId.toString(), EvidenceSource.MANUAL.name),
        )
        val observedAt = Instant.now()
        if (joined != null) {
            insertEvent(
                db = db,
                uid = uid,
                type = if (hasEarlierTenure(db, uid, tenureId)) {
                    MemberEventType.REJOINED
                } else {
                    MemberEventType.JOINED
                },
                occurredAt = joined.instant,
                eventDate = joined.date,
                timeKnown = joined.timeKnown,
                observedAt = observedAt,
                precision = EvidencePrecision.MANUAL,
                source = EvidenceSource.MANUAL,
                note = memberName,
                tenureId = tenureId,
            )
        }
        if (withdrew != null) {
            insertEvent(
                db = db,
                uid = uid,
                type = MemberEventType.LEFT,
                occurredAt = withdrew.instant,
                eventDate = withdrew.date,
                timeKnown = withdrew.timeKnown,
                observedAt = observedAt,
                precision = EvidencePrecision.MANUAL,
                source = EvidenceSource.MANUAL,
                note = memberName,
                tenureId = tenureId,
            )
        }
    }

    private fun backfillManualTenureEvents(db: SQLiteDatabase) {
        val manualTenures = db.rawQuery(
            """
            SELECT id, uid, joined_at, left_at
            FROM tenures
            WHERE joined_source = ? OR left_source = ?
            ORDER BY uid, id
            """.trimIndent(),
            arrayOf(EvidenceSource.MANUAL.name, EvidenceSource.MANUAL.name),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ManualTenureBoundary(
                            tenureId = cursor.getLong(0),
                            uid = cursor.getLong(1),
                            joinedAt = cursor.getNullableLong(2)?.let(Instant::ofEpochMilli),
                            withdrewAt = cursor.getNullableLong(3)?.let(Instant::ofEpochMilli),
                        ),
                    )
                }
            }
        }
        manualTenures.forEach { tenure ->
            replaceManualTenureEvents(
                db,
                tenure.tenureId,
                tenure.uid,
                memberName(db, tenure.uid).orEmpty(),
                tenure.joinedAt?.let {
                    MembershipBoundaryValue(
                        date = it.atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                        instant = it,
                        timeKnown = true,
                    )
                },
                tenure.withdrewAt?.let {
                    MembershipBoundaryValue(
                        date = it.atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                        instant = it,
                        timeKnown = true,
                    )
                },
            )
        }
    }

    private fun backfillManualCalendarDates(db: SQLiteDatabase, zoneId: ZoneId) {
        val tenures = db.rawQuery(
            """
            SELECT id, joined_at, left_at, joined_source, left_source
            FROM tenures
            WHERE (joined_source = ? AND joined_at IS NOT NULL)
               OR (left_source = ? AND left_at IS NOT NULL)
            """.trimIndent(),
            arrayOf(EvidenceSource.MANUAL.name, EvidenceSource.MANUAL.name),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ManualBoundaryDateBackfill(
                            id = cursor.getLong(0),
                            joinedAtMillis = cursor.getNullableLong(1),
                            leftAtMillis = cursor.getNullableLong(2),
                            joinedIsManual = cursor.getString(3) == EvidenceSource.MANUAL.name,
                            leftIsManual =
                                cursor.getNullableString(4) == EvidenceSource.MANUAL.name,
                        ),
                    )
                }
            }
        }
        tenures.forEach { tenure ->
            db.update(
                "tenures",
                ContentValues().apply {
                    if (tenure.joinedIsManual && tenure.joinedAtMillis != null) {
                        put(
                            "joined_date",
                            migrationCalendarDate(tenure.joinedAtMillis, zoneId).toEpochDay(),
                        )
                        put("joined_time_known", 1)
                    }
                    if (tenure.leftIsManual && tenure.leftAtMillis != null) {
                        put(
                            "left_date",
                            migrationCalendarDate(tenure.leftAtMillis, zoneId).toEpochDay(),
                        )
                        put("left_time_known", 1)
                    }
                },
                "id = ?",
                arrayOf(tenure.id.toString()),
            )
        }

        val events = db.rawQuery(
            """
            SELECT id, occurred_at
            FROM member_events
            WHERE source = ?
              AND occurred_at IS NOT NULL
            """.trimIndent(),
            arrayOf(EvidenceSource.MANUAL.name),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1))
            }
        }
        events.forEach { (id, occurredAtMillis) ->
            db.update(
                "member_events",
                ContentValues().apply {
                    put(
                        "event_date",
                        migrationCalendarDate(occurredAtMillis, zoneId).toEpochDay(),
                    )
                    put("time_known", 1)
                },
                "id = ?",
                arrayOf(id.toString()),
            )
        }
    }

    private fun backfillSnapshotTenureEvents(db: SQLiteDatabase) {
        db.rawQuery(
            """
            SELECT t.id, t.uid, t.joined_at, t.left_at, t.joined_source, t.left_source,
                   COALESCE(m.custom_name, m.current_name)
            FROM tenures t
            JOIN members m ON m.uid = t.uid
            WHERE t.joined_source IN (?, ?)
               OR t.left_source IN (?, ?)
            ORDER BY t.uid, t.id
            """.trimIndent(),
            arrayOf(
                EvidenceSource.SNAPSHOT.name,
                EvidenceSource.LEGACY_IMPORT.name,
                EvidenceSource.SNAPSHOT.name,
                EvidenceSource.LEGACY_IMPORT.name,
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val tenureId = cursor.getLong(0)
                val uid = cursor.getLong(1)
                val joinedAt = cursor.getNullableLong(2)?.let(Instant::ofEpochMilli)
                val leftAt = cursor.getNullableLong(3)?.let(Instant::ofEpochMilli)
                val joinedSource = EvidenceSource.valueOf(cursor.getString(4))
                val leftSource = cursor.getNullableString(5)?.let(EvidenceSource::valueOf)
                val name = cursor.getString(6)
                if (joinedAt != null && joinedSource in SNAPSHOT_EVENT_SOURCES) {
                    insertSnapshotBoundaryEvent(
                        db,
                        tenureId,
                        uid,
                        name,
                        if (hasEarlierTenure(db, uid, tenureId)) {
                            MemberEventType.REJOINED
                        } else {
                            MemberEventType.JOINED
                        },
                        joinedAt,
                        joinedSource,
                    )
                }
                if (leftAt != null && leftSource in SNAPSHOT_EVENT_SOURCES) {
                    insertSnapshotBoundaryEvent(
                        db,
                        tenureId,
                        uid,
                        name,
                        MemberEventType.LEFT,
                        leftAt,
                        requireNotNull(leftSource),
                    )
                }
            }
        }
    }

    private fun insertSnapshotBoundaryEvent(
        db: SQLiteDatabase,
        tenureId: Long,
        uid: Long,
        memberName: String,
        type: MemberEventType,
        observedAt: Instant,
        source: EvidenceSource,
    ) {
        val exists = db.rawQuery(
            "SELECT 1 FROM member_events WHERE tenure_id = ? AND event_type = ? LIMIT 1",
            arrayOf(tenureId.toString(), type.name),
        ).use { cursor -> cursor.moveToFirst() }
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
            tenureId = tenureId,
        )
    }

    private fun hasEarlierTenure(db: SQLiteDatabase, uid: Long, tenureId: Long): Boolean =
        db.rawQuery(
            """
            SELECT 1
            FROM tenures current
            JOIN tenures other ON other.uid = current.uid AND other.id != current.id
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
            arrayOf(tenureId.toString(), uid.toString()),
        ).use(Cursor::moveToFirst)

    private fun memberExists(db: SQLiteDatabase, uid: Long): Boolean =
        db.rawQuery(
            "SELECT 1 FROM members WHERE uid = ? LIMIT 1",
            arrayOf(uid.toString()),
        ).use(Cursor::moveToFirst)

    private fun memberName(db: SQLiteDatabase, uid: Long): String? =
        db.rawQuery(
            "SELECT COALESCE(custom_name, current_name) FROM members WHERE uid = ?",
            arrayOf(uid.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun readTenureForUpdate(db: SQLiteDatabase, tenureId: Long): TenureForUpdate? =
        db.rawQuery(
            """
            SELECT uid, joined_at, left_at, joined_date, left_date,
                   joined_time_known, left_time_known, joined_source, left_source
            FROM tenures
            WHERE id = ?
            """.trimIndent(),
            arrayOf(tenureId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                TenureForUpdate(
                    uid = cursor.getLong(0),
                    joined = cursor.membershipBoundaryValue(
                        instantIndex = 1,
                        dateIndex = 3,
                        timeKnownIndex = 5,
                    ),
                    left = cursor.membershipBoundaryValue(
                        instantIndex = 2,
                        dateIndex = 4,
                        timeKnownIndex = 6,
                    ),
                    joinedSource = EvidenceSource.valueOf(cursor.getString(7)),
                    leftSource = cursor.getNullableString(8)?.let(EvidenceSource::valueOf),
                )
            }
        }

    private fun insertSnapshotMember(
        db: SQLiteDatabase,
        snapshotId: Long,
        member: SnapshotMember,
    ) {
        db.insertOrThrow(
            "snapshot_members",
            null,
            ContentValues().apply {
                put("snapshot_id", snapshotId)
                put("uid", member.uid)
                put("name", member.name)
                put("level", member.level)
                put("weekly_merit", member.weeklyMerit)
                put("total_merit", member.totalMerit)
                put("high_score", member.highScore)
                put("total_score", member.totalScore)
                put("last_login", member.lastLogin)
            },
        )
    }

    private fun upsertMember(db: SQLiteDatabase, member: SnapshotMember, observedAt: Instant) {
        val existing = ContentValues().apply {
            put("current_name", member.name)
            put("current_level", member.level)
            put("is_active", 1)
            put("last_seen_at", observedAt.toEpochMilli())
        }
        if (db.update("members", existing, "uid = ?", arrayOf(member.uid.toString())) == 0) {
            existing.put("uid", member.uid)
            existing.put("first_seen_at", observedAt.toEpochMilli())
            db.insertOrThrow("members", null, existing)
        }
    }

    private fun markInactive(db: SQLiteDatabase, uid: Long, observedAt: Instant) {
        db.update(
            "members",
            ContentValues().apply {
                put("is_active", 0)
                put("last_seen_at", observedAt.toEpochMilli())
            },
            "uid = ?",
            arrayOf(uid.toString()),
        )
    }

    private fun insertTenure(
        db: SQLiteDatabase,
        uid: Long,
        joinedAt: Instant?,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ): Long = db.insertOrThrow(
        "tenures",
        null,
        ContentValues().apply {
            put("uid", uid)
            putNullableLong("joined_at", joinedAt?.toEpochMilli())
            put("joined_precision", precision.name)
            put("joined_source", source.name)
        },
    )

    private fun closeLatestTenure(
        db: SQLiteDatabase,
        uid: Long,
        leftAt: Instant,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ): Long {
        val tenureId = db.rawQuery(
            "SELECT id FROM tenures WHERE uid = ? AND left_at IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf(uid.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: error("Active member $uid has no open tenure")
        db.update(
            "tenures",
            ContentValues().apply {
                put("left_at", leftAt.toEpochMilli())
                putNull("left_date")
                put("left_time_known", 1)
                put("left_precision", precision.name)
                put("left_source", source.name)
            },
            "id = ?",
            arrayOf(tenureId.toString()),
        )
        return tenureId
    }

    private fun insertEvent(
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
        tenureId: Long? = null,
    ): Long = db.insertOrThrow(
        "member_events",
        null,
        ContentValues().apply {
            put("uid", uid)
            putNullableLong("tenure_id", tenureId)
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

    private fun readKnownMembers(db: SQLiteDatabase): List<SnapshotReconciler.KnownMember> =
        db.rawQuery(
            """
            SELECT m.uid, m.current_name, m.is_active,
                   EXISTS(SELECT 1 FROM tenures t WHERE t.uid = m.uid)
            FROM members m
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SnapshotReconciler.KnownMember(
                            uid = cursor.getLong(0),
                            name = cursor.getString(1),
                            isActive = cursor.getInt(2) != 0,
                            hasPriorTenure = cursor.getInt(3) != 0,
                        ),
                    )
                }
            }
        }

    private fun querySnapshots(
        selectedSnapshotsSql: String,
        selectionArgs: Array<String>,
    ): List<PlatoonSnapshot> = readableDatabase.rawQuery(
        """
        SELECT selected.id, selected.captured_at, selected.source_file, selected.game_version,
               member.uid, member.name, member.level, member.weekly_merit,
               member.total_merit, member.high_score, member.total_score, member.last_login
        FROM ($selectedSnapshotsSql) AS selected
        LEFT JOIN snapshot_members AS member ON member.snapshot_id = selected.id
        ORDER BY selected.captured_at DESC, selected.id DESC,
                 member.name COLLATE NOCASE, member.uid
        """.trimIndent(),
        selectionArgs,
    ).use { cursor ->
        val snapshots = linkedMapOf<Long, SnapshotAccumulator>()
        while (cursor.moveToNext()) {
            val snapshotId = cursor.getLong(0)
            val snapshot = snapshots.getOrPut(snapshotId) {
                SnapshotAccumulator(
                    id = snapshotId,
                    capturedAt = Instant.ofEpochMilli(cursor.getLong(1)),
                    sourceFile = cursor.getNullableString(2),
                    gameVersion = cursor.getNullableString(3),
                )
            }
            if (!cursor.isNull(4)) {
                snapshot.members += SnapshotMember(
                    uid = cursor.getLong(4),
                    name = cursor.getString(5),
                    level = cursor.getLong(6),
                    weeklyMerit = cursor.getLong(7),
                    totalMerit = cursor.getLong(8),
                    highScore = cursor.getLong(9),
                    totalScore = cursor.getLong(10),
                    lastLogin = cursor.getLong(11),
                )
            }
        }
        snapshots.values.map(SnapshotAccumulator::toSnapshot)
    }

    private fun readTenures(db: SQLiteDatabase, uid: Long): List<MembershipTenure> =
        db.query(
            "tenures",
            TENURE_COLUMNS,
            "uid = ?",
            arrayOf(uid.toString()),
            null,
            null,
            "CASE WHEN joined_at IS NULL THEN 1 ELSE 0 END, joined_at, id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MembershipTenure(
                            id = cursor.getLong(0),
                            uid = uid,
                            joinedAt = cursor.getNullableLong(1)?.let(Instant::ofEpochMilli),
                            leftAt = cursor.getNullableLong(2)?.let(Instant::ofEpochMilli),
                            joinedDate = cursor.getNullableLong(3)?.let(LocalDate::ofEpochDay),
                            leftDate = cursor.getNullableLong(4)?.let(LocalDate::ofEpochDay),
                            joinedTimeKnown = cursor.getInt(5) != 0,
                            leftTimeKnown = cursor.getNullableInt(6)?.let { it != 0 },
                            joinedPrecision = enumValueOf(cursor.getString(7)),
                            leftPrecision = cursor.getNullableString(8)
                                ?.let(EvidencePrecision::valueOf),
                            joinedSource = enumValueOf(cursor.getString(9)),
                            leftSource = cursor.getNullableString(10)
                                ?.let(EvidenceSource::valueOf),
                            note = cursor.getString(11),
                        ),
                    )
                }
            }
        }

    private fun sourceFileExists(db: SQLiteDatabase, sourceFile: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM snapshots WHERE source_file = ? LIMIT 1",
            arrayOf(sourceFile),
        ).use { cursor -> cursor.moveToFirst() }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun Cursor.toMemberEvent(): MemberEvent = MemberEvent(
        id = getLong(0),
        uid = getLong(1),
        type = enumValueOf(getString(2)),
        occurredAt = getNullableLong(3)?.let(Instant::ofEpochMilli),
        eventDate = getNullableLong(4)?.let(LocalDate::ofEpochDay),
        timeKnown = getInt(5) != 0,
        observedAt = Instant.ofEpochMilli(getLong(6)),
        precision = enumValueOf(getString(7)),
        source = enumValueOf(getString(8)),
        note = getString(9),
    )

    private enum class MembershipBoundary {
        JOIN,
        WITHDRAW,
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
        val tenureId: Long,
        val uid: Long,
        val boundary: MembershipBoundary,
        val inferredAt: Instant,
    )

    private data class BoundaryEventCandidate(
        val id: Long,
        val tenureId: Long?,
        val boundaryAt: Instant,
    )

    private data class TenureForUpdate(
        val uid: Long,
        val joined: MembershipBoundaryValue?,
        val left: MembershipBoundaryValue?,
        val joinedSource: EvidenceSource,
        val leftSource: EvidenceSource?,
    )

    private data class SnapshotAccumulator(
        val id: Long,
        val capturedAt: Instant,
        val sourceFile: String?,
        val gameVersion: String?,
        val members: MutableList<SnapshotMember> = mutableListOf(),
    ) {
        fun toSnapshot() = PlatoonSnapshot(
            id = id,
            capturedAt = capturedAt,
            sourceFile = sourceFile,
            gameVersion = gameVersion,
            members = members,
        )
    }

    private data class ShadowTenure(
        val joinedAt: Long?,
        val leftAt: Long?,
        val joinedDate: Long?,
        val leftDate: Long?,
        val joinedTimeKnown: Int?,
        val leftTimeKnown: Int?,
        val joinedPrecision: String?,
        val leftPrecision: String?,
        val joinedSource: String?,
        val leftSource: String?,
    )

    private data class ManualTenureBoundary(
        val tenureId: Long,
        val uid: Long,
        val joinedAt: Instant?,
        val withdrewAt: Instant?,
    )

    private data class ManualBoundaryDateBackfill(
        val id: Long,
        val joinedAtMillis: Long?,
        val leftAtMillis: Long?,
        val joinedIsManual: Boolean,
        val leftIsManual: Boolean,
    )

    companion object {
        /**
         * A sparse positive signal: the member triggered a Daily Patrol supply
         * reward, which proves completion for that member and day. Its absence
         * must never be interpreted as a missed Daily Patrol.
         */
        const val DAILY_PATROL_REWARD_ACTION_ID = 802001L
        private const val DAILY_PATROL_RELATED_ACTION_ID = 801005L
        private const val NAME_RESOLUTION_WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private const val MEMBERSHIP_CORRELATION_WINDOW_MILLIS = 12L * 60L * 60L * 1000L
        private const val EXACT_UPDATE_CORRELATION_WINDOW_MILLIS =
            48L * 60L * 60L * 1000L
        private val SNAPSHOT_EVENT_SOURCES = setOf(
            EvidenceSource.SNAPSHOT,
            EvidenceSource.LEGACY_IMPORT,
        )
        private val SNAPSHOT_COLUMNS = arrayOf("id", "captured_at", "source_file", "game_version")
        private val MEMBER_COLUMNS = arrayOf(
            "uid",
            "COALESCE(custom_name, current_name)",
            "current_level",
            "is_active",
            "first_seen_at",
            "last_seen_at",
            "note",
        )
        private val EVENT_COLUMNS = arrayOf(
            "id",
            "uid",
            "event_type",
            "occurred_at",
            "event_date",
            "time_known",
            "observed_at",
            "precision",
            "source",
            "note",
        )
        private val SNAPSHOT_MEMBER_COLUMNS = arrayOf(
            "uid",
            "name",
            "level",
            "weekly_merit",
            "total_merit",
            "high_score",
            "total_score",
            "last_login",
        )
        private val TENURE_COLUMNS = arrayOf(
            "id",
            "joined_at",
            "left_at",
            "joined_date",
            "left_date",
            "joined_time_known",
            "left_time_known",
            "joined_precision",
            "left_precision",
            "joined_source",
            "left_source",
            "note",
        )
        private val WEEKLY_NOTE_COLUMNS = arrayOf(
            "id",
            "period_start",
            "game_day",
            "text",
            "event_id",
            "is_automatic",
        )
        private val WEEKLY_OVERRIDE_COLUMNS = arrayOf(
            "uid",
            "period_start",
            "game_day",
            "merit_delta",
            "score_delta",
            "attempts",
            "attended",
            "daily_patrol",
        )
    }
}

private fun ContentValues.putNullableLong(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putMembershipBoundary(
    prefix: String,
    boundary: MembershipBoundaryValue,
) {
    put("${prefix}_at", boundary.instant.toEpochMilli())
    put("${prefix}_date", boundary.date.toEpochDay())
    put("${prefix}_time_known", if (boundary.timeKnown) 1 else 0)
}

private fun ContentValues.clearMembershipBoundary(prefix: String) {
    putNull("${prefix}_at")
    putNull("${prefix}_date")
    putNull("${prefix}_time_known")
}

private fun ContentValues.putNullableInt(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullableBoolean(key: String, value: Boolean?) {
    if (value == null) putNull(key) else put(key, if (value) 1 else 0)
}

private fun Cursor.getNullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun Cursor.getNullableInt(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

private fun Cursor.membershipBoundaryValue(
    instantIndex: Int,
    dateIndex: Int,
    timeKnownIndex: Int,
): MembershipBoundaryValue? {
    val instant = getNullableLong(instantIndex)?.let(Instant::ofEpochMilli) ?: return null
    val date = getNullableLong(dateIndex)?.let(LocalDate::ofEpochDay)
        ?: instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return MembershipBoundaryValue(
        date = date,
        instant = instant,
        timeKnown = getNullableInt(timeKnownIndex)?.let { it != 0 } ?: true,
    )
}

private fun Cursor.getNullableBoolean(index: Int): Boolean? =
    if (isNull(index)) null else getInt(index) != 0

private fun Cursor.getNullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)
