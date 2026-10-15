package dev.gf2log.app.capture

import dev.gf2log.app.history.AtomicFiles
import dev.gf2log.protocol.Gfl2PayloadDecoder
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
 * Collects one logical roster dataset in memory and writes it once, deduplicated by UID, only after
 * protocol completion. An empty `.partial` file reserves the final name while the batch is open.
 */
class GuildMembersCsvWriter(
    private val outputDirectory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val publisher: (File, File) -> Unit = AtomicFiles::move,
    private val onBatchClosed: (CompletedBatch) -> Unit = {},
) : AutoCloseable {
    private var activeBatch: Batch? = null

    @Synchronized
    fun accept(payload: ParsedPayload, flowEnded: Boolean = false): SaveResult? {
        if (payload.payloadType != Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS) {
            discardActiveBatch()
            return null
        }

        val data = payload.data as? GuildMembersData ?: return null
        if (!GuildMembersCsv.hasValidMemberBounds(data.members)) {
            discardActiveBatch()
            error("Platoon roster exceeds the member or name limit")
        }
        var batch = activeBatch
        if (batch == null || (batch.previousMessageId != 0 && batch.previousMessageId != payload.messageId)) {
            discardActiveBatch()
            batch = openBatch()
            activeBatch = batch
        }
        if ((batch.members.keys + data.members.map(GuildMember::uid)).size >
            GuildMembersCsv.MAX_ROSTER_MEMBERS
        ) {
            discardActiveBatch()
            error("Platoon roster exceeds the member limit")
        }

        data.members.forEach { member -> batch.members[member.uid] = member }
        batch.previousMessageId = payload.messageId

        if (payload.messageId != 0 && payload.isEndOfMessage) return publishActiveBatch()
        if (flowEnded) discardActiveBatch()
        return null
    }

    @Synchronized
    override fun close() = discardActiveBatch()

    private fun openBatch(): Batch {
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Unable to create the roster directory"
        }
        val instant = Instant.now(clock)
        val filenameStem = "gf2log_platoonmembers_${FILE_TIME_FORMAT.format(instant)}"
        var suffix = 1
        while (true) {
            val name = if (suffix == 1) "$filenameStem.csv" else "${filenameStem}_$suffix.csv"
            val finalFile = File(outputDirectory, name)
            val workingFile = File(outputDirectory, "$name.partial")
            if (!finalFile.exists() && workingFile.createNewFile()) {
                return Batch(workingFile, finalFile, LogTime.format(instant))
            }
            suffix += 1
        }
    }

    private fun discardActiveBatch() {
        activeBatch?.workingFile?.delete()
        activeBatch = null
    }

    private fun publishActiveBatch(): SaveResult? {
        val batch = activeBatch ?: return null
        activeBatch = null
        if (batch.members.isEmpty()) {
            batch.workingFile.delete()
            return null
        }
        val completed = CompletedBatch(
            file = batch.finalFile,
            logTime = batch.logTime,
            members = batch.members.values.toList(),
        )
        try {
            batch.workingFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(GuildMembersCsv.HEADER)
                completed.members.forEach { member ->
                    writer.appendLine(GuildMembersCsv.row(member, completed.logTime))
                }
            }
            publishWithRetry(batch.workingFile, batch.finalFile)
        } catch (error: Exception) {
            // Structured ingestion still gets the completed batch; the caller sees the failure.
            runCatching { onBatchClosed(completed) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
        onBatchClosed(completed)
        return SaveResult(completed.file, completed.members.size)
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

    private class Batch(
        val workingFile: File,
        val finalFile: File,
        val logTime: String,
    ) {
        val members = linkedMapOf<UInt, GuildMember>()
        var previousMessageId: Int = -1
    }

    companion object {
        private const val PUBLISH_ATTEMPTS = 2

        private val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
