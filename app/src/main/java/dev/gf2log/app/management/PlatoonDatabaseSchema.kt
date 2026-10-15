package dev.gf2log.app.management

import android.database.sqlite.SQLiteDatabase

/** Owns the current DDL; definitions shared by onCreate and migrations live here once. */
internal object PlatoonDatabaseSchema {
    fun create(db: SQLiteDatabase) {
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
        db.execSQL(MEMBERSHIP_PERIODS_UID_INDEX)
        db.execSQL(memberEventsTable(withCalendarColumns = true))
        db.execSQL(MEMBER_EVENTS_TIME_INDEX)
        createPlatoonActivityTable(db)
        db.execSQL(WEEKLY_NOTES_TABLE)
        createWeeklyOverridesTable(db)
        createPlatoonMaintenanceStateTable(db)
        createWeeklyReportHistoryTables(db)
    }

    /**
     * The member_events definition shared by onCreate and the v10 rebuild. Databases older than
     * v9 are rebuilt without the calendar columns, which the v9 step then appends.
     */
    fun memberEventsTable(withCalendarColumns: Boolean): String = table(
        "member_events",
        listOfNotNull(
            "id INTEGER PRIMARY KEY AUTOINCREMENT",
            "uid INTEGER NOT NULL REFERENCES members(uid)",
            "membership_period_id INTEGER REFERENCES membership_periods(id)",
            "event_type TEXT NOT NULL",
            "occurred_at INTEGER",
            "event_date INTEGER".takeIf { withCalendarColumns },
            "time_known INTEGER NOT NULL DEFAULT 1".takeIf { withCalendarColumns },
            "observed_at INTEGER NOT NULL",
            "precision TEXT NOT NULL",
            "source TEXT NOT NULL",
            "note TEXT NOT NULL DEFAULT ''",
        ),
    )

    val WEEKLY_NOTES_TABLE = table(
        "weekly_notes",
        listOf(
            "id INTEGER PRIMARY KEY AUTOINCREMENT",
            "period_start INTEGER NOT NULL",
            "game_day INTEGER NOT NULL",
            "text TEXT NOT NULL",
            "event_id INTEGER REFERENCES member_events(id) ON DELETE SET NULL",
            "is_automatic INTEGER NOT NULL DEFAULT 0",
        ),
    )

    const val MEMBERSHIP_PERIODS_UID_INDEX =
        "CREATE INDEX membership_periods_uid ON membership_periods(uid, id DESC)"
    const val MEMBER_EVENTS_TIME_INDEX =
        "CREATE INDEX member_events_time ON member_events(observed_at DESC)"

    private fun table(name: String, columns: List<String>): String =
        "CREATE TABLE $name (\n    ${columns.joinToString(",\n    ")}\n)"

    /** Idempotent maintenance objects that every writable open guarantees. */
    fun ensureMaintenanceObjects(db: SQLiteDatabase) {
        createPlatoonMaintenanceStateTable(db)
        createPlatoonActivityRetentionIndex(db)
        createWeeklyReportHistoryTables(db)
    }

    fun createWeeklyReportHistoryTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS weekly_report_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                period_start INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL,
                fingerprint TEXT NOT NULL,
                report_blob BLOB NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS weekly_report_history_period " +
                "ON weekly_report_history(period_start, recorded_at DESC, id DESC)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS weekly_report_history_state (
                period_start INTEGER PRIMARY KEY,
                active_history_id INTEGER REFERENCES weekly_report_history(id) ON DELETE SET NULL
            )
            """.trimIndent(),
        )
    }

    fun createWeeklyOverridesTable(db: SQLiteDatabase) {
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

    fun createPlatoonActivityTable(db: SQLiteDatabase) {
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

    fun createPlatoonActivityRetentionIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS platoon_activity_resolution_retention " +
                "ON platoon_activity(resolved_uid, captured_at DESC, id DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS platoon_activity_retention_order " +
                "ON platoon_activity(captured_at DESC, id DESC)",
        )
    }

    fun createPlatoonMaintenanceStateTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS platoon_maintenance_state (
                key TEXT PRIMARY KEY,
                value INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
