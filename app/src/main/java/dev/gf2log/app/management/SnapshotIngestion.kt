package dev.gf2log.app.management

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.gf2log.app.management.ActivityIngestion.correlateMembershipBoundary
import dev.gf2log.app.management.ExactBoundaryCorrelation.findRosterConfirmedExactMembershipPeriod
import dev.gf2log.app.management.MembershipRows.closeLatestMembershipPeriod
import dev.gf2log.app.management.MembershipRows.ensureRosterActiveMembershipPeriod
import dev.gf2log.app.management.MembershipRows.insertEvent
import dev.gf2log.app.management.MembershipRows.insertMembershipPeriod
import dev.gf2log.app.management.MembershipRows.insertSnapshotBoundaryEvent
import dev.gf2log.protocol.GuildMembersCsv
import java.time.Instant
import java.util.Locale

/** Ingests one 21917 roster snapshot and derives roster-change membership boundaries. */
internal object SnapshotIngestion {
    fun ingest(
        db: SQLiteDatabase,
        snapshot: PlatoonSnapshot,
        source: EvidenceSource,
        historicalOnly: Boolean,
        deferHistoricalMembershipReconciliation: Boolean,
    ): SnapshotIngestResult {
        require(snapshot.members.isNotEmpty()) { "A Platoon snapshot cannot be empty" }
        require(snapshot.members.size <= GuildMembersCsv.MAX_ROSTER_MEMBERS) {
            "A Platoon snapshot exceeds the member limit"
        }
        require(snapshot.members.all { it.name.length <= GuildMembersCsv.MAX_MEMBER_NAME_CHARS }) {
            "A Platoon snapshot contains an oversized member name"
        }
        require(snapshot.members.map(SnapshotMember::uid).distinct().size == snapshot.members.size) {
            "A Platoon snapshot cannot contain duplicate UIDs"
        }
        return db.inTransaction {
            if (snapshot.sourceFile != null && sourceFileExists(db, snapshot.sourceFile)) {
                return@inTransaction SnapshotIngestResult.duplicate()
            }
            val priorCapturedAt = latestSnapshotCapturedAt(db)
            if (historicalOnly) {
                require(priorCapturedAt != null && !snapshot.capturedAt.isAfter(priorCapturedAt)) {
                    "Historical snapshots must not be newer than current structured data"
                }
                val snapshotId = insertSnapshotRows(db, snapshot)
                if (!deferHistoricalMembershipReconciliation) RosterHistoryReplay.reconcile(db)
                SnapshotIngestResult.historical(snapshotId)
            } else {
                ingestCurrent(db, snapshot, source, priorCapturedAt)
            }
        }
    }

    private fun ingestCurrent(
        db: SQLiteDatabase,
        snapshot: PlatoonSnapshot,
        source: EvidenceSource,
        priorCapturedAt: Instant?,
    ): SnapshotIngestResult {
        val known = readKnownMembers(db)
        val changes = SnapshotReconciler.reconcile(
            known = known,
            incoming = snapshot.members,
            hasPriorSnapshot = priorCapturedAt != null,
        )
        val snapshotId = insertSnapshotRows(db, snapshot)
        snapshot.members.forEach { member -> upsertMember(db, member, snapshot.capturedAt) }
        if (priorCapturedAt != null) {
            recordRosterChanges(db, snapshot, source, priorCapturedAt, known, changes)
        }
        // A roster is authoritative for current presence. Repair any missing open
        // membership period left by an incomplete historical Updates feed.
        snapshot.members.forEach { member ->
            ensureRosterActiveMembershipPeriod(db, member.uid, source)
        }
        MembershipRows.synchronizeActiveState(
            db,
            snapshot.members.map(SnapshotMember::uid) + changes.left.map { it.uid },
        )
        ActivityIngestion.trimPlatoonActivity(db)
        ActivityIngestion.resolveUnresolvedActivityUids(db)
        return SnapshotIngestResult(
            snapshotId = snapshotId,
            duplicate = false,
            initialRoster = changes.initialRoster,
            joined = changes.joined.size,
            rejoined = changes.rejoined.size,
            left = changes.left.size,
            renamed = changes.renamed.size,
        )
    }

