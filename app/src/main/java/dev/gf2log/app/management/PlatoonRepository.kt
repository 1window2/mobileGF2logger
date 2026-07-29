package dev.gf2log.app.management

import android.content.Context
import dev.gf2log.app.capture.GuildMembersCsvWriter
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.PlatoonActivityData
import java.io.File
import java.time.Instant

class PlatoonRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = database(appContext)
    private val migrationPreferences = appContext.getSharedPreferences(
        MIGRATION_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun ingest(batch: GuildMembersCsvWriter.CompletedBatch): PlatoonDatabase.IngestResult =
        database.ingestSnapshot(
            snapshot = PlatoonSnapshot(
                id = 0,
                capturedAt = Instant.parse(batch.logTime),
                members = batch.members.map(GuildMember::toSnapshotMember),
                sourceFile = batch.file.name,
            ),
            source = EvidenceSource.SNAPSHOT,
        )

    fun ingestActivity(
        data: PlatoonActivityData,
        capturedAt: Instant = Instant.now(),
    ): PlatoonDatabase.ActivityIngestResult =
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

    fun importLegacyCsvFiles(directory: File): ImportResult {
        if (migrationPreferences.getBoolean(LEGACY_IMPORT_COMPLETE, false)) {
            return ImportResult(alreadyComplete = true, imported = 0, skipped = 0, invalid = 0)
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

        migrationPreferences.edit().putBoolean(LEGACY_IMPORT_COMPLETE, true).apply()
        return ImportResult(
            alreadyComplete = false,
            imported = imported,
            skipped = skipped,
            invalid = invalid,
        )
    }

    fun listSnapshots(limit: Int = 100): List<PlatoonSnapshot> = database.listSnapshots(limit)

    fun listMemberStatuses(activeOnly: Boolean = false): List<MemberStatus> =
        database.listMemberStatuses(activeOnly)

    fun listEvents(from: Instant, until: Instant): List<MemberEvent> =
        database.listEvents(from, until)

    fun listDailyPatrolFacts(from: Instant, until: Instant): List<DailyPatrolFact> =
        database.listDailyPatrolFacts(from, until)

    fun updateMember(uid: Long, name: String, note: String): Boolean =
        database.updateMember(uid, name, note)

    fun updateTenure(
        tenureId: Long,
        joinedAt: Instant?,
        leftAt: Instant?,
        note: String,
    ): Boolean = database.updateTenure(tenureId, joinedAt, leftAt, note)

    fun addWithdrawnMember(
        uid: Long,
        name: String,
        joinedAt: Instant?,
        withdrewAt: Instant?,
        note: String,
    ): Boolean = database.addWithdrawnMember(uid, name, joinedAt, withdrewAt, note)

    fun addTenure(
        uid: Long,
        joinedAt: Instant?,
        withdrewAt: Instant?,
        note: String,
    ): Boolean = database.addTenure(uid, joinedAt, withdrewAt, note)

    fun addWeeklyNote(periodStartEpochDay: Long, gameDayEpochDay: Long, text: String): Long =
        database.addWeeklyNote(periodStartEpochDay, gameDayEpochDay, text)

    fun listWeeklyNotes(periodStartEpochDay: Long): List<WeeklyNote> =
        database.listWeeklyNotes(periodStartEpochDay)

    fun deleteWeeklyNote(id: Long): Boolean = database.deleteWeeklyNote(id)

    fun listWeeklyOverrides(periodStartEpochDay: Long): List<WeeklyCellOverride> =
        database.listWeeklyOverrides(periodStartEpochDay)

    fun replaceWeeklyOverrides(
        periodStartEpochDay: Long,
        overrides: List<WeeklyCellOverride>,
    ) = database.replaceWeeklyOverrides(periodStartEpochDay, overrides)

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

        @Volatile
        private var databaseInstance: PlatoonDatabase? = null

        private fun database(context: Context): PlatoonDatabase =
            databaseInstance ?: synchronized(databaseLock) {
                databaseInstance ?: PlatoonDatabase(context).also { databaseInstance = it }
            }

        internal fun closeDatabaseForFileCopy() {
            synchronized(databaseLock) {
                databaseInstance?.close()
                databaseInstance = null
            }
        }

        internal fun markLegacyImportComplete(context: Context) {
            context.applicationContext.getSharedPreferences(
                MIGRATION_PREFERENCES,
                Context.MODE_PRIVATE,
            ).edit().putBoolean(LEGACY_IMPORT_COMPLETE, true).apply()
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
