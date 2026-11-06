package dev.gf2log.app.management

/**
 * Finds table DDL in a live Platoon database that the app never wrote. Before 2.7.2 a restore
 * installed the selected file as it was, and its structural checks cannot see table clauses such
 * as CHECK, ON CONFLICT, COLLATE, STRICT or WITHOUT ROWID, so such a profile can still carry them;
 * in-place upgrades never rewrite table DDL. No CREATE or ALTER statement the app has ever run
 * uses any of these clauses.
 */
internal object LiveSchemaAudit {
    private val FOREIGN_CLAUSES = listOf(
        Regex("\\bCHECK\\b"),
        Regex("\\bCONFLICT\\b"),
        Regex("\\bCOLLATE\\b"),
        Regex("\\bSTRICT\\b"),
        Regex("\\bWITHOUT\\s+ROWID\\b"),
        Regex("\\bGENERATED\\b"),
        Regex("\\bAS\\s*\\("),
    )

    /** Tables no migration renames or alters, so even an upgraded database keeps their app text. */
    internal val NEVER_ALTERED = setOf(
        "snapshot_members",
        "weekly_overrides",
        "platoon_maintenance_state",
        "weekly_report_history",
        "weekly_report_history_state",
    )

    /**
     * Whether [actual], the live database's normalized table DDL, needs a rebuild into the app's
     * own [reference] DDL ([DatabaseSchemaContract.readTableSql] of both).
     */
    fun carriesForeignDdl(actual: Map<String, String>, reference: Map<String, String>): Boolean =
        actual.values.any { sql -> FOREIGN_CLAUSES.any { it.containsMatchIn(sql.uppercase()) } } ||
            NEVER_ALTERED.any { table -> table in actual && actual[table] != reference[table] }
}
