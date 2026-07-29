package dev.gf2log.app.management

import android.content.Context
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonUpdatesData
import java.io.File
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

class PlatoonRepository(context: Context) {
    private val appContext = context.applicationContext
    private val migrationPreferences = appContext.getSharedPreferences(
        MIGRATION_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun ingest(
        capturedAt: Instant,
        members: List<GuildMember>,
        sourceFile: String?,
    ): SnapshotIngestResult = access { database ->
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

    fun ingestActivity(
        data: PlatoonActivityData,
        capturedAt: Instant = Instant.now(),
    ): ActivityIngestResult = access { database ->
        database.ingestPlatoonActivity(
            observations = data.entries.mapNotNull {
                if (it.occurredAt == 0u || it.actionId == 0u || it.memberName.isBlank()) {
                    null
                } else {
                    PlatoonActivityObservation(
                        occurredAt = Instant.ofEpochSecond(it.occurredAt.toLong()),
                        actionId = it.actionId.toLong(),
                        kind = it.kind.toLong(),
                        memberName = it.memberName,
                    )
                }
            },
            capturedAt = capturedAt,
        )
    }

    fun ingestUpdates(
        data: PlatoonUpdatesData,
        capturedAt: Instant = Instant.now(),
    ): UpdatesIngestResult = access { database ->
        database.ingestPlatoonUpdates(
            observations = data.entries.mapNotNull { entry ->
                if (entry.occurredAt == 0u || entry.kind == 0u) {
                    null
                } else {
                    PlatoonUpdateObservation(
                        kind = entry.kind.toLong(),
                        occurredAt = Instant.ofEpochSecond(entry.occurredAt.toLong()),
                        members = entry.members.mapNotNull { member ->
                            if (member.uid == 0u || member.name.isBlank()) {
                                null
                            } else {
                                PlatoonUpdateMemberObservation(
                                    role = member.role.toLong(),
                                    uid = member.uid.toLong(),
                                    name = member.name,
                                )
                            }
                        },
                    )
                }
            },
            capturedAt = capturedAt,
        )
    }

    fun importLegacyCsvFiles(directory: File): ImportResult = access { database ->
        if (migrationPreferences.getBoolean(LEGACY_IMPORT_COMPLETE, false)) {
            return@access ImportResult(
                alreadyComplete = true,
                imported = 0,
                skipped = 0,
                invalid = 0,
            )
        }

        var imported = 0
        var skipped = 0
        var invalid = 0
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }
            .sortedBy(File::getName)
            .forEach { file ->
                val parsed = runCatching {
                    GuildMembersCsv.parse(file.readText(Charsets.UTF_8))
                }.getOrNull()
                val capturedAt = parsed?.logTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                if (parsed == null || capturedAt == null) {
                    invalid += 1
                    return@forEach
                }

                val result = database.ingestSnapshot(
                    PlatoonSnapshot(
                        id = 0,
                        capturedAt = capturedAt,
                        members = parsed.members.map(GuildMember::toSnapshotMember),
                        sourceFile = file.name,
                    ),
                    EvidenceSource.LEGACY_IMPORT,
                )
                if (result.duplicate) skipped += 1 else imported += 1
            }

        check(
            migrationPreferences.edit()
                .putBoolean(LEGACY_IMPORT_COMPLETE, true)
                .commit(),
        ) { "Failed to persist legacy import completion" }
        ImportResult(
            alreadyComplete = false,
            imported = imported,
            skipped = skipped,
            invalid = invalid,
        )
    }

    fun listSnapshots(limit: Int = 100): List<PlatoonSnapshot> =
        access { it.listSnapshots(limit) }

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

    fun updateTenure(
        tenureId: Long,
        joined: MembershipBoundaryValue,
        left: MembershipBoundaryValue?,
        note: String,
    ): Boolean = access { it.updateTenure(tenureId, joined, left, note) }

    fun addWithdrawnMember(
        uid: Long,
        name: String,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue,
        note: String,
    ): Boolean = access { it.addWithdrawnMember(uid, name, joined, withdrew, note) }

    fun addTenure(
        uid: Long,
        joined: MembershipBoundaryValue,
        withdrew: MembershipBoundaryValue?,
        note: String,
    ): Boolean = access { it.addTenure(uid, joined, withdrew, note) }

    fun addWeeklyNote(periodStartEpochDay: Long, gameDayEpochDay: Long, text: String): Long =
        access { it.addWeeklyNote(periodStartEpochDay, gameDayEpochDay, text) }

    fun listWeeklyNotes(periodStartEpochDay: Long): List<WeeklyNote> =
        access { it.listWeeklyNotes(periodStartEpochDay) }

    fun deleteWeeklyNote(id: Long): Boolean = access { it.deleteWeeklyNote(id) }

    fun listWeeklyOverrides(periodStartEpochDay: Long): List<WeeklyCellOverride> =
        access { it.listWeeklyOverrides(periodStartEpochDay) }

    fun replaceWeeklyOverrides(
        periodStartEpochDay: Long,
        overrides: List<WeeklyCellOverride>,
    ) = access { it.replaceWeeklyOverrides(periodStartEpochDay, overrides) }

    private fun <T> access(block: (PlatoonDatabase) -> T): T =
        withDatabase(appContext, block)

    data class ImportResult(
        val alreadyComplete: Boolean,
        val imported: Int,
        val skipped: Int,
        val invalid: Int,
    )

    companion object {
        private const val MIGRATION_PREFERENCES = "platoon_migrations"
        private const val LEGACY_IMPORT_COMPLETE = "legacy_csv_v1"
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

        internal fun markLegacyImportComplete(context: Context) {
            check(
                context.applicationContext.getSharedPreferences(
                    MIGRATION_PREFERENCES,
                    Context.MODE_PRIVATE,
                ).edit().putBoolean(LEGACY_IMPORT_COMPLETE, true).commit(),
            ) { "Failed to persist legacy import completion" }
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
