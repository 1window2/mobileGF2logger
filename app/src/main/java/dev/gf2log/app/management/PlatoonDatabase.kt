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

class PlatoonDatabase(
    context: Context,
    databaseName: String = PlatoonSchema.DATABASE_NAME,
) :
    SQLiteOpenHelper(
        context.applicationContext,
        databaseName,
        null,
        PlatoonSchema.CURRENT_VERSION,
    ) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            createPlatoonActivityRetentionIndex(db)
            trimPlatoonActivity(db)
        }
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
            CREATE TABLE membership_periods (
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
        db.execSQL("CREATE INDEX membership_periods_uid ON membership_periods(uid, id DESC)")
        db.execSQL(
            """
            CREATE TABLE member_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid INTEGER NOT NULL REFERENCES members(uid),
                membership_period_id INTEGER REFERENCES membership_periods(id),
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
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE tenures RENAME TO membership_periods")
            db.execSQL("DROP INDEX IF EXISTS tenures_uid")
            db.execSQL(
                "CREATE INDEX membership_periods_uid ON membership_periods(uid, id DESC)",
            )
            if (oldVersion >= 6) {
                migrateMembershipPeriodEventReference(
                    db = db,
                    hasCalendarColumns = oldVersion >= 9,
                )
            }
        }
        val needsManualCalendarDateBackfill = oldVersion < 9
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE membership_periods ADD COLUMN joined_date INTEGER")
            db.execSQL("ALTER TABLE membership_periods ADD COLUMN left_date INTEGER")
            db.execSQL(
                "ALTER TABLE membership_periods ADD COLUMN joined_time_known INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL("ALTER TABLE membership_periods ADD COLUMN left_time_known INTEGER")
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
            db.execSQL("ALTER TABLE member_events ADD COLUMN membership_period_id INTEGER REFERENCES membership_periods(id)")
            createPlatoonActivityTable(db)
            db.delete("weekly_notes", "is_automatic = 1", null)
            linkLegacyMembershipPeriodEvents(db)
        }
        if (oldVersion < 7) {
            backfillSnapshotMembershipPeriodEvents(db)
        }
        if (needsManualCalendarDateBackfill) {
            backfillManualCalendarDates(db, ZoneId.systemDefault())
        }
    }

    // Function Name: migrateMembershipPeriodEventReference
    // Description:
    // - Rebuilds the membership-event table with the clearer membership-period identifier.
    // - Rebuilds dependent tables so every foreign key targets the replacement event table.
    // Parameters:
    // - db: Database being upgraded inside SQLiteOpenHelper's transaction.
    // - hasCalendarColumns: Whether the source event table already contains schema-v9 dates.
    // Returns:
    // - Returns after all rows, IDs, and foreign-key relationships have been preserved.
    private fun migrateMembershipPeriodEventReference(
        db: SQLiteDatabase,
        hasCalendarColumns: Boolean,
    ) {
        db.execSQL("ALTER TABLE member_events RENAME TO member_events_legacy_v10")
        db.execSQL("ALTER TABLE weekly_notes RENAME TO weekly_notes_legacy_v10")
        db.execSQL("ALTER TABLE platoon_activity RENAME TO platoon_activity_legacy_v10")
        db.execSQL("DROP INDEX IF EXISTS member_events_time")
        db.execSQL("DROP INDEX IF EXISTS platoon_activity_member_time")
        db.execSQL("DROP INDEX IF EXISTS platoon_activity_action_time")
        db.execSQL("DROP INDEX IF EXISTS platoon_activity_exact_identity")
        db.execSQL("DROP INDEX IF EXISTS platoon_activity_unresolved_identity")

        val calendarColumns = if (hasCalendarColumns) {
            "event_date INTEGER, time_known INTEGER NOT NULL DEFAULT 1,"
        } else {
            ""
        }
        db.execSQL(
            """
            CREATE TABLE member_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid INTEGER NOT NULL REFERENCES members(uid),
                membership_period_id INTEGER REFERENCES membership_periods(id),
                event_type TEXT NOT NULL,
                occurred_at INTEGER,
                $calendarColumns
                observed_at INTEGER NOT NULL,
                precision TEXT NOT NULL,
                source TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        val calendarNames = if (hasCalendarColumns) ", event_date, time_known" else ""
        db.execSQL(
            """
            INSERT INTO member_events(
                id, uid, membership_period_id, event_type, occurred_at$calendarNames,
                observed_at, precision, source, note
            )
            SELECT id, uid, tenure_id, event_type, occurred_at$calendarNames,
                   observed_at, precision, source, note
            FROM member_events_legacy_v10
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX member_events_time ON member_events(observed_at DESC)")

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
        db.execSQL(
            """
            INSERT INTO weekly_notes(id, period_start, game_day, text, event_id, is_automatic)
            SELECT id, period_start, game_day, text, event_id, is_automatic
            FROM weekly_notes_legacy_v10
            """.trimIndent(),
        )

        createPlatoonActivityTable(db)
        db.execSQL(
            """
            INSERT OR IGNORE INTO platoon_activity(
                id, occurred_at, action_id, kind, member_name, captured_at,
                resolved_uid, resolution, member_event_id
            )
            SELECT id, occurred_at, action_id, kind, member_name, captured_at,
                   resolved_uid, resolution, member_event_id
            FROM platoon_activity_legacy_v10
            ORDER BY CASE WHEN member_event_id IS NULL THEN 1 ELSE 0 END, id
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE weekly_notes_legacy_v10")
        db.execSQL("DROP TABLE platoon_activity_legacy_v10")
        db.execSQL("DROP TABLE member_events_legacy_v10")
    }

    @Synchronized
    fun ingestSnapshot(
        snapshot: PlatoonSnapshot,
        source: EvidenceSource,
        historicalOnly: Boolean = false,
        deferHistoricalMembershipReconciliation: Boolean = false,
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
            if (historicalOnly) {
                require(priorCapturedAt != null && !snapshot.capturedAt.isAfter(priorCapturedAt)) {
                    "Historical snapshots must not be newer than current structured data"
                }
                val snapshotId = insertSnapshotRows(db, snapshot)
                if (!deferHistoricalMembershipReconciliation) {
                    reconcileSnapshotMembershipHistory(db)
                }
                db.setTransactionSuccessful()
                return SnapshotIngestResult.historical(snapshotId)
            }
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
                    val exactMembershipPeriodId = findRosterConfirmedExactMembershipPeriod(
                        db = db,
                        uid = member.uid,
                        boundary = MembershipBoundary.WITHDRAW,
                        from = priorCapturedAt,
                        observedAt = snapshot.capturedAt,
                    )
                    val membershipPeriodId = exactMembershipPeriodId ?: closeLatestMembershipPeriod(
                        db = db,
                        uid = member.uid,
                        leftAt = snapshot.capturedAt,
                        precision = EvidencePrecision.INFERRED,
                        source = source,
                    )
                    markInactive(db, member.uid, snapshot.capturedAt)
                    if (exactMembershipPeriodId == null) {
                        insertSnapshotBoundaryEvent(
                            db,
                            membershipPeriodId,
                            member.uid,
                            member.name,
                            MemberEventType.LEFT,
                            snapshot.capturedAt,
                            source,
                        )
                        correlateMembershipBoundary(
                            db = db,
                            membershipPeriodId = membershipPeriodId,
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
            // membershipPeriod left by an incomplete historical Updates feed.
            snapshot.members.forEach { member ->
                ensureRosterActiveMembershipPeriod(db, member.uid, source)
            }

            trimPlatoonActivity(db)
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

    // Function Name: reconcileSnapshotMembershipHistory
    // Description:
    // - Replays every retained roster in chronological order after late historical imports.
    // - Rebuilds only weak snapshot-derived membership periods and preserves manual or exact evidence.
    // - Creates inactive member records for people found only in historical rosters.
    // Parameters:
    // - None.
    // Returns:
    // - Unit after atomically reconciling member identities and membership periods.
    @Synchronized
    fun reconcileSnapshotMembershipHistory() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            reconcileSnapshotMembershipHistory(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun reconcileSnapshotMembershipHistory(db: SQLiteDatabase) {
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
        resolveUnresolvedActivityUids(db)
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
        val latestFrame = snapshots.last()
        snapshots.flatMap { frame ->
            frame.members.map { (uid, identity) -> Triple(uid, identity, frame.capturedAt) }
        }.groupBy { it.first }.forEach { (uid, observations) ->
            val firstSeenAt = observations.minOf { it.third }
            val latest = observations.maxBy { it.third }
            if (!memberExists(db, uid)) {
                db.insertOrThrow(
                    "members",
                    null,
                    ContentValues().apply {
                        put("uid", uid)
                        put("current_name", latest.second.name)
                        put("current_level", latest.second.level)
                        put("is_active", if (uid in latestFrame.members) 1 else 0)
                        put("first_seen_at", firstSeenAt.toEpochMilli())
                        put("last_seen_at", latest.third.toEpochMilli())
                        put("note", "")
                    },
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
            db.delete(
                "weekly_notes",
                "is_automatic = 1 AND event_id IN (SELECT id FROM member_events WHERE membership_period_id = ?)",
                arrayOf(membershipPeriodId.toString()),
            )
            db.update(
                "weekly_notes",
                ContentValues().apply { putNull("event_id") },
                "is_automatic = 0 AND event_id IN (SELECT id FROM member_events WHERE membership_period_id = ?)",
                arrayOf(membershipPeriodId.toString()),
            )
            db.update(
                "platoon_activity",
                ContentValues().apply { putNull("member_event_id") },
                "member_event_id IN (SELECT id FROM member_events WHERE membership_period_id = ?)",
                arrayOf(membershipPeriodId.toString()),
            )
            db.delete(
                "member_events",
                "membership_period_id = ?",
                arrayOf(membershipPeriodId.toString()),
            )
            db.delete("membership_periods", "id = ?", arrayOf(membershipPeriodId.toString()))
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

    // Function Name: reconcilePreservedMembershipPeriodOverlap
    // Description:
    // - Preserves exact, manual, or user-annotated membership periods during roster replay.
    // - Closes every overlapping open preserved period at an observed roster absence.
    // - Allows a later roster-presence run to become a separate inferred rejoin period.
    // Parameters:
    // - db: Writable database participating in the replay transaction.
    // - uid: Stable member identity whose roster run is being reconciled.
    // - run: One contiguous roster-presence run bounded by observed absence when known.
    // Returns:
    // - True when a preserved period covers this run; false when a new weak run is required.
    private fun reconcilePreservedMembershipPeriodOverlap(
        db: SQLiteDatabase,
        uid: Long,
        run: RosterPresenceRun,
    ): Boolean {
        val overlaps = db.rawQuery(
            """
            SELECT id, left_at, left_precision, left_source
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
                            leftAt = cursor.getNullableLong(1)?.let(Instant::ofEpochMilli),
                            leftPrecision = cursor.getNullableString(2)
                                ?.let(EvidencePrecision::valueOf),
                            leftSource = cursor.getNullableString(3)
                                ?.let(EvidenceSource::valueOf),
                        ),
                    )
                }
            }
        }
        val observedLeftAt = run.leftAt
        if (observedLeftAt != null) {
            overlaps.filter { membershipPeriod ->
                membershipPeriod.leftAt == null ||
                    (
                        membershipPeriod.leftPrecision == EvidencePrecision.INFERRED &&
                            membershipPeriod.leftSource in SNAPSHOT_EVENT_SOURCES &&
                            observedLeftAt.isBefore(requireNotNull(membershipPeriod.leftAt))
                        )
            }.forEach { membershipPeriod ->
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
                    val eventUpdated = db.update(
                        "member_events",
                        ContentValues().apply {
                            putNull("occurred_at")
                            putNull("event_date")
                            put("time_known", 1)
                            put("observed_at", observedLeftAt.toEpochMilli())
                            put("precision", EvidencePrecision.INFERRED.name)
                            put("source", EvidenceSource.LEGACY_IMPORT.name)
                            put("note", run.name)
                        },
                        "membership_period_id = ? AND event_type = ? " +
                            "AND source IN (?, ?)",
                        arrayOf(
                            membershipPeriod.id.toString(),
                            MemberEventType.LEFT.name,
                            EvidenceSource.SNAPSHOT.name,
                            EvidenceSource.LEGACY_IMPORT.name,
                        ),
                    )
                    if (eventUpdated == 0) {
                        insertSnapshotBoundaryEvent(
                            db = db,
                            membershipPeriodId = membershipPeriod.id,
                            uid = uid,
                            memberName = run.name,
                            type = MemberEventType.LEFT,
                            observedAt = observedLeftAt,
                            source = EvidenceSource.LEGACY_IMPORT,
                        )
                    }
                }
            }
        }
        return overlaps.isNotEmpty()
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
                if (hasEarlierMembershipPeriod(db, uid, membershipPeriodId)) {
                    MemberEventType.REJOINED
                } else {
                    MemberEventType.JOINED
                },
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

    @Synchronized
    fun latestSnapshotIdentity(): Pair<Instant, String?>? = readableDatabase.rawQuery(
        "SELECT captured_at, source_file FROM snapshots " +
            "ORDER BY captured_at DESC, source_file DESC, id DESC LIMIT 1",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            Instant.ofEpochMilli(cursor.getLong(0)) to cursor.getNullableString(1)
        } else {
            null
        }
    }

    @Synchronized
    fun snapshotSourceFiles(): Set<String> = readableDatabase.rawQuery(
        "SELECT source_file FROM snapshots WHERE source_file IS NOT NULL",
        null,
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun insertSnapshotRows(db: SQLiteDatabase, snapshot: PlatoonSnapshot): Long {
        val snapshotId = db.insertOrThrow(
            "snapshots",
            null,
            ContentValues().apply {
                put("captured_at", snapshot.capturedAt.toEpochMilli())
                put("source_file", snapshot.sourceFile)
                put("game_version", snapshot.gameVersion)
            },
        )
        snapshot.members.forEach { member -> insertSnapshotMember(db, snapshotId, member) }
        return snapshotId
    }

    @Synchronized
    fun ingestPlatoonActivity(
        observations: List<PlatoonActivityObservation>,
        capturedAt: Instant,
    ): ActivityIngestResult {
        val acceptedObservations = PlatoonObservationPolicy.activity(observations)
        if (acceptedObservations.isEmpty()) return ActivityIngestResult(0, 0, 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
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
            db.setTransactionSuccessful()
            return ActivityIngestResult(acceptedObservations.size, inserted, resolved)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun ingestPlatoonUpdates(
        observations: List<PlatoonUpdateObservation>,
        capturedAt: Instant,
    ): UpdatesIngestResult {
        val acceptedObservations = PlatoonObservationPolicy.updates(observations)
        if (acceptedObservations.isEmpty()) return UpdatesIngestResult(0, 0, 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
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
                        synchronizeCurrentMembershipFromUpdate(
                            db,
                            member.uid,
                        )
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
                        synchronizeCurrentMembershipFromUpdate(
                            db,
                            member.uid,
                        )
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
                        synchronizeCurrentMembershipFromUpdate(
                            db,
                            member.uid,
                        )
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
            return UpdatesIngestResult(acceptedObservations.size, membershipEvents, patrolFacts)
        } finally {
            db.endTransaction()
        }
    }

    private fun synchronizeCurrentMembershipFromUpdate(
        db: SQLiteDatabase,
        uid: Long,
    ) {
        val latestSnapshotAt = latestSnapshotCapturedAt(db)
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
            SELECT event_type, note, occurred_at
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
                type = MemberEventType.valueOf(cursor.getString(0)),
                name = cursor.getString(1),
                occurredAt = Instant.ofEpochMilli(cursor.getLong(2)),
            )
        }
        db.update(
            "members",
            ContentValues().apply {
                put("current_name", latestBoundary.name)
                put(
                    "is_active",
                    if (latestBoundary.type in setOf(
                            MemberEventType.JOINED,
                            MemberEventType.REJOINED,
                        )
                    ) {
                        1
                    } else {
                        0
                    },
                )
                put("last_seen_at", latestBoundary.occurredAt.toEpochMilli())
            },
            "uid = ?",
            arrayOf(uid.toString()),
        )
    }

    private data class LatestMembershipBoundary(
        val type: MemberEventType,
        val name: String,
        val occurredAt: Instant,
    )

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
        val exactMembershipPeriodId = findRosterConfirmedExactMembershipPeriod(
            db = db,
            uid = member.uid,
            boundary = MembershipBoundary.JOIN,
            from = priorCapturedAt,
            observedAt = observedAt,
        )
        if (exactMembershipPeriodId != null) return

        val membershipPeriodId = insertMembershipPeriod(
            db = db,
            uid = member.uid,
            joinedAt = observedAt,
            precision = EvidencePrecision.INFERRED,
            source = source,
        )
        insertSnapshotBoundaryEvent(
            db = db,
            membershipPeriodId = membershipPeriodId,
            uid = member.uid,
            memberName = member.name,
            type = type,
            observedAt = observedAt,
            source = source,
        )
        correlateMembershipBoundary(
            db = db,
            membershipPeriodId = membershipPeriodId,
            member = member,
            type = type,
            boundary = MembershipBoundary.JOIN,
            observedAt = observedAt,
            from = priorCapturedAt,
            nameIsUnique = nameIsUnique,
        )
    }

    private fun findRosterConfirmedExactMembershipPeriod(
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
            SELECT id, membership_period_id, COALESCE(occurred_at, observed_at),
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
            db.delete("member_events", "id = ?", arrayOf(candidate.id.toString()))
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
            type = if (boundary == MembershipBoundary.JOIN &&
                hasEarlierMembershipPeriod(db, member.uid, membershipPeriodId)
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
        if (boundary == MembershipBoundary.WITHDRAW &&
            MembershipChronology.shouldRestoreRosterActiveMembershipPeriod(
                memberIsActive = isActiveMember(db, member.uid),
                withdrawalPredatesRoster = hasRosterPresenceAfter(db, member.uid, occurredAt),
            )
        ) {
            /*
             * The historical boundary and a later authoritative roster are
             * both true. Close the compatible historical membershipPeriod above, then
             * preserve exactly one unknown current membershipPeriod for the later roster.
             * A subsequent exact rejoin in the replay will refine this membershipPeriod.
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
                FROM membership_periods
                WHERE uid = ?
                  AND (joined_at IS NULL OR joined_at <= ?)
                  AND (
                    left_at IS NULL
                    OR (
                      left_precision != ?
                      AND ABS(left_at - ?) <= ?
                    )
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
                    occurredAtMillis.toString(),
                    EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
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

    private fun mergeShadowInferredMembershipPeriod(
        db: SQLiteDatabase,
        selectedMembershipPeriodId: Long,
        shadowMembershipPeriodId: Long,
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
            FROM membership_periods
            WHERE id = ?
              AND $precisionColumn IN (?, ?)
              AND (
                $boundaryColumn IS NULL
                OR ABS($boundaryColumn - ?) <= ?
              )
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                shadowMembershipPeriodId.toString(),
                EvidencePrecision.INFERRED.name,
                EvidencePrecision.UNKNOWN.name,
                occurredAt.toEpochMilli().toString(),
                EXACT_UPDATE_CORRELATION_WINDOW_MILLIS.toString(),
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ShadowMembershipPeriod(
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
                "SELECT ${oppositePrefix}_at FROM membership_periods WHERE id = ?",
                arrayOf(selectedMembershipPeriodId.toString()),
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
                "membership_periods",
                values,
                "id = ?",
                arrayOf(selectedMembershipPeriodId.toString()),
            )
            val oppositeTypes = if (boundary == MembershipBoundary.JOIN) {
                listOf(MemberEventType.LEFT, MemberEventType.REMOVED)
            } else {
                listOf(MemberEventType.JOINED, MemberEventType.REJOINED)
            }
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
        db.delete("member_events", "membership_period_id = ?", arrayOf(shadowMembershipPeriodId.toString()))
        db.delete("membership_periods", "id = ?", arrayOf(shadowMembershipPeriodId.toString()))
    }

    @Synchronized
    fun listDailyPatrolFacts(from: Instant, until: Instant): List<DailyPatrolFact> {
        require(!until.isBefore(from))
        return readableDatabase.query(
            true,
            "platoon_activity",
            arrayOf("resolved_uid", "occurred_at"),
            """
                action_id = ? AND resolved_uid IS NOT NULL
                AND resolution = ? AND occurred_at >= ? AND occurred_at < ?
            """.trimIndent(),
            arrayOf(
                DAILY_PATROL_REWARD_ACTION_ID.toString(),
                ActivityResolution.EXACT_UPDATE.name,
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
            val membershipPeriodId = insertManualMembershipPeriod(db, uid, joined, withdrew, note)
            replaceManualMembershipPeriodEvents(db, membershipPeriodId, uid, name.trim(), joined, withdrew)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addMembershipPeriod(
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
            val membershipPeriodId = insertManualMembershipPeriod(db, uid, joined, withdrew, note)
            replaceManualMembershipPeriodEvents(db, membershipPeriodId, uid, memberName, joined, withdrew)
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
                WHERE (captured_at >= ? AND captured_at <= ?)
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
                            membershipPeriods = readMembershipPeriods(db, uid),
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
    fun deleteMember(uid: Long): Boolean {
        require(uid > 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
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
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    // Function Name: deleteMembershipPeriod
    // Description:
    // - Deletes one membership period and its derived boundary events atomically.
    // - Refuses to remove the member's final period and recomputes current active state.
    // Parameters:
    // - membershipPeriodId: Persistent period identifier selected in member details.
    // Returns:
    // - True when the period was deleted; false when it was missing or was the only period.
    @Synchronized
    fun deleteMembershipPeriod(membershipPeriodId: Long): Boolean {
        require(membershipPeriodId > 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
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

            db.delete(
                "weekly_notes",
                "is_automatic = 1 AND event_id IN " +
                    "(SELECT id FROM member_events WHERE membership_period_id = ?)",
                arrayOf(membershipPeriodId.toString()),
            )
            db.update(
                "weekly_notes",
                ContentValues().apply { putNull("event_id") },
                "event_id IN " +
                    "(SELECT id FROM member_events WHERE membership_period_id = ?)",
                arrayOf(membershipPeriodId.toString()),
            )
            db.update(
                "platoon_activity",
                ContentValues().apply { putNull("member_event_id") },
                "member_event_id IN " +
                    "(SELECT id FROM member_events WHERE membership_period_id = ?)",
                arrayOf(membershipPeriodId.toString()),
            )
            db.delete(
                "member_events",
                "membership_period_id = ?",
                arrayOf(membershipPeriodId.toString()),
            )
            if (
                db.delete(
                    "membership_periods",
                    "id = ? AND uid = ?",
                    arrayOf(membershipPeriodId.toString(), uid.toString()),
                ) != 1
            ) {
                return false
            }
            val isActive = db.rawQuery(
                "SELECT 1 FROM membership_periods WHERE uid = ? AND left_at IS NULL LIMIT 1",
                arrayOf(uid.toString()),
            ).use(Cursor::moveToFirst)
            db.update(
                "members",
                ContentValues().apply { put("is_active", if (isActive) 1 else 0) },
                "uid = ?",
                arrayOf(uid.toString()),
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateMembershipPeriod(
        membershipPeriodId: Long,
        joined: MembershipBoundaryValue,
        left: MembershipBoundaryValue?,
        note: String,
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val membershipPeriod = readMembershipPeriodForUpdate(db, membershipPeriodId) ?: return false
            val effectiveJoined = if (membershipPeriod.joinedSource.isImmutableMembershipBoundary()) {
                membershipPeriod.joined
            } else {
                joined
            } ?: return false
            val effectiveLeft = if (membershipPeriod.leftSource?.isImmutableMembershipBoundary() == true) {
                membershipPeriod.left
            } else {
                left
            }
            require(isValidMembershipRange(effectiveJoined, effectiveLeft))
            val joinedChanged = editableMembershipBoundaryChanged(
                original = membershipPeriod.joined,
                requested = joined,
                source = membershipPeriod.joinedSource,
            )
            val leftChanged = editableMembershipBoundaryChanged(
                original = membershipPeriod.left,
                requested = left,
                source = membershipPeriod.leftSource,
            )
            val resultingJoinedSource = if (joinedChanged) {
                EvidenceSource.MANUAL
            } else {
                membershipPeriod.joinedSource
            }
            val resultingLeftSource = if (leftChanged) {
                left?.let { EvidenceSource.MANUAL }
            } else {
                membershipPeriod.leftSource
            }
            val updated = db.update(
                "membership_periods",
                ContentValues().apply {
                    if (joinedChanged) {
                        putMembershipBoundary("joined", joined)
                        put("joined_precision", EvidencePrecision.MANUAL.name)
                        put("joined_source", EvidenceSource.MANUAL.name)
                    }
                    if (leftChanged) {
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
                arrayOf(membershipPeriodId.toString()),
            ) == 1
            if (updated && (joinedChanged || leftChanged)) {
                replaceManualMembershipPeriodEvents(
                    db,
                    membershipPeriodId,
                    membershipPeriod.uid,
                    memberName(db, membershipPeriod.uid).orEmpty(),
                    effectiveJoined.takeIf {
                        resultingJoinedSource == EvidenceSource.MANUAL
                    },
                    effectiveLeft.takeIf {
                        resultingLeftSource == EvidenceSource.MANUAL
                    },
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
    fun listWeeklyEvidenceDays(zoneId: ZoneId): List<LocalDate> {
        val db = readableDatabase
        val days = mutableListOf<LocalDate>()
        listOf(
            "snapshots" to "captured_at",
            "platoon_activity" to "occurred_at",
        ).forEach { (table, expression) ->
            db.rawQuery("SELECT DISTINCT $expression FROM $table", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) {
                        days += PlatoonPeriods.gameDay(Instant.ofEpochMilli(cursor.getLong(0)), zoneId)
                    }
                }
            }
        }
        db.rawQuery(
            "SELECT DISTINCT COALESCE(occurred_at, observed_at) " +
                "FROM member_events WHERE event_date IS NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) {
                    days += PlatoonPeriods.gameDay(Instant.ofEpochMilli(cursor.getLong(0)), zoneId)
                }
            }
        }
        listOf("weekly_notes", "weekly_overrides").forEach { table ->
            db.rawQuery("SELECT DISTINCT period_start FROM $table", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(0)) days += LocalDate.ofEpochDay(cursor.getLong(0))
                }
            }
        }
        db.rawQuery("SELECT DISTINCT event_date FROM member_events", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) days += LocalDate.ofEpochDay(cursor.getLong(0))
            }
        }
        return days.distinct()
    }

    @Synchronized
    fun replaceWeeklyOverrides(
        periodStartEpochDay: Long,
        overrides: List<WeeklyCellOverride>,
    ) {
        require(overrides.all { it.periodStart.toEpochDay() == periodStartEpochDay })
        require(
            overrides.all {
                it.attempts == null || it.attempts in 0..ActivityInference.MAX_DAILY_ATTEMPTS
            },
        )
        require(overrides.none { it.attended == false && it.dailyPatrol == true })
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
        createPlatoonActivityRetentionIndex(db)
    }

    private fun createPlatoonActivityRetentionIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS platoon_activity_resolution_retention " +
                "ON platoon_activity(resolved_uid, captured_at DESC, id DESC)",
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
            arrayOf("id", "occurred_at", "member_name", "action_id", "kind"),
            "resolved_uid IS NULL",
            null,
            null,
            null,
            "captured_at DESC, id DESC",
            MAX_UNRESOLVED_ACTIVITY_RESOLUTIONS.toString(),
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

    // Function Name: trimPlatoonActivity
    // Description:
    // - Caps cumulative network-derived activity evidence to a bounded newest-first history.
    // - Runs on database open and after each ingest so an older oversized database self-heals.
    // Parameters:
    // - db: Writable database participating in the caller's transaction when applicable.
    // Returns:
    // - Unit after deleting only rows beyond the retained observation limit.
    private fun trimPlatoonActivity(db: SQLiteDatabase) {
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

    private fun ensureRosterActiveMembershipPeriod(
        db: SQLiteDatabase,
        uid: Long,
        source: EvidenceSource,
    ) {
        val hasOpenMembershipPeriod = db.rawQuery(
            "SELECT 1 FROM membership_periods WHERE uid = ? AND left_at IS NULL LIMIT 1",
            arrayOf(uid.toString()),
        ).use(Cursor::moveToFirst)
        if (hasOpenMembershipPeriod) return
        insertMembershipPeriod(
            db = db,
            uid = uid,
            joinedAt = null,
            precision = EvidencePrecision.UNKNOWN,
            source = source,
        )
    }

    private fun correlateMembershipBoundary(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
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
            membershipPeriodId = membershipPeriodId,
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
                    if (hasEarlierMembershipPeriod(db, boundary.uid, boundary.membershipPeriodId)) {
                        MemberEventType.REJOINED
                    } else {
                        MemberEventType.JOINED
                    }
                }
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
        }
    }

    private fun readNearbyInferredBoundaries(
        db: SQLiteDatabase,
        activity: ResolvedActivityCandidate,
    ): List<InferredBoundary> = db.rawQuery(
        """
        SELECT id, uid, joined_at, left_at, joined_precision, left_precision
        FROM membership_periods
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
                val membershipPeriodId = cursor.getLong(0)
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
                            membershipPeriodId,
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
                            membershipPeriodId,
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
        membershipPeriodId: Long,
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
        db.update("membership_periods", values, "id = ?", arrayOf(membershipPeriodId.toString()))
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
            "membership_period_id = ? AND source IN (?, ?) AND event_type IN (?, ?)",
            arrayOf(
                membershipPeriodId.toString(),
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
        if (joined != null) {
            insertEvent(
                db = db,
                uid = uid,
                type = if (hasEarlierMembershipPeriod(db, uid, membershipPeriodId)) {
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
                membershipPeriodId = membershipPeriodId,
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
                membershipPeriodId = membershipPeriodId,
            )
        }
    }

    private fun linkLegacyMembershipPeriodEvents(db: SQLiteDatabase) {
        db.rawQuery(
            """
            SELECT t.id, t.uid, t.joined_at, t.left_at,
                   t.joined_precision, t.left_precision,
                   t.joined_source, t.left_source,
                   COALESCE(m.custom_name, m.current_name)
            FROM membership_periods t
            JOIN members m ON m.uid = t.uid
            ORDER BY t.uid, t.id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val membershipPeriodId = cursor.getLong(0)
                val uid = cursor.getLong(1)
                val joinedAt = cursor.getNullableLong(2)
                val leftAt = cursor.getNullableLong(3)
                val joinedPrecision = EvidencePrecision.valueOf(cursor.getString(4))
                val leftPrecision = cursor.getNullableString(5)?.let(EvidencePrecision::valueOf)
                val joinedSource = EvidenceSource.valueOf(cursor.getString(6))
                val leftSource = cursor.getNullableString(7)?.let(EvidenceSource::valueOf)
                val memberName = cursor.getString(8)
                if (joinedAt != null) {
                    val expectedType = if (hasEarlierMembershipPeriod(db, uid, membershipPeriodId)) {
                        MemberEventType.REJOINED
                    } else {
                        MemberEventType.JOINED
                    }
                    val linked = linkLegacyBoundaryEvent(
                        db = db,
                        membershipPeriodId = membershipPeriodId,
                        uid = uid,
                        occurredAt = joinedAt,
                        precision = joinedPrecision,
                        source = joinedSource,
                        expectedType = expectedType,
                    )
                    if (!linked && joinedSource == EvidenceSource.MANUAL) {
                        insertLegacyManualBoundaryEvent(
                            db,
                            membershipPeriodId,
                            uid,
                            memberName,
                            expectedType,
                            joinedAt,
                            joinedPrecision,
                        )
                    }
                }
                if (leftAt != null && leftPrecision != null && leftSource != null) {
                    val linked = linkLegacyBoundaryEvent(
                        db = db,
                        membershipPeriodId = membershipPeriodId,
                        uid = uid,
                        occurredAt = leftAt,
                        precision = leftPrecision,
                        source = leftSource,
                        expectedType = MemberEventType.LEFT,
                    )
                    if (!linked && leftSource == EvidenceSource.MANUAL) {
                        insertLegacyManualBoundaryEvent(
                            db,
                            membershipPeriodId,
                            uid,
                            memberName,
                            MemberEventType.LEFT,
                            leftAt,
                            leftPrecision,
                        )
                    }
                }
            }
        }
    }

    private fun linkLegacyBoundaryEvent(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        uid: Long,
        occurredAt: Long,
        precision: EvidencePrecision,
        source: EvidenceSource,
        expectedType: MemberEventType,
    ): Boolean {
        val candidates = db.rawQuery(
            """
            SELECT id, event_type, occurred_at, observed_at, source
            FROM member_events
            WHERE membership_period_id IS NULL
              AND uid = ?
              AND source = ?
              AND precision = ?
            ORDER BY id
            """.trimIndent(),
            arrayOf(
                uid.toString(),
                source.name,
                precision.name,
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LegacyEventCandidate(
                            id = cursor.getLong(0),
                            type = MemberEventType.valueOf(cursor.getString(1)),
                            occurredAt = cursor.getNullableLong(2),
                            observedAt = cursor.getLong(3),
                            source = EvidenceSource.valueOf(cursor.getString(4)),
                        ),
                    )
                }
            }
        }
        val eventId = LegacyEventMigrationPolicy.selectCandidate(
            expectedType,
            occurredAt,
            candidates,
        )
            ?: return false
        db.update(
            "member_events",
            ContentValues().apply { put("membership_period_id", membershipPeriodId) },
            "id = ?",
            arrayOf(eventId.toString()),
        )
        return true
    }

    private fun insertLegacyManualBoundaryEvent(
        db: SQLiteDatabase,
        membershipPeriodId: Long,
        uid: Long,
        memberName: String,
        type: MemberEventType,
        occurredAt: Long,
        precision: EvidencePrecision,
    ) {
        val boundary = Instant.ofEpochMilli(occurredAt)
        insertEvent(
            db = db,
            uid = uid,
            type = type,
            occurredAt = boundary,
            observedAt = boundary,
            precision = precision,
            source = EvidenceSource.MANUAL,
            note = memberName,
            membershipPeriodId = membershipPeriodId,
        )
    }

    private fun backfillManualCalendarDates(db: SQLiteDatabase, zoneId: ZoneId) {
        val membershipPeriods = db.rawQuery(
            """
            SELECT id, joined_at, left_at, joined_source, left_source
            FROM membership_periods
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
        membershipPeriods.forEach { membershipPeriod ->
            db.update(
                "membership_periods",
                ContentValues().apply {
                    if (membershipPeriod.joinedIsManual && membershipPeriod.joinedAtMillis != null) {
                        put(
                            "joined_date",
                            migrationCalendarDate(membershipPeriod.joinedAtMillis, zoneId).toEpochDay(),
                        )
                        put("joined_time_known", 1)
                    }
                    if (membershipPeriod.leftIsManual && membershipPeriod.leftAtMillis != null) {
                        put(
                            "left_date",
                            migrationCalendarDate(membershipPeriod.leftAtMillis, zoneId).toEpochDay(),
                        )
                        put("left_time_known", 1)
                    }
                },
                "id = ?",
                arrayOf(membershipPeriod.id.toString()),
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

    private fun backfillSnapshotMembershipPeriodEvents(db: SQLiteDatabase) {
        db.rawQuery(
            """
            SELECT t.id, t.uid, t.joined_at, t.left_at, t.joined_source, t.left_source,
                   COALESCE(m.custom_name, m.current_name)
            FROM membership_periods t
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
                val membershipPeriodId = cursor.getLong(0)
                val uid = cursor.getLong(1)
                val joinedAt = cursor.getNullableLong(2)?.let(Instant::ofEpochMilli)
                val leftAt = cursor.getNullableLong(3)?.let(Instant::ofEpochMilli)
                val joinedSource = EvidenceSource.valueOf(cursor.getString(4))
                val leftSource = cursor.getNullableString(5)?.let(EvidenceSource::valueOf)
                val name = cursor.getString(6)
                if (joinedAt != null && joinedSource in SNAPSHOT_EVENT_SOURCES) {
                    insertSnapshotBoundaryEvent(
                        db,
                        membershipPeriodId,
                        uid,
                        name,
                        if (hasEarlierMembershipPeriod(db, uid, membershipPeriodId)) {
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
                        membershipPeriodId,
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
        membershipPeriodId: Long,
        uid: Long,
        memberName: String,
        type: MemberEventType,
        observedAt: Instant,
        source: EvidenceSource,
    ) {
        val compatibleTypes = when (type) {
            MemberEventType.JOINED,
            MemberEventType.REJOINED,
            -> arrayOf(MemberEventType.JOINED.name, MemberEventType.REJOINED.name)
            else -> arrayOf(type.name, type.name)
        }
        val exists = db.rawQuery(
            "SELECT 1 FROM member_events " +
                "WHERE membership_period_id = ? AND event_type IN (?, ?) LIMIT 1",
            arrayOf(membershipPeriodId.toString(), compatibleTypes[0], compatibleTypes[1]),
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
            membershipPeriodId = membershipPeriodId,
        )
    }

    private fun hasEarlierMembershipPeriod(db: SQLiteDatabase, uid: Long, membershipPeriodId: Long): Boolean =
        db.rawQuery(
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
            arrayOf(membershipPeriodId.toString(), uid.toString()),
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

    private fun readMembershipPeriodForUpdate(db: SQLiteDatabase, membershipPeriodId: Long): MembershipPeriodForUpdate? =
        db.rawQuery(
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
                MembershipPeriodForUpdate(
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

    private fun insertMembershipPeriod(
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

    private fun closeLatestMembershipPeriod(
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
            ?: error("Active member $uid has no open membershipPeriod")
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

    private fun readKnownMembers(db: SQLiteDatabase): List<SnapshotReconciler.KnownMember> =
        db.rawQuery(
            """
            SELECT m.uid, m.current_name, m.is_active,
                   EXISTS(SELECT 1 FROM membership_periods t WHERE t.uid = m.uid)
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
                            hasPriorMembershipPeriod = cursor.getInt(3) != 0,
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

    private fun readMembershipPeriods(db: SQLiteDatabase, uid: Long): List<MembershipPeriod> =
        db.query(
            "membership_periods",
            MEMBERSHIP_PERIOD_COLUMNS,
            "uid = ?",
            arrayOf(uid.toString()),
            null,
            null,
            "CASE WHEN joined_at IS NULL THEN 1 ELSE 0 END, joined_at, id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MembershipPeriod(
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
        val membershipPeriodId: Long,
        val uid: Long,
        val boundary: MembershipBoundary,
        val inferredAt: Instant,
    )

    private data class BoundaryEventCandidate(
        val id: Long,
        val membershipPeriodId: Long?,
        val boundaryAt: Instant,
    )

    private data class MembershipPeriodForUpdate(
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

    private data class ShadowMembershipPeriod(
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

    private data class ManualBoundaryDateBackfill(
        val id: Long,
        val joinedAtMillis: Long?,
        val leftAtMillis: Long?,
        val joinedIsManual: Boolean,
        val leftIsManual: Boolean,
    )

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

    companion object {
        /**
         * A sparse positive signal: the member triggered a Daily Patrol supply
         * reward, which proves completion for that member and day. Its absence
         * must never be interpreted as a missed Daily Patrol.
         */
        const val DAILY_PATROL_REWARD_ACTION_ID = 802001L
        internal const val MAX_STORED_ACTIVITY_OBSERVATIONS = 10_000
        private const val MAX_UNRESOLVED_ACTIVITY_RESOLUTIONS = 250
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
        private val MEMBERSHIP_PERIOD_COLUMNS = arrayOf(
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
