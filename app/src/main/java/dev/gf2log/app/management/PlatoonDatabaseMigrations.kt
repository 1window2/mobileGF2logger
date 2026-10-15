package dev.gf2log.app.management

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Upgrades older schemas inside SQLiteOpenHelper's upgrade transaction. */
internal object PlatoonDatabaseMigrations {
    fun upgrade(db: SQLiteDatabase, oldVersion: Int, gameZone: () -> ZoneId) {
        if (oldVersion < 10) renameTenuresToMembershipPeriods(db, oldVersion)
        if (oldVersion < 9) addCalendarDateColumns(db)
        if (oldVersion < 2) moveWeeklyNotesToSundayPeriods(db)
        if (oldVersion < 3) PlatoonDatabaseSchema.createWeeklyOverridesTable(db)
        if (oldVersion < 4) db.execSQL("ALTER TABLE members ADD COLUMN custom_name TEXT")
        if (oldVersion < 5) copyAutomaticNoteNamesToEvents(db)
        if (oldVersion < 6) {
            db.execSQL(
                "ALTER TABLE member_events ADD COLUMN membership_period_id INTEGER " +
                    "REFERENCES membership_periods(id)",
            )
            PlatoonDatabaseSchema.createPlatoonActivityTable(db)
            db.delete("weekly_notes", "is_automatic = 1", null)
            linkLegacyMembershipPeriodEvents(db)
        }
        if (oldVersion < 7) backfillSnapshotMembershipPeriodEvents(db)
        if (oldVersion < 9) backfillManualCalendarDates(db, gameZone())
        if (oldVersion < 11) {
            PlatoonDatabaseSchema.createPlatoonMaintenanceStateTable(db)
            PlatoonDatabaseSchema.createPlatoonActivityRetentionIndex(db)
        }
        if (oldVersion < 12) PlatoonDatabaseSchema.createWeeklyReportHistoryTables(db)
        if (oldVersion < 13) MembershipRows.synchronizeAllActiveStates(db)
    }

    private fun renameTenuresToMembershipPeriods(db: SQLiteDatabase, oldVersion: Int) {
        db.execSQL("ALTER TABLE tenures RENAME TO membership_periods")
        db.execSQL("DROP INDEX IF EXISTS tenures_uid")
        db.execSQL(PlatoonDatabaseSchema.MEMBERSHIP_PERIODS_UID_INDEX)
        if (oldVersion >= 6) {
            migrateMembershipPeriodEventReference(
                db = db,
                hasCalendarColumns = oldVersion >= 9,
            )
        }
    }

    private fun addCalendarDateColumns(db: SQLiteDatabase) {
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

    private fun moveWeeklyNotesToSundayPeriods(db: SQLiteDatabase) {
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
                    PlatoonPeriods.weekStart(LocalDate.ofEpochDay(cursor.getLong(1))).toEpochDay()
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

    private fun copyAutomaticNoteNamesToEvents(db: SQLiteDatabase) {
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

    /**
     * Rebuilds member_events with the membership-period reference, then rebuilds dependent tables
     * so every foreign key targets the replacement. All rows and IDs are preserved.
     */
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

        db.execSQL(PlatoonDatabaseSchema.memberEventsTable(withCalendarColumns = hasCalendarColumns))
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
        db.execSQL(PlatoonDatabaseSchema.MEMBER_EVENTS_TIME_INDEX)

        db.execSQL(PlatoonDatabaseSchema.WEEKLY_NOTES_TABLE)
        db.execSQL(
            """
            INSERT INTO weekly_notes(id, period_start, game_day, text, event_id, is_automatic)
            SELECT id, period_start, game_day, text, event_id, is_automatic
            FROM weekly_notes_legacy_v10
            """.trimIndent(),
        )

        PlatoonDatabaseSchema.createPlatoonActivityTable(db)
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

    private fun linkLegacyMembershipPeriodEvents(db: SQLiteDatabase) {
        db.rawQuery(
            """
            SELECT period.id, period.uid, period.joined_at, period.left_at,
                   period.joined_precision, period.left_precision,
                   period.joined_source, period.left_source,
                   COALESCE(m.custom_name, m.current_name)
            FROM membership_periods period
            JOIN members m ON m.uid = period.uid
            ORDER BY period.uid, period.id
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
                    val expectedType = MembershipRows.joinEventType(db, uid, membershipPeriodId)
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
        MembershipRows.insertEvent(
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
            SELECT period.id, period.uid, period.joined_at, period.left_at,
                   period.joined_source, period.left_source,
                   COALESCE(m.custom_name, m.current_name)
            FROM membership_periods period
            JOIN members m ON m.uid = period.uid
            WHERE period.joined_source IN (?, ?)
               OR period.left_source IN (?, ?)
            ORDER BY period.uid, period.id
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
                val joinedAt = cursor.getNullableInstant(2)
                val leftAt = cursor.getNullableInstant(3)
                val joinedSource = EvidenceSource.valueOf(cursor.getString(4))
                val leftSource = cursor.getNullableString(5)?.let(EvidenceSource::valueOf)
                val name = cursor.getString(6)
                if (joinedAt != null && joinedSource in SNAPSHOT_EVENT_SOURCES) {
                    MembershipRows.insertSnapshotBoundaryEvent(
                        db,
                        membershipPeriodId,
                        uid,
                        name,
                        MembershipRows.joinEventType(db, uid, membershipPeriodId),
                        joinedAt,
                        joinedSource,
                    )
                }
                if (leftAt != null && leftSource in SNAPSHOT_EVENT_SOURCES) {
                    MembershipRows.insertSnapshotBoundaryEvent(
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

    private data class ManualBoundaryDateBackfill(
        val id: Long,
        val joinedAtMillis: Long?,
        val leftAtMillis: Long?,
        val joinedIsManual: Boolean,
        val leftIsManual: Boolean,
    )
}
