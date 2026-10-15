package dev.gf2log.app.management

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.management.PlatoonDatabase.Companion.DAILY_PATROL_REWARD_ACTION_ID
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Read-only projections served to the repository. */
internal object PlatoonReadQueries {
    fun latestSnapshotIdentity(db: SQLiteDatabase): Pair<Instant, String?>? = db.rawQuery(
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

    /** The earliest roster captured strictly after [instant], if any. */
    fun firstSnapshotCapturedAfter(db: SQLiteDatabase, instant: Instant): Instant? = db.rawQuery(
        "SELECT MIN(captured_at) FROM snapshots WHERE captured_at > ?",
        arrayOf(instant.toEpochMilli().toString()),
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) Instant.ofEpochMilli(cursor.getLong(0)) else null
    }

    fun snapshotSourceFiles(db: SQLiteDatabase): Set<String> = db.rawQuery(
        "SELECT source_file FROM snapshots WHERE source_file IS NOT NULL",
        null,
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    fun listDailyPatrolFacts(db: SQLiteDatabase, from: Instant, until: Instant): List<DailyPatrolFact> {
        require(!until.isBefore(from))
        return db.query(
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

    /** Two queries in total: the members, then every listed member's periods at once. */
    fun listMemberStatuses(db: SQLiteDatabase, activeOnly: Boolean): List<MemberStatus> {
        val selection = if (activeOnly) "is_active = 1" else null
        val periodsByUid = readMembershipPeriods(db, selection)
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
                            membershipPeriods = periodsByUid[uid].orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    private fun readMembershipPeriods(
        db: SQLiteDatabase,
        memberSelection: String?,
    ): Map<Long, List<MembershipPeriod>> = db.query(
        "membership_periods",
        MEMBERSHIP_PERIOD_COLUMNS,
        memberSelection?.let { "uid IN (SELECT uid FROM members WHERE $it)" },
        null,
        null,
        null,
        "uid, CASE WHEN joined_at IS NULL THEN 1 ELSE 0 END, joined_at, id",
    ).use { cursor ->
        val periods = mutableMapOf<Long, MutableList<MembershipPeriod>>()
        while (cursor.moveToNext()) {
            val uid = cursor.getLong(12)
            periods.getOrPut(uid, ::mutableListOf) += MembershipPeriod(
                id = cursor.getLong(0),
                uid = uid,
                joinedAt = cursor.getNullableInstant(1),
                leftAt = cursor.getNullableInstant(2),
                joinedDate = cursor.getNullableLong(3)?.let(LocalDate::ofEpochDay),
                leftDate = cursor.getNullableLong(4)?.let(LocalDate::ofEpochDay),
                joinedTimeKnown = cursor.getInt(5) != 0,
                leftTimeKnown = cursor.getNullableInt(6)?.let { it != 0 },
                joinedPrecision = enumValueOf(cursor.getString(7)),
                leftPrecision = cursor.getNullableString(8)?.let(EvidencePrecision::valueOf),
                joinedSource = enumValueOf(cursor.getString(9)),
                leftSource = cursor.getNullableString(10)?.let(EvidenceSource::valueOf),
                note = cursor.getString(11),
            )
        }
        periods
    }

    fun listEvents(
        db: SQLiteDatabase,
        from: Instant,
        until: Instant,
        fromDate: LocalDate,
        untilDate: LocalDate,
    ): List<MemberEvent> {
        require(!until.isBefore(from))
        require(!untilDate.isBefore(fromDate))
        return db.query(
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

    fun listWeeklyEvidenceDays(db: SQLiteDatabase, zoneId: ZoneId): List<LocalDate> {
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

    fun membershipPeriodOwner(db: SQLiteDatabase, membershipPeriodId: Long): Long? =
        db.rawQuery(
            "SELECT uid FROM membership_periods WHERE id = ?",
            arrayOf(membershipPeriodId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun Cursor.toMemberEvent(): MemberEvent = MemberEvent(
        id = getLong(0),
        uid = getLong(1),
        type = enumValueOf(getString(2)),
        occurredAt = getNullableInstant(3),
        eventDate = getNullableLong(4)?.let(LocalDate::ofEpochDay),
        timeKnown = getInt(5) != 0,
        observedAt = Instant.ofEpochMilli(getLong(6)),
        precision = enumValueOf(getString(7)),
        source = enumValueOf(getString(8)),
        note = getString(9),
    )

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
        "uid",
    )
}
