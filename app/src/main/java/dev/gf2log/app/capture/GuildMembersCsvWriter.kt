package dev.gf2log.app.capture

import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.ParsedPayload
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class GuildMembersCsvWriter(
    private val outputDirectory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val onBatchClosed: (CompletedBatch) -> Unit = {},
) : AutoCloseable {
    private var activeBatch: Batch? = null

    @Synchronized
    fun accept(payload: ParsedPayload, flowEnded: Boolean = false): SaveResult? {
        if (payload.payloadType != Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS) {
            closeActiveBatch()
            return null
        }

        val data = payload.data as? GuildMembersData ?: return null
        var batch = activeBatch
        if (batch == null || (batch.previousMessageId != 0 && batch.previousMessageId != payload.messageId)) {
            closeActiveBatch()
            batch = openBatch()
            activeBatch = batch
        }

        data.members.forEach { member ->
            batch.writer.appendLine(GuildMembersCsv.row(member, batch.logTime))
            batch.members[member.uid] = member
            batch.rows += 1
        }
        batch.writer.flush()
        batch.previousMessageId = payload.messageId

        val result = SaveResult(batch.file, batch.rows)
        if (flowEnded || (payload.messageId != 0 && payload.isEndOfMessage)) {
            closeActiveBatch()
        }
        return result
    }

    @Synchronized
    override fun close() {
        closeActiveBatch()
    }

    private fun openBatch(): Batch {
        outputDirectory.mkdirs()
        val instant = Instant.now(clock)
        val filenameStem = "gf2log_platoonmembers_${FILE_TIME_FORMAT.format(instant)}"
        val file = uniqueFile(filenameStem)
        val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
        writer.appendLine(GuildMembersCsv.HEADER)
        writer.flush()
        return Batch(file, LOG_TIME_FORMAT.format(instant), writer)
    }

    private fun uniqueFile(filenameStem: String): File {
        var suffix = 1
        var candidate = File(outputDirectory, "$filenameStem.csv")
        while (candidate.exists()) {
            suffix += 1
            candidate = File(outputDirectory, "${filenameStem}_$suffix.csv")
        }
        return candidate
    }

    private fun closeActiveBatch() {
        val batch = activeBatch ?: return
        activeBatch = null
        batch.writer.close()
        if (batch.members.isNotEmpty()) {
            onBatchClosed(
                CompletedBatch(
                    file = batch.file,
                    logTime = batch.logTime,
                    members = batch.members.values.toList(),
                ),
            )
        }
    }

    data class SaveResult(val file: File, val rowCount: Int)

    data class CompletedBatch(
        val file: File,
        val logTime: String,
        val members: List<GuildMember>,
    )

    private data class Batch(
        val file: File,
        val logTime: String,
        val writer: BufferedWriter,
        val members: LinkedHashMap<UInt, GuildMember> = linkedMapOf(),
        var previousMessageId: Int = -1,
        var rows: Int = 0,
    )

    companion object {
        internal const val OUTPUT_DIRECTORY = "guild-members"
        val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
        val LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
