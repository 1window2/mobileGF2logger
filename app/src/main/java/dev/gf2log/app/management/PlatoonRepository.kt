package dev.gf2log.app.management

import android.content.Context
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

class PlatoonRepository(context: Context) {
    private val appContext = context.applicationContext

    init {
        PlatoonBackupManager.recoverInterruptedFullRestore(appContext)
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
        directory: File = File(appContext.filesDir, RETAINED_CSV_DIRECTORY),
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
        access { it.updateMember(uid, name, note) }

    fun deleteMember(uid: Long): Boolean {
        return withExclusiveDatabase {
            val order = MemberOrderPreferences(appContext)
            val previousOrder = order.read()
            val updatedOrder = previousOrder.filterNot { it == uid }
            if (updatedOrder != previousOrder) {
                check(order.write(updatedOrder)) { "Unable to update saved member order" }
            }
            try {
                PlatoonDatabase(appContext).use { database ->
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
    ): Boolean = access { it.updateMembershipPeriod(membershipPeriodId, joined, left, note) }

    fun deleteMembershipPeriod(membershipPeriodId: Long): Boolean =
        access { it.deleteMembershipPeriod(membershipPeriodId) }

    fun addWithdrawnMember(
        uid: Long,
        name: String,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue,
        note: String,
    ): Boolean = access { it.addWithdrawnMember(uid, name, joined, withdrew, note) }

    fun addMembershipPeriod(
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Boolean = access { it.addMembershipPeriod(uid, joined, withdrew, note) }

    fun addWeeklyNote(periodStartEpochDay: Long, gameDayEpochDay: Long, text: String): Long =
        access { it.addWeeklyNote(periodStartEpochDay, gameDayEpochDay, text) }

    fun listWeeklyNotes(periodStartEpochDay: Long): List<WeeklyNote> =
        access { it.listWeeklyNotes(periodStartEpochDay) }

    fun deleteWeeklyNote(id: Long): Boolean = access { it.deleteWeeklyNote(id) }

    fun listWeeklyOverrides(periodStartEpochDay: Long): List<WeeklyCellOverride> =
        access { it.listWeeklyOverrides(periodStartEpochDay) }

    fun buildWeeklyReport(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        asOf: Instant = Instant.now(),
    ): WeeklyReportBuilder.Report {
        val live = buildLiveWeeklyReport(referenceDay, zoneId, asOf)
        recordHistory(live, Instant.now(), clearActiveOnChange = false)
        val activePayload = access {
            it.activeWeeklyReportHistoryPayload(live.periodStart.toEpochDay())
        } ?: return live
        return runCatching { WeeklyReportHistoryCodec.decode(activePayload) }
            .getOrElse {
                access { database ->
                    database.clearActiveWeeklyReportHistory(live.periodStart.toEpochDay())
                }
                live
            }
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
        val zone = ZoneId.systemDefault()
        recordHistory(
            report = buildLiveWeeklyReport(day, zone, Instant.now()),
            recordedAt = Instant.now(),
            clearActiveOnChange = true,
        )
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

    private fun recordChangedWeeks(instants: Iterable<Instant>) =
        recordChangedWeeks(instants.asSequence())

    private fun recordChangedWeeks(instants: Sequence<Instant>) {
        val zone = ZoneId.systemDefault()
        WeeklyHistoryWorkPolicy.changedPeriodStarts(instants, zone)
            .forEach { periodStart ->
                val report = buildLiveWeeklyReport(periodStart, zone, Instant.now())
                recordHistory(report, Instant.now(), clearActiveOnChange = true)
            }
    }

    private fun recordAllLiveWeeklyReports() {
        val zone = ZoneId.systemDefault()
        WeeklyReportRange.periodStarts(access { it.listWeeklyEvidenceDays(zone) })
            .forEach { periodStart ->
                recordHistory(
                    buildLiveWeeklyReport(periodStart, zone, Instant.now()),
                    Instant.now(),
                    clearActiveOnChange = true,
                )
            }
    }

    private fun recordHistory(
        report: WeeklyReportBuilder.Report,
        recordedAt: Instant,
        clearActiveOnChange: Boolean,
    ) {
        val encoded = WeeklyReportHistoryCodec.encode(report)
        access { database ->
            database.recordWeeklyReportHistory(
                periodStartEpochDay = report.periodStart.toEpochDay(),
                recordedAt = recordedAt,
                fingerprint = encoded.fingerprint,
                payload = encoded.payload,
                clearActiveOnChange = clearActiveOnChange,
            )
        }
    }

    private fun <T> access(block: (PlatoonDatabase) -> T): T =
        withDatabase(appContext, block)

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
        private val databaseLock = Any()
        private val maintenanceLock = ReentrantReadWriteLock(true)

        @Volatile
        private var databaseInstance: PlatoonDatabase? = null

        private fun database(context: Context): PlatoonDatabase =
            databaseInstance ?: synchronized(databaseLock) {
                databaseInstance ?: PlatoonDatabase(context).also { databaseInstance = it }
            }

        private fun <T> withDatabase(
            context: Context,
            block: (PlatoonDatabase) -> T,
        ): T = maintenanceLock.readLock().withLock {
            block(database(context))
        }

        internal fun <T> withExclusiveDatabase(block: () -> T): T =
            maintenanceLock.writeLock().withLock {
                synchronized(databaseLock) {
                    databaseInstance?.close()
                    databaseInstance = null
                }
                block()
            }

    }
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
