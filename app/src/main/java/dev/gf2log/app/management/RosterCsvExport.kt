package dev.gf2log.app.management

import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.LogTime
import dev.gf2log.protocol.model.GuildMember
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Home's "Export roster CSV" source: the newest stored roster, the one Home summarizes. Retained
 * CSV files are not used: a backup restore retires them, and an imported older CSV is newer on
 * disk than the latest capture. Deleted members are already absent from stored rosters.
 */
internal object RosterCsvExport {
    fun latest(repository: PlatoonRepository): PlatoonSnapshot? =
        repository.listSnapshots(limit = 1).firstOrNull()

    fun csvSnapshot(snapshot: PlatoonSnapshot): GuildMembersCsv.Snapshot = GuildMembersCsv.Snapshot(
        logTime = LogTime.format(snapshot.capturedAt),
        members = snapshot.members.map { member ->
            GuildMember(
                uid = member.uid.toUInt(),
                name = member.name,
                level = member.level.toUInt(),
                weeklyMerit = member.weeklyMerit.toUInt(),
                totalMerit = member.totalMerit.toUInt(),
                highScore = member.highScore.toUInt(),
                totalScore = member.totalScore.toUInt(),
                lastLogin = member.lastLogin.toUInt(),
            )
        },
    )

    /** The roster's own retained name when it has one, otherwise the capture naming scheme. */
    fun fileName(snapshot: PlatoonSnapshot): String =
        snapshot.sourceFile?.takeIf { it.matches(SAFE_FILE_NAME) }
            ?: "gf2log_platoonmembers_${FILE_TIME.format(snapshot.capturedAt)}.csv"

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]{1,120}\\.csv")
    private val FILE_TIME: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
}
