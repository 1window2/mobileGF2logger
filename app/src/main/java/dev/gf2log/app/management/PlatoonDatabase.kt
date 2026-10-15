package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.gf2log.app.settings.GameTimeZonePreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The single transactional write owner of one scoped management database. Every public method
 * is serialized on this helper; the SQL lives in focused objects that receive the open
 * [SQLiteDatabase] so one evidence ingest still commits or rolls back as a whole.
 */
internal class PlatoonDatabase private constructor(
    context: Context,
    private val storageScope: PlatoonStorageScope,
    databaseName: String?,
) :
    SQLiteOpenHelper(
        context.applicationContext,
        databaseName,
        null,
        PlatoonSchema.CURRENT_VERSION,
    ) {
    constructor(
        context: Context,
        databaseName: String = PlatoonProfileRegistry(context).activeScope().databaseName,
        storageScope: PlatoonStorageScope = PlatoonStorageScope.fromDatabaseName(databaseName),
    ) : this(context, storageScope, databaseName)

    private val appContext = context.applicationContext

    private fun gameZone(): ZoneId = GameTimeZonePreferences.get(appContext, storageScope.storageId)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            PlatoonDatabaseSchema.ensureMaintenanceObjects(db)
            ActivityIngestion.trimPlatoonActivity(db)
        }
    }

    override fun onCreate(db: SQLiteDatabase) = PlatoonDatabaseSchema.create(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        PlatoonDatabaseMigrations.upgrade(db, oldVersion, ::gameZone)

    /** Returns true only when this database contains user-visible management state. */
    internal fun hasManagementData(): Boolean {
        val db = readableDatabase
        return MANAGEMENT_DATA_TABLES.any { table -> PlatoonReadQueries.count(db, table) > 0L }
    }

    @Synchronized
    internal fun <T> runInTransaction(block: () -> T): T = writableDatabase.inTransaction(block)

    @Synchronized
    fun ingestSnapshot(
        snapshot: PlatoonSnapshot,
        source: EvidenceSource,
        historicalOnly: Boolean = false,
        deferHistoricalMembershipReconciliation: Boolean = false,
    ): SnapshotIngestResult = SnapshotIngestion.ingest(
        writableDatabase,
        snapshot,
        source,
        historicalOnly,
        deferHistoricalMembershipReconciliation,
    )

    /** Replays every retained roster after late historical imports; see [RosterHistoryReplay]. */
    @Synchronized
    fun reconcileSnapshotMembershipHistory() {
        val db = writableDatabase
        db.inTransaction { RosterHistoryReplay.reconcile(db) }
    }

    @Synchronized
    fun latestSnapshotIdentity(): Pair<Instant, String?>? =
        PlatoonReadQueries.latestSnapshotIdentity(readableDatabase)

    @Synchronized
    fun snapshotSourceFiles(): Set<String> = PlatoonReadQueries.snapshotSourceFiles(readableDatabase)

    @Synchronized
    fun ingestPlatoonActivity(
        observations: List<PlatoonActivityObservation>,
        capturedAt: Instant,
    ): ActivityIngestResult = ActivityIngestion.ingest(writableDatabase, observations, capturedAt)

    @Synchronized
    fun ingestPlatoonUpdates(
        observations: List<PlatoonUpdateObservation>,
        capturedAt: Instant,
    ): UpdatesIngestResult = ExactBoundaryCorrelation.ingest(writableDatabase, observations, capturedAt)

    @Synchronized
    fun listDailyPatrolFacts(from: Instant, until: Instant): List<DailyPatrolFact> =
        PlatoonReadQueries.listDailyPatrolFacts(readableDatabase, from, until)

    @Synchronized
    fun addWithdrawnMember(
        uid: Long,
        name: String,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue,
        note: String,
    ): Boolean = MembershipEditing.addWithdrawnMember(writableDatabase, uid, name, joined, withdrew, note)

    @Synchronized
    fun addMembershipPeriod(
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Boolean = MembershipEditing.addMembershipPeriod(writableDatabase, uid, joined, withdrew, note)

    @Synchronized
    fun listSnapshots(limit: Int = 100): List<PlatoonSnapshot> {
        require(limit in 1..1000)
        return WeeklySnapshotStore.list(readableDatabase, limit)
    }

    /**
     * Loads only the snapshots capable of affecting a reporting period plus the nearest prior
     * roster/baseline, independent of database growth.
     */
    @Synchronized
    fun listSnapshotsForPeriod(from: Instant, until: Instant): List<PlatoonSnapshot> {
        require(until.isAfter(from))
        return WeeklySnapshotStore.listForPeriod(readableDatabase, from, until)
    }

    @Synchronized
    fun listMemberStatuses(activeOnly: Boolean = false): List<MemberStatus> =
        PlatoonReadQueries.listMemberStatuses(readableDatabase, activeOnly)

    @Synchronized
    fun listEvents(
        from: Instant,
        until: Instant,
        fromDate: LocalDate,
        untilDate: LocalDate,
    ): List<MemberEvent> = PlatoonReadQueries.listEvents(readableDatabase, from, until, fromDate, untilDate)

    @Synchronized
    fun updateMember(uid: Long, name: String, note: String): Boolean =
        MembershipEditing.updateMember(writableDatabase, uid, name, note)

    @Synchronized
    fun deleteMember(uid: Long): Boolean = MembershipEditing.deleteMember(writableDatabase, uid)

    @Synchronized
    fun deleteMembershipPeriod(membershipPeriodId: Long): Boolean =
        MembershipEditing.deleteMembershipPeriod(writableDatabase, membershipPeriodId)

    @Synchronized
    fun updateMembershipPeriod(
        membershipPeriodId: Long,
        joined: MembershipBoundaryValue,
        left: MembershipBoundaryValue?,
        note: String,
    ): Boolean = MembershipEditing.updateMembershipPeriod(
        writableDatabase,
        ::gameZone,
        membershipPeriodId,
        joined,
        left,
        note,
    )

    @Synchronized
    fun addWeeklyNote(periodStartEpochDay: Long, gameDayEpochDay: Long, text: String): Long =
        WeeklyAnnotationStore.addWeeklyNote(writableDatabase, periodStartEpochDay, gameDayEpochDay, text)

    @Synchronized
    fun listWeeklyNotes(periodStartEpochDay: Long): List<WeeklyNote> =
        WeeklyAnnotationStore.listWeeklyNotes(readableDatabase, periodStartEpochDay)

    @Synchronized
    fun weeklyNotePeriodStart(id: Long): LocalDate? =
        WeeklyAnnotationStore.weeklyNotePeriodStart(readableDatabase, id)

    @Synchronized
    fun deleteWeeklyNote(id: Long): Boolean = WeeklyAnnotationStore.deleteWeeklyNote(writableDatabase, id)

    @Synchronized
    fun listWeeklyOverrides(periodStartEpochDay: Long): List<WeeklyCellOverride> =
        WeeklyAnnotationStore.listWeeklyOverrides(readableDatabase, periodStartEpochDay)

    @Synchronized
    fun listWeeklyEvidenceDays(zoneId: ZoneId): List<LocalDate> =
        PlatoonReadQueries.listWeeklyEvidenceDays(readableDatabase, zoneId)

    @Synchronized
    fun replaceWeeklyOverrides(
        periodStartEpochDay: Long,
        overrides: List<WeeklyCellOverride>,
    ) = WeeklyAnnotationStore.replaceWeeklyOverrides(writableDatabase, periodStartEpochDay, overrides)

    @Synchronized
    fun recordWeeklyReportHistory(
        periodStartEpochDay: Long,
        recordedAt: Instant,
        fingerprint: String,
        payload: ByteArray,
        clearActiveOnChange: Boolean,
    ): Long = WeeklyHistoryStore.record(
        writableDatabase,
        periodStartEpochDay,
        recordedAt,
        fingerprint,
        payload,
        clearActiveOnChange,
    )

    @Synchronized
    fun listWeeklyReportHistory(periodStartEpochDay: Long): List<WeeklyReportHistoryEntry> =
        WeeklyHistoryStore.list(readableDatabase, periodStartEpochDay)

    @Synchronized
    fun weeklyReportHistoryPayload(id: Long, periodStartEpochDay: Long): ByteArray? =
        WeeklyHistoryStore.payload(readableDatabase, id, periodStartEpochDay)

    @Synchronized
    fun activeWeeklyReportHistoryPayload(periodStartEpochDay: Long): ByteArray? =
        WeeklyHistoryStore.activePayload(readableDatabase, periodStartEpochDay)

    @Synchronized
    fun activateWeeklyReportHistory(id: Long, periodStartEpochDay: Long): Boolean =
        WeeklyHistoryStore.activate(writableDatabase, id, periodStartEpochDay)

    @Synchronized
    fun clearActiveWeeklyReportHistory(periodStartEpochDay: Long): Boolean =
        WeeklyHistoryStore.clearActive(writableDatabase, periodStartEpochDay)

    @Synchronized
    fun clearWeeklyReportHistory() = WeeklyHistoryStore.clear(writableDatabase)

    @Synchronized
    internal fun replaceWeeklyReportHistory(replacements: List<WeeklyReportHistoryReplacement>) =
        WeeklyHistoryStore.replace(writableDatabase, replacements)

    companion object {
        /** A fresh current-schema database that never touches storage, for schema comparison. */
        fun inMemory(context: Context, storageScope: PlatoonStorageScope) =
            PlatoonDatabase(context, storageScope, null)

        /**
         * A sparse positive signal: the member triggered a Daily Patrol supply
         * reward, which proves completion for that member and day. Its absence
         * must never be interpreted as a missed Daily Patrol.
         */
        const val DAILY_PATROL_REWARD_ACTION_ID = 802001L
        internal const val MAX_STORED_ACTIVITY_OBSERVATIONS = 10_000
        internal const val MAX_WEEKLY_REPORT_HISTORY = 15
        private val MANAGEMENT_DATA_TABLES = listOf(
            "snapshots",
            "members",
            "membership_periods",
            "member_events",
            "platoon_activity",
            "weekly_notes",
            "weekly_overrides",
        )
    }
}