    private fun recordRosterChanges(
        db: SQLiteDatabase,
        snapshot: PlatoonSnapshot,
        source: EvidenceSource,
        priorCapturedAt: Instant,
        known: List<SnapshotReconciler.KnownMember>,
        changes: SnapshotReconciler.Result,
    ) {
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

        // Activity names can be correlated only when neither the change set nor the roster
        // contains another identity with the same case-insensitive name.
        fun nameIsUnique(name: String): Boolean = name.lowercase(Locale.ROOT).let { key ->
            changedNameCounts[key] == 1 && rosterNameUidCounts[key] == 1
        }

        changes.joined.forEach { member ->
            recordSnapshotJoin(
                db = db,
                member = member,
                type = MemberEventType.JOINED,
                observedAt = snapshot.capturedAt,
                priorCapturedAt = priorCapturedAt,
                source = source,
                nameIsUnique = nameIsUnique(member.name),
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
                nameIsUnique = nameIsUnique(member.name),
            )
        }
        changes.left.forEach { member ->
            recordSnapshotLeave(
                db = db,
                member = member,
                observedAt = snapshot.capturedAt,
                priorCapturedAt = priorCapturedAt,
                source = source,
                nameIsUnique = nameIsUnique(member.name),
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

    private fun recordSnapshotJoin(
        db: SQLiteDatabase,
        member: SnapshotMember,
        type: MemberEventType,
        observedAt: Instant,
        priorCapturedAt: Instant,
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
            uid = member.uid,
            memberName = member.name,
            type = type,
            boundary = MembershipBoundary.JOIN,
            observedAt = observedAt,
            from = priorCapturedAt,
            nameIsUnique = nameIsUnique,
        )
    }

    private fun recordSnapshotLeave(
        db: SQLiteDatabase,
        member: SnapshotReconciler.KnownMember,
        observedAt: Instant,
        priorCapturedAt: Instant,
        source: EvidenceSource,
        nameIsUnique: Boolean,
    ) {
        val exactMembershipPeriodId = findRosterConfirmedExactMembershipPeriod(
            db = db,
            uid = member.uid,
            boundary = MembershipBoundary.WITHDRAW,
            from = priorCapturedAt,
            observedAt = observedAt,
        )
        val membershipPeriodId = exactMembershipPeriodId ?: closeLatestMembershipPeriod(
            db = db,
            uid = member.uid,
            leftAt = observedAt,
            precision = EvidencePrecision.INFERRED,
            source = source,
        )
        recordLastSeen(db, member.uid, observedAt)
        if (exactMembershipPeriodId != null) return
        insertSnapshotBoundaryEvent(
            db,
            membershipPeriodId,
            member.uid,
            member.name,
            MemberEventType.LEFT,
            observedAt,
            source,
        )
        correlateMembershipBoundary(
            db = db,
            membershipPeriodId = membershipPeriodId,
            uid = member.uid,
            memberName = member.name,
            type = MemberEventType.LEFT,
            boundary = MembershipBoundary.WITHDRAW,
            observedAt = observedAt,
            from = priorCapturedAt,
            nameIsUnique = nameIsUnique,
        )
    }

    fun insertSnapshotRows(db: SQLiteDatabase, snapshot: PlatoonSnapshot): Long {
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
            put("last_seen_at", observedAt.toEpochMilli())
        }
        if (db.update("members", existing, "uid = ?", arrayOf(member.uid.toString())) == 0) {
            MembershipRows.insertMember(
                db = db,
                uid = member.uid,
                name = member.name,
                level = member.level,
                firstSeenAt = observedAt,
                lastSeenAt = observedAt,
            )
        }
    }

    private fun recordLastSeen(db: SQLiteDatabase, uid: Long, observedAt: Instant) {
        db.update(
            "members",
            ContentValues().apply { put("last_seen_at", observedAt.toEpochMilli()) },
            "uid = ?",
            arrayOf(uid.toString()),
        )
    }

    private fun readKnownMembers(db: SQLiteDatabase): List<SnapshotReconciler.KnownMember> =
        db.rawQuery(
            """
            SELECT m.uid, m.current_name, m.is_active,
                   EXISTS(SELECT 1 FROM membership_periods period WHERE period.uid = m.uid)
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

    private fun sourceFileExists(db: SQLiteDatabase, sourceFile: String): Boolean =
        db.exists("SELECT 1 FROM snapshots WHERE source_file = ? LIMIT 1", sourceFile)

    fun latestSnapshotCapturedAt(db: SQLiteDatabase): Instant? =
        db.rawQuery("SELECT MAX(captured_at) FROM snapshots", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getNullableInstant(0) else null
        }
}
