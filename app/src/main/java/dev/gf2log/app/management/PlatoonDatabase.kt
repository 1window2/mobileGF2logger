package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.ZoneId

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
                changes.joined.forEach { member ->
                    insertTenure(db, member.uid, snapshot.capturedAt, EvidencePrecision.INFERRED, source)
                    insertEventAndNote(
                        db,
                        member,
                        MemberEventType.JOINED,
                        snapshot.capturedAt,
                        EvidencePrecision.INFERRED,
                        source,
                    )
                }
                changes.rejoined.forEach { member ->
                    insertTenure(db, member.uid, snapshot.capturedAt, EvidencePrecision.INFERRED, source)
                    insertEventAndNote(
                        db,
                        member,
                        MemberEventType.REJOINED,
                        snapshot.capturedAt,
                        EvidencePrecision.INFERRED,
                        source,
                    )
                }
                changes.left.forEach { member ->
                    closeLatestTenure(db, member.uid, snapshot.capturedAt, EvidencePrecision.INFERRED, source)
                    markInactive(db, member.uid, snapshot.capturedAt)
                    insertEventAndNote(
                        db,
                        SnapshotMember(member.uid, member.name, 0, 0, 0, 0, 0, 0),
                        MemberEventType.LEFT,
                        snapshot.capturedAt,
                        EvidencePrecision.INFERRED,
                        source,
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
            "is_active DESC, current_name COLLATE NOCASE, uid",
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
            "observed_at >= ? AND observed_at < ?",
            arrayOf(from.toEpochMilli().toString(), until.toEpochMilli().toString()),
            null,
            null,
            "observed_at, id",
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
            put("current_name", name.trim())
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
        val values = ContentValues().apply {
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
        }
        return writableDatabase.update(
            "tenures",
            values,
            "id = ?",
            arrayOf(tenureId.toString()),
        ) == 1
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
    ) {
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
    }

    private fun insertEventAndNote(
        db: SQLiteDatabase,
        member: SnapshotMember,
        type: MemberEventType,
        observedAt: Instant,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ) {
        val eventId = insertEvent(
            db,
            member.uid,
            type,
            observedAt,
            observedAt,
            precision,
            source,
            "",
        )
        db.insertOrThrow(
            "weekly_notes",
            null,
            ContentValues().apply {
                val gameDay = PlatoonPeriods.gameDay(observedAt, ZoneId.systemDefault())
                val periodStart = PlatoonPeriods.weekStart(gameDay)
                put("period_start", periodStart.toEpochDay())
                put("game_day", gameDay.toEpochDay())
                put("text", "${type.name}:${member.name}:${member.uid}")
                put("event_id", eventId)
                put("is_automatic", 1)
            },
        )
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
    ): Long = db.insertOrThrow(
        "member_events",
        null,
        ContentValues().apply {
            put("uid", uid)
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
            "id DESC",
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

    companion object {
        private const val DATABASE_NAME = "platoon.db"
        private const val DATABASE_VERSION = 2
        private val SNAPSHOT_COLUMNS = arrayOf("id", "captured_at", "source_file", "game_version")
        private val MEMBER_COLUMNS = arrayOf(
            "uid",
            "current_name",
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
    }
}

private fun ContentValues.putNullableLong(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun Cursor.getNullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun Cursor.getNullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)
