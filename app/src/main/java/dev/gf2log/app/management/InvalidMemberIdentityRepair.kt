package dev.gf2log.app.management

import android.database.sqlite.SQLiteDatabase

/**
 * Removes every stored row of a member without a real UID (0, the protobuf default of a missing
 * UID, or below). Ingestion rejects such members, but a database written before 2.7.2, or a
 * backup of one, can still hold them, and a single one fails every weekly report that lists it
 * and cannot be deleted from the Members screen. Runs in the v14 upgrade and on every restored
 * database, inside the caller's transaction; a database without such rows is left untouched.
 * Works with foreign keys on or off: references are cleared before their targets go.
 */
internal object InvalidMemberIdentityRepair {
    /** Returns whether anything was removed. */
    fun repair(db: SQLiteDatabase): Boolean {
        val found = db.exists(
            """
            SELECT 1 FROM members WHERE uid <= 0
            UNION ALL SELECT 1 FROM snapshot_members WHERE uid <= 0
            UNION ALL SELECT 1 FROM membership_periods WHERE uid <= 0
            UNION ALL SELECT 1 FROM member_events WHERE uid <= 0
            UNION ALL SELECT 1 FROM weekly_overrides WHERE uid <= 0
            UNION ALL SELECT 1 FROM platoon_activity WHERE resolved_uid <= 0
            LIMIT 1
            """.trimIndent(),
        )
        if (!found) return false
        // A revision showing such a member fails its consistency check, so it never decodes.
        WeeklyHistoryIntegrity.dropUnreadableRevisions(db)
        repairActivity(db)
        repairMembership(db)
        db.delete("weekly_overrides", "uid <= 0", null)
        // A roster whose only member had no UID would read as everyone withdrawing.
        val emptied = db.rawQuery(
            "SELECT DISTINCT snapshot_id FROM snapshot_members WHERE uid <= 0 " +
                "AND snapshot_id NOT IN (SELECT snapshot_id FROM snapshot_members WHERE uid > 0)",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        db.delete("snapshot_members", "uid <= 0", null)
        emptied.forEach { id -> db.delete("snapshots", "id = ?", arrayOf(id.toString())) }
        db.delete("members", "uid <= 0", null)
        if (emptied.isNotEmpty()) {
            RosterHistoryReplay.reconcile(db)
        } else {
            MembershipRows.synchronizeAllActiveStates(db)
        }
        return true
    }

    /**
     * An exact Updates fact names only its UID, so it goes. A name-resolved fact is still the
     * name's evidence: it returns to unresolved, or goes when that name already has the same
     * unresolved fact.
     */
    private fun repairActivity(db: SQLiteDatabase) {
        db.delete(
            "platoon_activity",
            "resolved_uid <= 0 AND resolution = ?",
            arrayOf(ActivityResolution.EXACT_UPDATE.name),
        )
        db.execSQL(
            "UPDATE OR IGNORE platoon_activity SET resolved_uid = NULL, resolution = ?, " +
                "member_event_id = NULL WHERE resolved_uid <= 0",
            arrayOf(ActivityResolution.UNRESOLVED.name),
        )
        db.delete("platoon_activity", "resolved_uid <= 0", null)
    }

    private fun repairMembership(db: SQLiteDatabase) {
        val invalidPeriods = "SELECT id FROM membership_periods WHERE uid <= 0"
        // A real member's event never belongs to an invalid member's period; it keeps the event.
        db.execSQL(
            "UPDATE member_events SET membership_period_id = NULL " +
                "WHERE uid > 0 AND membership_period_id IN ($invalidPeriods)",
        )
        db.execSQL(
            "UPDATE weekly_notes SET event_id = NULL " +
                "WHERE event_id IN (SELECT id FROM member_events WHERE uid <= 0)",
        )
        MembershipRows.deleteSupersededEvents(db, "uid <= 0", emptyArray())
        db.delete("membership_periods", "uid <= 0", null)
    }
}
