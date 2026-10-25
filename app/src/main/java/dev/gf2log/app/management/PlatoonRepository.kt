package dev.gf2log.app.management

import android.content.Context
import android.util.Log
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.MemberOrderPreferences
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonUpdatesData
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

internal class PlatoonRepository(
    context: Context,
    internal val storageScope: PlatoonStorageScope =
        PlatoonProfileRegistry(context).activeScope(),
) {
    private val appContext = context.applicationContext

    init {
        PlatoonBackupManager.recoverInterruptedFullRestore(appContext, storageScope)
    }

    /** The 05:00 reset zone of this repository's own Platoon, never the currently active one. */
    fun gameZone(): ZoneId = GameTimeZonePreferences.get(appContext, storageScope.storageId)

    fun ingest(
        capturedAt: Instant,
        members: List<GuildMember>,
        sourceFile: String?,
    ): SnapshotIngestResult {
        require(GuildMembersCsv.isValidRoster(members)) {
            "A Platoon roster must stay within the member and name limits with unique UIDs"
        }
        val result = access { database ->
            database.ingestSnapshot(
                snapshot = PlatoonSnapshot(
                    id = 0,
                    capturedAt = capturedAt,
                    members = members.map(GuildMember::toSnapshotMember),
                    sourceFile = sourceFile,
                ),
                source = EvidenceSource.SNAPSHOT,
            )
        }
        when {
            result.duplicate -> Unit
            // A replayed older roster can rebuild membership periods across every week.
            result.historical -> recordAllLiveWeeklyReports()
            else -> recordChangedWeeks(sequenceOf(capturedAt) + rosterBoundaryInstants(result, capturedAt))
        }
        return result
    }

    /**
     * A roster that joins or withdraws members can correlate each boundary to an exact activity
     * time anywhere after the previous roster, possibly in an earlier week. Those events are
     * observed at [capturedAt], so their times name every week the roster changed.
     */
    private fun rosterBoundaryInstants(result: SnapshotIngestResult, capturedAt: Instant): List<Instant> {
        if (result.joined + result.rejoined + result.left == 0) return emptyList()
        return try {
            access { it.eventInstantsObservedAt(capturedAt) }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to read roster boundary times; refreshing the roster's week", error)
            emptyList()
        }
    }

    fun ingestActivity(
        data: PlatoonActivityData,
        capturedAt: Instant = Instant.now(),
    ): ActivityIngestResult {
        val observations = PlatoonObservationPolicy.activity(data)
        val result = access { database -> database.ingestPlatoonActivity(
            observations = observations,
            capturedAt = capturedAt,
        ) }
        if (result.inserted > 0 || result.resolved > 0) {
            recordChangedWeeks(observations.asSequence().map { it.occurredAt })
        }
        return result
    }

    fun ingestUpdates(
        data: PlatoonUpdatesData,
        capturedAt: Instant = Instant.now(),
    ): UpdatesIngestResult {
        val observations = PlatoonObservationPolicy.updates(data)
        val result = access { database -> database.ingestPlatoonUpdates(
            observations = observations,
            capturedAt = capturedAt,
        ) }
        if (result.membershipEvents > 0 || result.patrolFacts > 0) {
            recordChangedWeeks(observations.asSequence().map { it.occurredAt })
        }
        return result
    }

    fun reconcileRetainedCsvFiles(
        directory: File = storageScope.retainedCsvDirectory(appContext),
    ): ImportResult {
        val result = access { database -> database.runInTransaction {
            var imported = 0
            var historical = 0
            var skipped = 0
            var invalid = 0
            val representedFiles = database.snapshotSourceFiles()
            var latestStructuredSnapshot = database.latestSnapshotIdentity()?.let {
                SnapshotIdentity(it.first, it.second)
            }
            directory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
                .filter { file ->
                    if (file.name in representedFiles) {
                        skipped += 1
                        false
                    } else {
                        true
                    }
                }
                .mapNotNull { file ->
                    val parsed = runCatching {
                        GuildMembersCsv.parse(file.readText(Charsets.UTF_8))
                    }.getOrNull()
                    val capturedAt = parsed?.logTime?.let {
                        runCatching { Instant.parse(it) }.getOrNull()
                    }
                    if (parsed == null || capturedAt == null) {
                        invalid += 1
                        null
                    } else {
                        RetainedCsv(file, capturedAt, parsed.members)
                    }
                }
                .sortedWith(compareBy(RetainedCsv::capturedAt, { it.file.name }))
                .forEach { file ->
                    val historicalOnly = latestStructuredSnapshot?.let {
                        SnapshotReconciler.isHistorical(
                            file.capturedAt,
                            file.file.name,
                            it.capturedAt,
                            it.sourceFile,
                        )
                    } ?: false
                    val result = database.ingestSnapshot(
                        PlatoonSnapshot(
                            id = 0,
                            capturedAt = file.capturedAt,
                            members = file.members.map(GuildMember::toSnapshotMember),
                            sourceFile = file.file.name,
                        ),
                        EvidenceSource.LEGACY_IMPORT,
                        historicalOnly = historicalOnly,
                        deferHistoricalMembershipReconciliation = historicalOnly,
                    )
                    if (result.duplicate) {
                        skipped += 1
                    } else if (historicalOnly) {
                        historical += 1
                    } else {
                        imported += 1
                        latestStructuredSnapshot = SnapshotIdentity(file.capturedAt, file.file.name)
                    }
                }

            if (historical > 0) database.reconcileSnapshotMembershipHistory()

            ImportResult(
                imported = imported,
                historical = historical,
                skipped = skipped,
                invalid = invalid,
            )
        } }
        if (result.imported > 0 || result.historical > 0) {
            recordAllLiveWeeklyReports()
        }
        return result
    }

    fun listSnapshots(limit: Int = 100): List<PlatoonSnapshot> =
        access { it.listSnapshots(limit) }

    fun representedSnapshotSources(): Set<String> =
        access(PlatoonDatabase::snapshotSourceFiles)

    fun hasSnapshotSource(sourceFile: String): Boolean =
        access { sourceFile in it.snapshotSourceFiles() }

    fun listSnapshotsForPeriod(from: Instant, until: Instant): List<PlatoonSnapshot> =
        access { it.listSnapshotsForPeriod(from, until) }

    fun listMemberStatuses(activeOnly: Boolean = false): List<MemberStatus> =
        access { it.listMemberStatuses(activeOnly) }

    fun listEvents(
        from: Instant,
        until: Instant,
        fromDate: LocalDate,
        untilDate: LocalDate,
    ): List<MemberEvent> = access { it.listEvents(from, until, fromDate, untilDate) }

    fun listDailyPatrolFacts(from: Instant, until: Instant): List<DailyPatrolFact> =
        access { it.listDailyPatrolFacts(from, until) }

    fun updateMember(uid: Long, name: String, note: String): Boolean =
        refreshingWeeksOf(uid, WeeklyHistoryWorkPolicy.MemberEdit.DISPLAY_ONLY) {
            access { it.updateMember(uid, name, note) }
        }

    fun deleteMember(uid: Long): Boolean = refreshingWeeksOf(
        uid,
        WeeklyHistoryWorkPolicy.MemberEdit.REMOVAL,
    ) {
        withExclusiveDatabase(storageScope) {
            val order = MemberOrderPreferences(appContext, storageScope.storageId)
            val previousOrder = order.read()
            val updatedOrder = previousOrder.filterNot { it == uid }
            if (updatedOrder != previousOrder) {
                check(order.write(updatedOrder)) { "Unable to update saved member order" }
            }
            try {
                PlatoonDatabase(appContext, storageScope.databaseName).use { database ->
                    val deleted = database.deleteMember(uid)
                    if (!deleted && updatedOrder != previousOrder) {
                        check(order.write(previousOrder)) {
                            "Unable to restore saved member order"
                        }
                    }
                    deleted
                }
            } catch (error: Exception) {
                if (updatedOrder != previousOrder && !order.write(previousOrder)) {
                    error.addSuppressed(
                        IllegalStateException("Unable to restore saved member order"),
                    )
                }
                throw error
            }
        }
    }

    fun updateMembershipPeriod(
        membershipPeriodId: Long,
        joined: MembershipBoundaryValue,
        left: MembershipBoundaryValue?,
        note: String,
    ): Boolean = refreshingWeeksOfPeriodOwner(membershipPeriodId) {
        access { it.updateMembershipPeriod(membershipPeriodId, joined, left, note) }
    }

    fun deleteMembershipPeriod(membershipPeriodId: Long): Boolean =
        refreshingWeeksOfPeriodOwner(membershipPeriodId) {
            access { it.deleteMembershipPeriod(membershipPeriodId) }
        }

    fun addWithdrawnMember(
        uid: Long,
        name: String,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue,
        note: String,
    ): Boolean = refreshingWeeksOf(uid) {
        access { it.addWithdrawnMember(uid, name, joined, withdrew, note) }
    }

    fun addMembershipPeriod(
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Boolean = refreshingWeeksOf(uid) {
        access { it.addMembershipPeriod(uid, joined, withdrew, note) }
    }

    fun addWeeklyNote(periodStartEpochDay: Long, gameDayEpochDay: Long, text: String): Long =
        access { it.addWeeklyNote(periodStartEpochDay, gameDayEpochDay, text) }.also {
            recordLiveWeeklyRevision(LocalDate.ofEpochDay(periodStartEpochDay))
        }

    fun listWeeklyNotes(periodStartEpochDay: Long): List<WeeklyNote> =
        access { it.listWeeklyNotes(periodStartEpochDay) }

    fun deleteWeeklyNote(id: Long): Boolean {
        val periodStart = access { it.weeklyNotePeriodStart(id) } ?: return false
        return access { it.deleteWeeklyNote(id) }.also { changed ->
            if (changed) recordLiveWeeklyRevision(periodStart)
        }
    }

    fun listWeeklyOverrides(periodStartEpochDay: Long): List<WeeklyCellOverride> =
        access { it.listWeeklyOverrides(periodStartEpochDay) }

    /** The displayed report (a restored revision when selected) without writing history. */
    fun buildWeeklyReport(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant = Instant.now(),
    ): WeeklyReportBuilder.Report = readWeeklyTableRevision(referenceDay, zoneId, asOf).report

    /**
     * The weekly screen's load: returns the displayed revision and records the live projection
     * it was derived from, so the history list includes what the user saw. History is a derived
     * audit trail, so a revision it cannot record still renders. A stored selection that no
     * longer decodes is cleared and the live projection is shown instead.
     */
    fun buildWeeklyTableRevision(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant = Instant.now(),
    ): WeeklyTableRevision {
        val live = buildLiveWeeklyRevision(referenceDay, zoneId, asOf)
        try {
            recordHistory(live, Instant.now(), clearActiveOnChange = false)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to record weekly history for ${live.report.periodStart}", error)
        }
        return activeRevision(live) ?: live.also {
            access { database ->
                database.clearActiveWeeklyReportHistory(live.report.periodStart.toEpochDay())
            }
        }
    }

    /** Like [buildWeeklyTableRevision] but strictly read-only. */
    fun readWeeklyTableRevision(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant = Instant.now(),
    ): WeeklyTableRevision {
        val live = buildLiveWeeklyRevision(referenceDay, zoneId, asOf)
        return activeRevision(live) ?: live
    }

    /** The selected historical revision, [live] when none is selected, or null if undecodable. */
    private fun activeRevision(live: WeeklyTableRevision): WeeklyTableRevision? {
        val payload = access {
            it.activeWeeklyReportHistoryPayload(live.report.periodStart.toEpochDay())
        } ?: return live
        return runCatching { WeeklyReportHistoryCodec.decodeRevision(payload) }.getOrNull()
    }

    private fun buildLiveWeeklyRevision(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant,
    ): WeeklyTableRevision {
        val inputs = readRevisionInputs(PlatoonPeriods.weekStart(referenceDay), zoneId, asOf)
        val report = WeeklyReportBuilder.build(
            referenceDay = referenceDay,
            zoneId = zoneId,
            snapshots = inputs.snapshots,
            membershipEvents = inputs.reportEvents,
            overrides = inputs.overrides,
            dailyPatrolFacts = inputs.dailyPatrolFacts,
            asOf = asOf,
        )
        val membershipEvents = inputs.revisionEvents.filter {
            it.type in MembershipEventPresentation.displayedTypes &&
                it.source in MembershipEventPresentation.displayedSources
        }.take(MAX_REVISION_EVENTS)
            .map { it.copy(note = it.note.boundedUtf8(MAX_REVISION_NOTE_BYTES)) }
        val reportUids = report.members.mapTo(mutableSetOf()) { it.uid }
        val relevantNameUids = reportUids + membershipEvents.map { it.uid }
        return ManagementConsistencyAudit.requireValid(WeeklyTableRevision(
            report = report,
            membershipEvents = membershipEvents,
            notes = inputs.notes
                .take(MAX_REVISION_NOTES)
                .map { it.copy(text = it.text.boundedUtf8(MAX_REVISION_NOTE_BYTES)) },
            memberNamesByUid = inputs.statuses.asSequence()
                .filter { it.uid in relevantNameUids }
                .take(MAX_REVISION_NAMES)
                .associate { it.uid to it.name.boundedUtf8(MAX_REVISION_NAME_BYTES) },
            memberPrivateNotesByUid = inputs.statuses.asSequence()
                .filter { it.uid in reportUids }
                .associate { it.uid to it.note.boundedUtf8(MAX_REVISION_PRIVATE_NOTE_BYTES) },
        ))
    }

    /** Every input of one weekly revision, read under a single consistent database state. */
    private fun readRevisionInputs(
        periodStart: LocalDate,
        zoneId: ZoneId,
        asOf: Instant,
    ): RevisionInputs = access { database ->
        database.readConsistently {
            val roster = readRosterInputs(database, periodStart, zoneId, asOf)
            RevisionInputs(
                snapshots = roster.snapshots,
                reportEvents = roster.reportEvents,
                revisionEvents = roster.revisionEvents,
                overrides = database.listWeeklyOverrides(periodStart.toEpochDay()),
                dailyPatrolFacts = database.listDailyPatrolFacts(
                    PlatoonPeriods.periodStartInstant(periodStart, zoneId),
                    PlatoonPeriods.periodStartInstant(periodStart.plusDays(7), zoneId),
                ),
                notes = database.listWeeklyNotes(periodStart.toEpochDay()),
                statuses = database.listMemberStatuses(),
            )
        }
    }

    private fun readRosterInputs(
        database: PlatoonDatabase,
        periodStart: LocalDate,
        zoneId: ZoneId,
        asOf: Instant,
    ): RosterInputs {
        val from = PlatoonPeriods.periodStartInstant(periodStart, zoneId)
        val until = PlatoonPeriods.periodStartInstant(periodStart.plusDays(7), zoneId)
        val snapshots = database.listSnapshotsForPeriod(from, until)
        val membershipEventFrom = minOf(
            from,
            snapshots.minOfOrNull(PlatoonSnapshot::capturedAt) ?: Instant.EPOCH,
        )
        return RosterInputs(
            snapshots = snapshots,
            reportEvents = database.listEvents(
                membershipEventFrom,
                minOf(until, asOf.plusMillis(1)),
                PlatoonPeriods.gameDay(membershipEventFrom, zoneId),
                periodStart.plusDays(7),
            ),
            // The Join / Withdraw section uses calendar-day bounds rather than 05:00 resets.
            revisionEvents = database.listEvents(
                periodStart.atStartOfDay(zoneId).toInstant(),
                periodStart.plusDays(7).atStartOfDay(zoneId).toInstant(),
                periodStart,
                periodStart.plusDays(7),
            ),
        )
    }

    fun listAllWeeklyReports(
        zoneId: ZoneId,
        asOf: Instant = Instant.now(),
    ): List<WeeklyReportBuilder.Report> = WeeklyReportRange
        .periodStarts(access { it.listWeeklyEvidenceDays(zoneId) })
        .map { buildWeeklyReport(it, zoneId, asOf) }

    fun replaceWeeklyOverrides(
        periodStartEpochDay: Long,
        overrides: List<WeeklyCellOverride>,
    ) {
        access { it.replaceWeeklyOverrides(periodStartEpochDay, overrides) }
        val day = LocalDate.ofEpochDay(periodStartEpochDay)
        val zone = gameZone()
        recordLiveWeeklyRevisionSafely(day, zone)
    }

    fun listWeeklyReportHistory(periodStart: LocalDate): List<WeeklyReportHistoryEntry> =
        access { it.listWeeklyReportHistory(periodStart.toEpochDay()) }

    fun weeklyReportHistory(id: Long, periodStart: LocalDate): WeeklyReportBuilder.Report? =
        access { it.weeklyReportHistoryPayload(id, periodStart.toEpochDay()) }
            ?.let { payload -> runCatching { WeeklyReportHistoryCodec.decode(payload) }.getOrNull() }

    fun restoreWeeklyReportHistory(id: Long, periodStart: LocalDate): Boolean =
        access { it.activateWeeklyReportHistory(id, periodStart.toEpochDay()) }

    fun showLiveWeeklyReport(periodStart: LocalDate): Boolean =
        access { it.clearActiveWeeklyReportHistory(periodStart.toEpochDay()) }

    fun rebuildWeeklyHistoryForTimeZoneChange(zoneId: ZoneId) {
        withExclusiveDatabase(storageScope) {
            val recordedAt = Instant.now()
            val replacements = WeeklyReportRange
                .periodStarts(access { it.listWeeklyEvidenceDays(zoneId) })
                .map { periodStart ->
                    val encoded = WeeklyReportHistoryCodec.encode(
                        buildLiveWeeklyRevision(periodStart, zoneId, recordedAt),
                    )
                    WeeklyReportHistoryReplacement(
                        periodStartEpochDay = periodStart.toEpochDay(),
                        recordedAt = recordedAt,
                        fingerprint = encoded.fingerprint,
                        payload = encoded.payload,
                    )
                }
            access { it.replaceWeeklyReportHistory(replacements) }
        }
    }

    private fun recordChangedWeeks(instants: Iterable<Instant>) =
        recordChangedWeeks(instants.asSequence())

    /**
     * Refreshes the weeks that evidence at [instants] can change, including neighbouring weeks
     * that read it across a 05:00 reset (see [WeeklyHistoryWorkPolicy.affectedPeriodStarts]).
     * If the neighbour scan fails, the instants' own game weeks are still refreshed.
     */
    private fun recordChangedWeeks(instants: Sequence<Instant>) {
        val zone = gameZone()
        val changed = instants.toList()
        val weeks = try {
            WeeklyHistoryWorkPolicy.affectedPeriodStarts(
                instants = changed.asSequence(),
                zoneId = zone,
                listedPeriodStarts = weeklyPeriodStarts(zone),
                nextSnapshotAfter = { instant -> access { it.firstSnapshotCapturedAfter(instant) } },
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to scope neighbouring weekly history; refreshing own weeks", error)
            WeeklyHistoryWorkPolicy.affectedPeriodStarts(changed.asSequence(), zone, emptyList()) { null }
        }
        weeks.forEach { periodStart -> recordLiveWeeklyRevisionSafely(periodStart, zone) }
    }

    /** Refreshes every derived weekly revision after a primary-data mutation. */
    private fun recordAllLiveWeeklyReports() {
        val zone = gameZone()
        weeklyPeriodStarts(zone).forEach { periodStart ->
            recordLiveWeeklyRevisionSafely(periodStart, zone)
        }
    }

    private fun weeklyPeriodStarts(zone: ZoneId): List<LocalDate> =
        WeeklyReportRange.periodStarts(access { it.listWeeklyEvidenceDays(zone) })

    /**
     * Runs a mutation confined to one member's rows and refreshes only the weeks whose revision
     * can show that member before or after it: weeks whose period-end roster or Join / Withdraw
     * section contains the member. Weekly notes are manual and cite no member events, so every
     * other row, name, and note of a revision depends on other members' evidence alone. If the
     * pre-scan fails, every week is refreshed so the mutation stays authoritative.
     */
    private fun refreshingWeeksOf(
        uid: Long?,
        edit: WeeklyHistoryWorkPolicy.MemberEdit = WeeklyHistoryWorkPolicy.MemberEdit.MEMBERSHIP,
        mutation: () -> Boolean,
    ): Boolean {
        val zone = gameZone()
        val asOf = Instant.now()
        val before = uid?.let {
            runCatching { weeksShowingMember(it, zone, asOf, weeklyPeriodStarts(zone)) }.getOrNull()
        }
        if (!mutation()) return false
        try {
            WeeklyHistoryWorkPolicy.memberEditPeriodStarts(
                edit = edit,
                showingBefore = before,
                listedAfter = { weeklyPeriodStarts(zone) },
                showingAfter = { listed ->
                    weeksShowingMember(requireNotNull(uid), zone, asOf, listed)
                },
            ).forEach { recordLiveWeeklyRevisionSafely(it, zone) }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to scope weekly history refresh; refreshing every week", error)
            recordAllLiveWeeklyReports()
        }
        return true
    }

    private fun refreshingWeeksOfPeriodOwner(
        membershipPeriodId: Long,
        mutation: () -> Boolean,
    ): Boolean = refreshingWeeksOf(
        runCatching { access { it.membershipPeriodOwner(membershipPeriodId) } }.getOrNull(),
        mutation = mutation,
    )

    /** The [listed] weeks whose revision shows [uid]; each loads one week's roster inputs. */
    private fun weeksShowingMember(
        uid: Long,
        zone: ZoneId,
        asOf: Instant,
        listed: List<LocalDate>,
    ): Set<LocalDate> =
        listed.filterTo(mutableSetOf()) { periodStart ->
            val inputs = access { database ->
                database.readConsistently { readRosterInputs(database, periodStart, zone, asOf) }
            }
            inputs.revisionEvents.any { it.uid == uid } ||
                uid in WeeklyReportBuilder.rosterAtPeriodEnd(
                    periodStart,
                    zone,
                    inputs.snapshots,
                    inputs.reportEvents,
                    asOf,
                )
        }

    private fun recordHistory(
        revision: WeeklyTableRevision,
        recordedAt: Instant,
        clearActiveOnChange: Boolean,
    ) {
        val encoded = WeeklyReportHistoryCodec.encode(revision)
        access { database ->
            database.recordWeeklyReportHistory(
                periodStartEpochDay = revision.report.periodStart.toEpochDay(),
                recordedAt = recordedAt,
                fingerprint = encoded.fingerprint,
                payload = encoded.payload,
                clearActiveOnChange = clearActiveOnChange,
            )
        }
    }

    private fun recordLiveWeeklyRevision(periodStart: LocalDate) {
        val zone = gameZone()
        recordLiveWeeklyRevisionSafely(periodStart, zone)
    }

    /**
     * Records a derived revision without changing the result of an already committed mutation.
     * On failure the restored selection is cleared so the next render uses live data.
     */
    private fun recordLiveWeeklyRevisionSafely(periodStart: LocalDate, zone: ZoneId) {
        try {
            val now = Instant.now()
            recordHistory(
                buildLiveWeeklyRevision(periodStart, zone, now),
                now,
                clearActiveOnChange = true,
            )
        } catch (historyError: Exception) {
            try {
                access { database ->
                    database.clearActiveWeeklyReportHistory(periodStart.toEpochDay())
                }
            } catch (clearError: Exception) {
                historyError.addSuppressed(clearError)
            }
            Log.e(
                TAG,
                "Unable to refresh weekly history for $periodStart; live data remains authoritative",
                historyError,
            )
        }
    }

    private fun <T> access(block: (PlatoonDatabase) -> T): T =
        withDatabase(appContext, storageScope, block)

    data class ImportResult(
        val imported: Int,
        val historical: Int,
        val skipped: Int,
        val invalid: Int,
    )

    private data class RetainedCsv(
        val file: File,
        val capturedAt: Instant,
        val members: List<GuildMember>,
    )

    private data class SnapshotIdentity(
        val capturedAt: Instant,
        val sourceFile: String?,
    )

    private class RosterInputs(
        val snapshots: List<PlatoonSnapshot>,
        val reportEvents: List<MemberEvent>,
        val revisionEvents: List<MemberEvent>,
    )

    private class RevisionInputs(
        val snapshots: List<PlatoonSnapshot>,
        val reportEvents: List<MemberEvent>,
        val revisionEvents: List<MemberEvent>,
        val overrides: List<WeeklyCellOverride>,
        val dailyPatrolFacts: List<DailyPatrolFact>,
        val notes: List<WeeklyNote>,
        val statuses: List<MemberStatus>,
    )

    companion object {
        const val RETAINED_CSV_DIRECTORY = "guild-members"
        private const val MAX_REVISION_EVENTS = WeeklyReportHistoryCodec.MAX_EVENTS
        private const val MAX_REVISION_NOTES = WeeklyReportHistoryCodec.MAX_NOTES
        private const val MAX_REVISION_NAMES = WeeklyReportHistoryCodec.MAX_CONTEXT_MEMBERS
        private const val MAX_REVISION_NAME_BYTES = WeeklyReportHistoryCodec.MAX_STRING_BYTES
        private const val MAX_REVISION_NOTE_BYTES = WeeklyReportHistoryCodec.MAX_NOTE_BYTES
        private const val MAX_REVISION_PRIVATE_NOTE_BYTES = WeeklyReportHistoryCodec.MAX_PRIVATE_NOTE_BYTES
        private const val TAG = "GF2PlatoonRepository"
        /*
         * Global lock order; acquire left to right and never in reverse:
         * CsvImportCheckpointManager scope state -> maintenanceLock -> PlatoonDatabase monitor ->
         * PlatoonProfileRegistry lock -> settings preference locks. databaseLock only guards the
         * helper cache; it is taken after the maintenance lock and only closes evicted helpers.
         */
        private val databaseLock = Any()
        private val maintenanceLock = ReentrantReadWriteLock(true)

        internal fun holdsSharedDatabaseAccess(): Boolean = maintenanceLock.readHoldCount > 0

        private val databaseInstances = mutableMapOf<String, PlatoonDatabase>()

        private fun database(context: Context, scope: PlatoonStorageScope): PlatoonDatabase =
            synchronized(databaseLock) {
                databaseInstances[scope.databaseName]
                    ?: PlatoonDatabase(context, scope.databaseName).also {
                        databaseInstances[scope.databaseName] = it
                    }
            }

        private fun <T> withDatabase(
            context: Context,
            scope: PlatoonStorageScope,
            block: (PlatoonDatabase) -> T,
        ): T = maintenanceLock.readLock().withLock {
            block(database(context, scope))
        }

        internal fun <T> withExclusiveDatabase(
            scope: PlatoonStorageScope,
            block: () -> T,
        ): T =
            maintenanceLock.writeLock().withLock {
                synchronized(databaseLock) {
                    databaseInstances.remove(scope.databaseName)?.close()
                }
                block()
            }

    }
}

private fun String.boundedUtf8(maximumBytes: Int): String {
    if (toByteArray(Charsets.UTF_8).size <= maximumBytes) return this
    val result = StringBuilder()
    var byteCount = 0
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        val character = String(Character.toChars(codePoint))
        val characterBytes = character.toByteArray(Charsets.UTF_8).size
        if (byteCount + characterBytes > maximumBytes) break
        result.append(character)
        byteCount += characterBytes
        offset += Character.charCount(codePoint)
    }
    return result.toString()
}

private fun GuildMember.toSnapshotMember() = SnapshotMember(
    uid = uid.toLong(),
    name = name,
    level = level.toLong(),
    weeklyMerit = weeklyMerit.toLong(),
    totalMerit = totalMerit.toLong(),
    highScore = highScore.toLong(),
    totalScore = totalScore.toLong(),
    lastLogin = lastLogin.toLong(),
)
