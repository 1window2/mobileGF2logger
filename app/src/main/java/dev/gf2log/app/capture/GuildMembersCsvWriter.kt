package dev.gf2log.app.capture

import dev.gf2log.app.history.AtomicFiles
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.LogTime
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes each completed logical roster dataset once, deduplicated by UID. The stream parser
 * already merges continuation frames, so a roster payload that does not complete its logical
 * payload was cut short by another payload or by flow closure. Like the reference logger, such a
 * boundary ends the dataset; it is discarded instead of being merged with a later, unrelated roster.
 * An empty `.partial` file reserves the final name while the CSV is written.
 */
class GuildMembersCsvWriter(
    private val outputDirectory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val publisher: (File, File) -> Unit = AtomicFiles::move,
    private val onBatchClosed: (CompletedBatch) -> Unit = {},
) {
    @Synchronized
    fun accept(payload: ParsedPayload): SaveResult? {
        val data = payload.data as? GuildMembersData ?: return null
        check(GuildMembersCsv.hasValidMemberBounds(data.members)) {
            "Platoon roster exceeds the member or name limit"
        }
        if (payload.messageId == 0 || !payload.isEndOfMessage) return null
        val members = linkedMapOf<UInt, GuildMember>()
        data.members.forEach { member -> members[member.uid] = member }
        if (members.isEmpty()) return null
        return publish(members.values.toList())
    }

    private fun publish(members: List<GuildMember>): SaveResult {
        val batch = reserve(Instant.now(clock), members)
        try {
            batch.workingFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(GuildMembersCsv.HEADER)
                members.forEach { member ->
                    writer.appendLine(GuildMembersCsv.row(member, batch.completed.logTime))
                }
            }
            publishWithRetry(batch.workingFile, batch.completed.file)
        } catch (error: Exception) {
            // Structured ingestion still gets the completed batch; the caller sees the failure.
            runCatching { onBatchClosed(batch.completed) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
        onBatchClosed(batch.completed)
        return SaveResult(batch.completed.file, members.size)
    }

    private fun reserve(instant: Instant, members: List<GuildMember>): Reservation {
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Unable to create the roster directory"
        }
        val filenameStem = "gf2log_platoonmembers_${FILE_TIME_FORMAT.format(instant)}"
        var suffix = 1
        while (true) {
            val name = if (suffix == 1) "$filenameStem.csv" else "${filenameStem}_$suffix.csv"
            val finalFile = File(outputDirectory, name)
            val workingFile = File(outputDirectory, "$name.partial")
            if (!finalFile.exists() && workingFile.createNewFile()) {
                return Reservation(workingFile, CompletedBatch(finalFile, LogTime.format(instant), members))
            }
            suffix += 1
        }
    }

    private fun publishWithRetry(workingFile: File, finalFile: File) {
        var firstFailure: Exception? = null
        repeat(PUBLISH_ATTEMPTS) { attempt ->
            try {
                publisher(workingFile, finalFile)
                return
            } catch (error: Exception) {
                if (finalFile.isFile && !workingFile.exists()) return
                if (attempt == 0) {
                    firstFailure = error
                } else {
                    firstFailure?.let(error::addSuppressed)
                    throw error
                }
            }
        }
    }

    /** [rowCount] equals the number of distinct members written to [file]. */
    data class SaveResult(val file: File, val rowCount: Int)

    data class CompletedBatch(
        val file: File,
        val logTime: String,
        val members: List<GuildMember>,
    )

    private class Reservation(val workingFile: File, val completed: CompletedBatch)

    companion object {
        private const val PUBLISH_ATTEMPTS = 2

        private val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
