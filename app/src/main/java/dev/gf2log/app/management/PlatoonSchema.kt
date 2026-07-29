package dev.gf2log.app.management

/**
 * Single storage contract shared by the SQLite helper and backup validator.
 */
internal object PlatoonSchema {
    const val DATABASE_NAME = "platoon.db"
    const val CURRENT_VERSION = 9
    const val MIN_BACKUP_VERSION = 1

    private val baseTables = setOf(
        "snapshots",
        "snapshot_members",
        "members",
        "tenures",
        "member_events",
        "weekly_notes",
    )

    fun requiredTables(version: Int): Set<String> = buildSet {
        addAll(baseTables)
        if (version >= 3) add("weekly_overrides")
        if (version >= 6) add("platoon_activity")
    }

    fun requiredColumns(version: Int): Map<String, Set<String>> = buildMap {
        put("snapshots", setOf("id", "captured_at"))
        put("snapshot_members", setOf("snapshot_id", "uid", "name", "total_merit"))
        put(
            "members",
            buildSet {
                addAll(setOf("uid", "current_name", "is_active", "first_seen_at", "last_seen_at"))
                if (version >= 4) add("custom_name")
            },
        )
        put(
            "tenures",
            buildSet {
                addAll(
                    setOf(
                        "id",
                        "uid",
                        "joined_at",
                        "left_at",
                        "joined_precision",
                        "left_precision",
                        "joined_source",
                        "left_source",
                    ),
                )
                if (version >= 9) {
                    addAll(
                        setOf(
                            "joined_date",
                            "left_date",
                            "joined_time_known",
                            "left_time_known",
                        ),
                    )
                }
            },
        )
        put(
            "member_events",
            buildSet {
                addAll(
                    setOf(
                        "id",
                        "uid",
                        "event_type",
                        "occurred_at",
                        "observed_at",
                        "precision",
                        "source",
                    ),
                )
                if (version >= 6) add("tenure_id")
                if (version >= 9) addAll(setOf("event_date", "time_known"))
            },
        )
        put("weekly_notes", setOf("id", "period_start", "game_day", "text"))
        if (version >= 3) {
            put("weekly_overrides", setOf("uid", "period_start", "game_day"))
        }
        if (version >= 6) {
            put(
                "platoon_activity",
                setOf("occurred_at", "action_id", "member_name", "resolved_uid"),
            )
        }
    }
}
