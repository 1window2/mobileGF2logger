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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class GuildMembersCsvWriter(
    private val outputDirectory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val publisher: (File, File) -> Unit = ::publishCompletedFile,
    private val onBatchClosed: (CompletedBatch) -> Unit = {},
) : AutoCloseable {
    private var activeBatch: Batch? = null

    @Synchronized
    fun accept(payload: ParsedPayload, flowEnded: Boolean = false): SaveResult? {
        if (payload.payloadType != Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS) {
            closeActiveBatch(completed = false)
            return null
        }

        val data = payload.data as? GuildMembersData ?: return null
        if (!GuildMembersCsv.hasValidMemberBounds(data.members)) {
            closeActiveBatch(completed = false)
            error("Platoon roster exceeds the member or name limit")
        }
        var batch = activeBatch
        if (batch == null || (batch.previousMessageId != 0 && batch.previousMessageId != payload.messageId)) {
            closeActiveBatch(completed = false)
            batch = openBatch()
            activeBatch = batch
        }
        if ((batch.members.keys + data.members.map(GuildMember::uid)).distinct().size >
            GuildMembersCsv.MAX_ROSTER_MEMBERS
        ) {
            closeActiveBatch(completed = false)
            error("Platoon roster exceeds the member limit")
        }

        data.members.forEach { member ->
            batch.writer.appendLine(GuildMembersCsv.row(member, batch.logTime))
            batch.members[member.uid] = member
            batch.rows += 1
        }
        batch.writer.flush()
        batch.previousMessageId = payload.messageId

        if (payload.messageId != 0 && payload.isEndOfMessage) {
            return closeActiveBatch(completed = true)?.let {
                SaveResult(it.file, batch.rows)
            }
        } else if (flowEnded) {
            closeActiveBatch(completed = false)
        }
        return null
    }

    @Synchronized
    override fun close() {
        closeActiveBatch(completed = false)
    }

    private fun openBatch(): Batch {
        outputDirectory.mkdirs()
        val instant = Instant.now(clock)
        val filenameStem = "gf2log_platoonmembers_${FILE_TIME_FORMAT.format(instant)}"
        val finalFile = uniqueFile(filenameStem)
        val workingFile = File(finalFile.parentFile, "${finalFile.name}.partial")
        val writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(workingFile), Charsets.UTF_8),
        )
        writer.appendLine(GuildMembersCsv.HEADER)
        writer.flush()
        return Batch(workingFile, finalFile, LOG_TIME_FORMAT.format(instant), writer)
    }

    private fun uniqueFile(filenameStem: String): File {
        var suffix = 1
        var candidate = File(outputDirectory, "$filenameStem.csv")
        while (candidate.exists() || File(candidate.parentFile, "${candidate.name}.partial").exists()) {
            suffix += 1
            candidate = File(outputDirectory, "${filenameStem}_$suffix.csv")
        }
        return candidate
    }

    private fun closeActiveBatch(completed: Boolean): CompletedBatch? {
        val batch = activeBatch ?: return null
        batch.writer.close()
        if (completed && batch.members.isNotEmpty()) {
            val result = CompletedBatch(
                file = batch.finalFile,
                logTime = batch.logTime,
                members = batch.members.values.toList(),
            )
            try {
                writeCanonicalRoster(batch)
                publishWithRetry(batch.workingFile, batch.finalFile)
            } catch (error: Exception) {
                activeBatch = null
                runCatching { onBatchClosed(result) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
                throw error
            }
            activeBatch = null
            onBatchClosed(result)
            return result
        } else {
            activeBatch = null
            batch.workingFile.delete()
        }
        return null
    }

    private fun writeCanonicalRoster(batch: Batch) {
        batch.workingFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(GuildMembersCsv.HEADER)
            batch.members.values.forEach { member ->
                writer.appendLine(GuildMembersCsv.row(member, batch.logTime))
            }
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

    data class SaveResult(val file: File, val rowCount: Int)

    data class CompletedBatch(
        val file: File,
        val logTime: String,
        val members: List<GuildMember>,
    )

    private data class Batch(
        val workingFile: File,
        val finalFile: File,
        val logTime: String,
        val writer: BufferedWriter,
        val members: LinkedHashMap<UInt, GuildMember> = linkedMapOf(),
        var previousMessageId: Int = -1,
        var rows: Int = 0,
    )

    companion object {
        private const val PUBLISH_ATTEMPTS = 2

        private fun publishCompletedFile(workingFile: File, finalFile: File) {
            try {
                Files.move(
                    workingFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(workingFile.toPath(), finalFile.toPath())
            }
        }

        val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
        val LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
