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
        if (!result.duplicate) recordChangedWeeks(setOf(capturedAt))
        return result
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
                        file.capturedAt.isBefore(it.capturedAt) ||
                            (file.capturedAt == it.capturedAt &&
                                file.file.name <= it.sourceFile.orEmpty())
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
        fromDate: java.time.LocalDate,
        untilDate: java.time.LocalDate,
    ): List<MemberEvent> = access { it.listEvents(from, until, fromDate, untilDate) }

    fun listDailyPatrolFacts(from: Instant, until: Instant): List<DailyPatrolFact> =
        access { it.listDailyPatrolFacts(from, until) }

    fun updateMember(uid: Long, name: String, note: String): Boolean =
        access { it.updateMember(uid, name, note) }.also { changed ->
            if (changed) recordAllLiveWeeklyReports()
        }

    fun deleteMember(uid: Long): Boolean {
        val deleted = withExclusiveDatabase(storageScope) {
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
        if (deleted) recordAllLiveWeeklyReports()
        return deleted
    }

    fun updateMembershipPeriod(
        membershipPeriodId: Long,
        joined: MembershipBoundaryValue,
        left: MembershipBoundaryValue?,
        note: String,
    ): Boolean = access { it.updateMembershipPeriod(membershipPeriodId, joined, left, note) }
        .also { changed -> if (changed) recordAllLiveWeeklyReports() }

    fun deleteMembershipPeriod(membershipPeriodId: Long): Boolean =
        access { it.deleteMembershipPeriod(membershipPeriodId) }
            .also { changed -> if (changed) recordAllLiveWeeklyReports() }

    fun addWithdrawnMember(
        uid: Long,
        name: String,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue,
        note: String,
    ): Boolean = access { it.addWithdrawnMember(uid, name, joined, withdrew, note) }
        .also { changed -> if (changed) recordAllLiveWeeklyReports() }

    fun addMembershipPeriod(
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Boolean = access { it.addMembershipPeriod(uid, joined, withdrew, note) }
        .also { changed -> if (changed) recordAllLiveWeeklyReports() }

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

    fun buildWeeklyReport(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant = Instant.now(),
    ): WeeklyReportBuilder.Report = buildWeeklyTableRevision(referenceDay, zoneId, asOf).report

    fun buildWeeklyTableRevision(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant = Instant.now(),
    ): WeeklyTableRevision {
        val live = buildLiveWeeklyRevision(referenceDay, zoneId, asOf)
        recordHistory(live, Instant.now(), clearActiveOnChange = false)
        val activePayload = access {
            it.activeWeeklyReportHistoryPayload(live.report.periodStart.toEpochDay())
        } ?: return live
        return runCatching { WeeklyReportHistoryCodec.decodeRevision(activePayload) }
            .getOrElse {
                access { database ->
                    database.clearActiveWeeklyReportHistory(live.report.periodStart.toEpochDay())
                }
                live
            }
    }

    private fun buildLiveWeeklyRevision(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant,
    ): WeeklyTableRevision {
        val report = buildLiveWeeklyReport(referenceDay, zoneId, asOf)
        val periodStart = report.periodStart
        val statuses = listMemberStatuses()
        val membershipEvents = listEvents(
            periodStart.atStartOfDay(zoneId).toInstant(),
            periodStart.plusDays(7).atStartOfDay(zoneId).toInstant(),
            periodStart,
            periodStart.plusDays(7),
        ).filter {
            it.type in MembershipEventPresentation.displayedTypes &&
                it.source in MembershipEventPresentation.displayedSources
        }.take(MAX_REVISION_EVENTS)
            .map { it.copy(note = it.note.boundedUtf8(MAX_REVISION_NOTE_BYTES)) }
        val relevantNameUids = (
            report.members.asSequence().map { it.uid } + membershipEvents.asSequence().map { it.uid }
            ).toSet()
        return ManagementConsistencyAudit.requireValid(WeeklyTableRevision(
            report = report,
            membershipEvents = membershipEvents,
            notes = listWeeklyNotes(periodStart.toEpochDay())
                .filterNot(WeeklyNote::isAutomatic)
                .take(MAX_REVISION_NOTES)
                .map { it.copy(text = it.text.boundedUtf8(MAX_REVISION_NOTE_BYTES)) },
            memberNamesByUid = statuses.asSequence()
                .filter { it.uid in relevantNameUids }
                .take(MAX_REVISION_NAMES)
                .associate { it.uid to it.name.boundedUtf8(MAX_REVISION_NAME_BYTES) },
            memberPrivateNotesByUid = statuses.asSequence()
                .filter { status -> report.members.any { it.uid == status.uid } }
                .associate { it.uid to it.note.boundedUtf8(MAX_REVISION_PRIVATE_NOTE_BYTES) },
        ))
    }

    private fun buildLiveWeeklyReport(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant,
    ): WeeklyReportBuilder.Report {
        val periodStart = PlatoonPeriods.weekStart(referenceDay)
        val from = PlatoonPeriods.periodStartInstant(periodStart, zoneId)
        val until = PlatoonPeriods.periodStartInstant(periodStart.plusDays(7), zoneId)
        val snapshots = listSnapshotsForPeriod(from, until)
        val membershipEventFrom = minOf(
            from,
            snapshots.minOfOrNull(PlatoonSnapshot::capturedAt) ?: Instant.EPOCH,
        )
        return WeeklyReportBuilder.build(
            referenceDay = referenceDay,
            zoneId = zoneId,
            snapshots = snapshots,
            membershipEvents = listEvents(
                membershipEventFrom,
                minOf(until, asOf.plusMillis(1)),
                PlatoonPeriods.gameDay(membershipEventFrom, zoneId),
                periodStart.plusDays(7),
            ),
            overrides = listWeeklyOverrides(periodStart.toEpochDay()),
            dailyPatrolFacts = listDailyPatrolFacts(from, until),
            asOf = asOf,
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
        val zone = GameTimeZonePreferences.get(appContext, storageScope.storageId)
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

    private fun recordChangedWeeks(instants: Sequence<Instant>) {
        val zone = GameTimeZonePreferences.get(appContext, storageScope.storageId)
        WeeklyHistoryWorkPolicy.changedPeriodStarts(instants, zone)
            .forEach { periodStart ->
                recordLiveWeeklyRevisionSafely(periodStart, zone)
            }
    }

    // Function Name: recordAllLiveWeeklyReports
    // Description:
    // - Refreshes every derived weekly revision after a primary-data mutation.
    // - Keeps committed member or packet data authoritative when optional history maintenance fails.
    // Parameters:
    // - failFast: Propagates failures for explicit maintenance operations such as timezone rebuilds.
    private fun recordAllLiveWeeklyReports(failFast: Boolean = false) {
        val zone = GameTimeZonePreferences.get(appContext, storageScope.storageId)
        WeeklyReportRange.periodStarts(access { it.listWeeklyEvidenceDays(zone) })
            .forEach { periodStart ->
                if (failFast) {
                    val now = Instant.now()
                    recordHistory(
                        buildLiveWeeklyRevision(periodStart, zone, now),
                        now,
                        clearActiveOnChange = true,
                    )
                } else {
                    recordLiveWeeklyRevisionSafely(periodStart, zone)
                }
            }
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
        val zone = GameTimeZonePreferences.get(appContext, storageScope.storageId)
        recordLiveWeeklyRevisionSafely(periodStart, zone)
    }

    // Function Name: recordLiveWeeklyRevisionSafely
    // Description:
    // - Records a derived table revision without changing the result of an already committed mutation.
    // - Clears a restored projection on failure so the next screen render uses authoritative live data.
    // Parameters:
    // - periodStart: Sunday key of the weekly table to refresh.
    // - zone: Persisted game timezone used by every date boundary in the projection.
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

    companion object {
        const val RETAINED_CSV_DIRECTORY = "guild-members"
        private const val MAX_REVISION_EVENTS = 512
        private const val MAX_REVISION_NOTES = 128
        private const val MAX_REVISION_NAMES = 768
        private const val MAX_REVISION_NAME_BYTES = 1_024
        private const val MAX_REVISION_NOTE_BYTES = 16 * 1_024
        private const val MAX_REVISION_PRIVATE_NOTE_BYTES = 2 * 1_024
        private const val TAG = "GF2PlatoonRepository"
        private val databaseLock = Any()
        private val maintenanceLock = ReentrantReadWriteLock(true)

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
            scope: PlatoonStorageScope = PlatoonStorageScope(
                PlatoonProfileIdentity.LEGACY_STORAGE_ID,
            ),
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
