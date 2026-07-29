package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

class PlatoonDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

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
    }

    @Synchronized
    fun ingestSnapshot(
        snapshot: PlatoonSnapshot,
        source: EvidenceSource,
    ): IngestResult {
        require(snapshot.members.isNotEmpty()) { "A Platoon snapshot cannot be empty" }
        require(snapshot.members.map(SnapshotMember::uid).distinct().size == snapshot.members.size) {
            "A Platoon snapshot cannot contain duplicate UIDs"
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            if (snapshot.sourceFile != null && sourceFileExists(db, snapshot.sourceFile)) {
                return IngestResult.duplicate()
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

            if (!hasPriorSnapshot) {
                snapshot.members.forEach { member ->
                    insertTenure(
                        db = db,
                        uid = member.uid,
                        joinedAt = null,
                        precision = EvidencePrecision.UNKNOWN,
                        source = source,
                    )
                }
            } else {
                val changedNameCounts = (
                    changes.joined.map(SnapshotMember::name) +
                        changes.rejoined.map(SnapshotMember::name) +
                        changes.left.map(SnapshotReconciler.KnownMember::name)
                    ).groupingBy { it.lowercase() }.eachCount()
                val rosterNameUidCounts = (
                    known.map { it.uid to it.name } +
                        snapshot.members.map { it.uid to it.name }
                    ).groupBy { it.second.lowercase() }
                    .mapValues { (_, identities) -> identities.map { it.first }.distinct().size }
                changes.joined.forEach { member ->
                    val tenureId = insertTenure(
                        db,
                        member.uid,
                        snapshot.capturedAt,
                        EvidencePrecision.INFERRED,
                        source,
                    )
                    insertSnapshotBoundaryEvent(
                        db,
                        tenureId,
                        member.uid,
                        member.name,
                        MemberEventType.JOINED,
                        snapshot.capturedAt,
                        source,
                    )
                    correlateMembershipBoundary(
                        db = db,
                        tenureId = tenureId,
                        member = member,
                        type = MemberEventType.JOINED,
                        boundary = MembershipBoundary.JOIN,
                        observedAt = snapshot.capturedAt,
                        from = priorCapturedAt,
                        nameIsUnique = changedNameCounts[member.name.lowercase()] == 1 &&
                            rosterNameUidCounts[member.name.lowercase()] == 1,
                    )
                }
                changes.rejoined.forEach { member ->
                    val tenureId = insertTenure(
                        db,
                        member.uid,
                        snapshot.capturedAt,
                        EvidencePrecision.INFERRED,
                        source,
                    )
                    insertSnapshotBoundaryEvent(
                        db,
                        tenureId,
                        member.uid,
                        member.name,
                        MemberEventType.REJOINED,
                        snapshot.capturedAt,
                        source,
                    )
                    correlateMembershipBoundary(
                        db = db,
                        tenureId = tenureId,
                        member = member,
                        type = MemberEventType.REJOINED,
                        boundary = MembershipBoundary.JOIN,
                        observedAt = snapshot.capturedAt,
                        from = priorCapturedAt,
                        nameIsUnique = changedNameCounts[member.name.lowercase()] == 1 &&
                            rosterNameUidCounts[member.name.lowercase()] == 1,
                    )
                }
                changes.left.forEach { member ->
                    val tenureId = closeLatestTenure(
                        db,
                        member.uid,
                        snapshot.capturedAt,
                        EvidencePrecision.INFERRED,
                        source,
                    )
                    markInactive(db, member.uid, snapshot.capturedAt)
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
                        nameIsUnique = changedNameCounts[member.name.lowercase()] == 1 &&
                            rosterNameUidCounts[member.name.lowercase()] == 1,
                    )
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

            resolveUnresolvedActivityUids(db)
            db.setTransactionSuccessful()
            return IngestResult(
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
                    when (observation.kind) {
                        UPDATE_KIND_JOIN -> observation.members.forEach { member ->
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
                        UPDATE_KIND_WITHDRAW -> observation.members.lastOrNull()?.let { member ->
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
                        UPDATE_KIND_REMOVED -> observation.members.lastOrNull()?.let { member ->
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
                        UPDATE_KIND_DAILY_PATROL -> observation.members.forEach { member ->
                            val inserted = db.insertWithOnConflict(
                                "platoon_activity",
                                null,
                                ContentValues().apply {
                                    put("occurred_at", observation.occurredAt.toEpochMilli())
                                    put("action_id", DAILY_PATROL_ACTION_ID)
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
            SELECT id, tenure_id,
                   ABS(COALESCE(occurred_at, observed_at) - ?) AS distance
            FROM member_events
            WHERE uid = ?
              AND event_type IN (?, ?)
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
                        ),
                    )
                }
            }
        }
        val tenureId = candidates.firstNotNullOfOrNull(BoundaryEventCandidate::tenureId)
            ?: findOrCreateUpdateTenure(db, member.uid, boundary, occurredAt)

        candidates.forEach { candidate ->
            db.delete("member_events", "id = ?", arrayOf(candidate.id.toString()))
        }
        candidates.mapNotNull(BoundaryEventCandidate::tenureId)
            .filter { it != tenureId }
            .distinct()
            .forEach { candidateTenureId ->
                deleteShadowInferredTenure(db, candidateTenureId, boundary, occurredAt)
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

    private fun findOrCreateUpdateTenure(
        db: SQLiteDatabase,
        uid: Long,
        boundary: MembershipBoundary,
        occurredAt: Instant,
    ): Long {
        val boundaryColumn = if (boundary == MembershipBoundary.JOIN) "joined_at" else "left_at"
        val nearby = db.rawQuery(
            """
            SELECT id
            FROM tenures
            WHERE uid = ?
              AND (
                $boundaryColumn IS NULL
                OR ABS($boundaryColumn - ?) <= ?
              )
            ORDER BY
              CASE WHEN $boundaryColumn IS NULL THEN 1 ELSE 0 END,
              ABS(COALESCE($boundaryColumn, ?) - ?),
              id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                uid.toString(),
                occurredAt.toEpochMilli().toString(),
                EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
                occurredAt.toEpochMilli().toString(),
                occurredAt.toEpochMilli().toString(),
            ),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
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

    private fun deleteShadowInferredTenure(
        db: SQLiteDatabase,
        tenureId: Long,
        boundary: MembershipBoundary,
        occurredAt: Instant,
    ) {
        val boundaryColumn = if (boundary == MembershipBoundary.JOIN) "joined_at" else "left_at"
        val precisionColumn = if (boundary == MembershipBoundary.JOIN) {
            "joined_precision"
        } else {
            "left_precision"
        }
        val isShadow = db.rawQuery(
            """
            SELECT 1
            FROM tenures
            WHERE id = ?
              AND joined_at IS NULL
              AND $precisionColumn IN (?, ?)
              AND (
                $boundaryColumn IS NULL
                OR ABS($boundaryColumn - ?) <= ?
              )
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                tenureId.toString(),
                EvidencePrecision.INFERRED.name,
                EvidencePrecision.UNKNOWN.name,
                occurredAt.toEpochMilli().toString(),
                EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
            ),
        ).use(Cursor::moveToFirst)
        if (!isShadow) return
        db.delete("member_events", "tenure_id = ?", arrayOf(tenureId.toString()))
        db.delete("tenures", "id = ?", arrayOf(tenureId.toString()))
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
                DAILY_PATROL_ACTION_ID.toString(),
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
        joinedAt: Instant?,
        withdrewAt: Instant?,
        note: String,
    ): Boolean {
        require(uid > 0)
        require(name.isNotBlank())
        require(joinedAt == null || withdrewAt == null || !withdrewAt.isBefore(joinedAt))
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (memberExists(db, uid)) return false
            val firstSeen = joinedAt ?: withdrewAt ?: Instant.now()
            val lastSeen = withdrewAt ?: firstSeen
            db.insertOrThrow(
                "members",
                null,
                ContentValues().apply {
                    put("uid", uid)
                    put("current_name", name.trim())
                    put("custom_name", name.trim())
                    put("current_level", 0)
                    put("is_active", 0)
                    put("first_seen_at", firstSeen.toEpochMilli())
                    put("last_seen_at", lastSeen.toEpochMilli())
                    put("note", "")
                },
            )
            val tenureId = insertManualTenure(db, uid, joinedAt, withdrewAt, note)
            replaceManualTenureEvents(db, tenureId, uid, name.trim(), joinedAt, withdrewAt)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addTenure(
        uid: Long,
        joinedAt: Instant?,
        withdrewAt: Instant?,
        note: String,
    ): Boolean {
        require(joinedAt == null || withdrewAt == null || !withdrewAt.isBefore(joinedAt))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val memberName = memberName(db, uid) ?: return false
            val tenureId = insertManualTenure(db, uid, joinedAt, withdrewAt, note)
            replaceManualTenureEvents(db, tenureId, uid, memberName, joinedAt, withdrewAt)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun listSnapshots(limit: Int = 100): List<PlatoonSnapshot> {
        require(limit in 1..1000)
        val db = readableDatabase
        return db.query(
            "snapshots",
            SNAPSHOT_COLUMNS,
            null,
            null,
            null,
            null,
            "captured_at DESC, id DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(
                        PlatoonSnapshot(
                            id = id,
                            capturedAt = Instant.ofEpochMilli(cursor.getLong(1)),
                            sourceFile = cursor.getNullableString(2),
                            gameVersion = cursor.getNullableString(3),
                            members = readSnapshotMembers(db, id),
                        ),
                    )
                }
            }
        }
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
    fun listEvents(from: Instant, until: Instant): List<MemberEvent> {
        require(!until.isBefore(from))
        return readableDatabase.query(
            "member_events",
            EVENT_COLUMNS,
            "COALESCE(occurred_at, observed_at) >= CAST(? AS INTEGER) AND " +
                "COALESCE(occurred_at, observed_at) < CAST(? AS INTEGER)",
            arrayOf(from.toEpochMilli().toString(), until.toEpochMilli().toString()),
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
        joinedAt: Instant?,
        leftAt: Instant?,
        note: String,
    ): Boolean {
        require(joinedAt == null || leftAt == null || !leftAt.isBefore(joinedAt))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val tenure = readTenureForUpdate(db, tenureId) ?: return false
            if (tenure.joinedSource == EvidenceSource.GAME_UPDATES ||
                tenure.leftSource == EvidenceSource.GAME_UPDATES
            ) {
                return false
            }
            val updated = db.update(
                "tenures",
                ContentValues().apply {
                    putNullableLong("joined_at", joinedAt?.toEpochMilli())
                    putNullableLong("left_at", leftAt?.toEpochMilli())
                    put("joined_precision", EvidencePrecision.MANUAL.name)
                    put("joined_source", EvidenceSource.MANUAL.name)
                    if (leftAt == null) {
                        putNull("left_precision")
                        putNull("left_source")
                    } else {
                        put("left_precision", EvidencePrecision.MANUAL.name)
                        put("left_source", EvidenceSource.MANUAL.name)
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
                    joinedAt,
                    leftAt,
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
                member_event_id INTEGER REFERENCES member_events(id),
                UNIQUE(occurred_at, action_id, kind, member_name)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS platoon_activity_member_time " +
                "ON platoon_activity(member_name, occurred_at)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS platoon_activity_action_time " +
                "ON platoon_activity(action_id, occurred_at)",
        )
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
            arrayOf("id", "occurred_at", "member_name"),
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
                        ),
                    )
                }
            }
        }
        unresolved.forEach { activity ->
            val uid = resolveUidForName(db, activity.memberName, activity.occurredAt)
                ?: return@forEach
            db.update(
                "platoon_activity",
                ContentValues().apply {
                    put("resolved_uid", uid)
                    put("resolution", ActivityResolution.UNIQUE_ROSTER_NAME.name)
                },
                "id = ? AND resolved_uid IS NULL",
                arrayOf(activity.id.toString()),
            )
        }
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
            DAILY_PATROL_ACTION_ID.toString(),
            DAILY_PATROL_COMPANION_ACTION_ID.toString(),
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
                DAILY_PATROL_ACTION_ID.toString(),
                DAILY_PATROL_COMPANION_ACTION_ID.toString(),
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
                    put("joined_precision", EvidencePrecision.EXACT.name)
                    put("joined_source", EvidenceSource.GAME_UPDATES.name)
                }
                MembershipBoundary.WITHDRAW -> {
                    put("left_at", occurredAt.toEpochMilli())
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
        joinedAt: Instant?,
        withdrewAt: Instant?,
        note: String,
    ): Long = db.insertOrThrow(
        "tenures",
        null,
        ContentValues().apply {
            put("uid", uid)
            putNullableLong("joined_at", joinedAt?.toEpochMilli())
            putNullableLong("left_at", withdrewAt?.toEpochMilli())
            put("joined_precision", EvidencePrecision.MANUAL.name)
            put("joined_source", EvidenceSource.MANUAL.name)
            if (withdrewAt == null) {
                putNull("left_precision")
                putNull("left_source")
            } else {
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
        joinedAt: Instant?,
        withdrewAt: Instant?,
    ) {
        db.delete(
            "member_events",
            "tenure_id = ? AND source = ?",
            arrayOf(tenureId.toString(), EvidenceSource.MANUAL.name),
        )
        val observedAt = Instant.now()
        if (joinedAt != null) {
            insertEvent(
                db = db,
                uid = uid,
                type = if (hasEarlierTenure(db, uid, tenureId)) {
                    MemberEventType.REJOINED
                } else {
                    MemberEventType.JOINED
                },
                occurredAt = joinedAt,
                observedAt = observedAt,
                precision = EvidencePrecision.MANUAL,
                source = EvidenceSource.MANUAL,
                note = memberName,
                tenureId = tenureId,
            )
        }
        if (withdrewAt != null) {
            insertEvent(
                db = db,
                uid = uid,
                type = MemberEventType.LEFT,
                occurredAt = withdrewAt,
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
                tenure.joinedAt,
                tenure.withdrewAt,
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
            SELECT uid, joined_source, left_source
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
                    joinedSource = EvidenceSource.valueOf(cursor.getString(1)),
                    leftSource = cursor.getNullableString(2)?.let(EvidenceSource::valueOf),
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

    private fun readSnapshotMembers(db: SQLiteDatabase, snapshotId: Long): List<SnapshotMember> =
        db.query(
            "snapshot_members",
            SNAPSHOT_MEMBER_COLUMNS,
            "snapshot_id = ?",
            arrayOf(snapshotId.toString()),
            null,
            null,
            "name COLLATE NOCASE, uid",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SnapshotMember(
                            uid = cursor.getLong(0),
                            name = cursor.getString(1),
                            level = cursor.getLong(2),
                            weeklyMerit = cursor.getLong(3),
                            totalMerit = cursor.getLong(4),
                            highScore = cursor.getLong(5),
                            totalScore = cursor.getLong(6),
                            lastLogin = cursor.getLong(7),
                        ),
                    )
                }
            }
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
                            joinedPrecision = enumValueOf(cursor.getString(3)),
                            leftPrecision = cursor.getNullableString(4)
                                ?.let(EvidencePrecision::valueOf),
                            joinedSource = enumValueOf(cursor.getString(5)),
                            leftSource = cursor.getNullableString(6)
                                ?.let(EvidenceSource::valueOf),
                            note = cursor.getString(7),
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
        observedAt = Instant.ofEpochMilli(getLong(4)),
        precision = enumValueOf(getString(5)),
        source = enumValueOf(getString(6)),
        note = getString(7),
    )

    data class IngestResult(
        val snapshotId: Long?,
        val duplicate: Boolean,
        val initialRoster: Boolean,
        val joined: Int,
        val rejoined: Int,
        val left: Int,
        val renamed: Int,
    ) {
        companion object {
            fun duplicate() = IngestResult(null, true, false, 0, 0, 0, 0)
        }
    }

    data class ActivityIngestResult(
        val inserted: Int,
        val resolved: Int,
    )

    data class UpdatesIngestResult(
        val membershipEvents: Int,
        val patrolFacts: Int,
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
    )

    private data class TenureForUpdate(
        val uid: Long,
        val joinedSource: EvidenceSource,
        val leftSource: EvidenceSource?,
    )

    private data class ManualTenureBoundary(
        val tenureId: Long,
        val uid: Long,
        val joinedAt: Instant?,
        val withdrewAt: Instant?,
    )

    companion object {
        private const val DATABASE_NAME = "platoon.db"
        private const val DATABASE_VERSION = 7
        const val DAILY_PATROL_ACTION_ID = 802001L
        private const val DAILY_PATROL_COMPANION_ACTION_ID = 801005L
        private const val NAME_RESOLUTION_WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private const val MEMBERSHIP_CORRELATION_WINDOW_MILLIS = 12L * 60L * 60L * 1000L
        private const val EXACT_UPDATE_CORRELATION_WINDOW_MILLIS =
            48L * 60L * 60L * 1000L
        private const val UPDATE_KIND_JOIN = 3L
        private const val UPDATE_KIND_WITHDRAW = 4L
        private const val UPDATE_KIND_REMOVED = 5L
        private const val UPDATE_KIND_DAILY_PATROL = 8L
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

private fun Cursor.getNullableBoolean(index: Int): Boolean? =
    if (isNull(index)) null else getInt(index) != 0

private fun Cursor.getNullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)
