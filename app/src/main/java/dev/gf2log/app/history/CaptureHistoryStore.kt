package dev.gf2log.app.history

import dev.gf2log.protocol.LogTime
import dev.gf2log.protocol.ParsedPayloadTextFormatter
import dev.gf2log.protocol.model.ParsedPayload
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Recent parsed-packet history; each save rotates out the earliest-recorded entries beyond
 * [MAX_ENTRIES]. Recording order comes from a sequence persisted in each entry ID, not from the
 * wall clock, so a clock moved backwards never makes a new capture the first one rotated out.
 */
class CaptureHistoryStore(
    directory: File,
    private val clock: Clock = Clock.systemUTC(),
    displayZone: ZoneId? = null,
) {
    private val entries = HistoryDirectory(directory, displayZone)

    fun save(payload: ParsedPayload): Entry = synchronized(entries.lock) {
        entries.ensureExists("history")
        val instant = Instant.now(clock)
        // Bounded while formatting, so a crafted payload cannot allocate its whole text first.
        val text = requireNotNull(
            ParsedPayloadTextFormatter.format(payload, LogTime.format(instant), MAX_ENTRY_BYTES),
        ) { "Parsed-packet history entry exceeds the storage limit" }
        val content = text.toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_ENTRY_BYTES) {
            "Parsed-packet history entry exceeds the storage limit"
        }
        val sequence = (entries.files().mapNotNull(::recordingSequence).maxOrNull() ?: 0L) + 1
        val id = "${instant.toEpochMilli()}_${payload.payloadType}_$SEQUENCE_PREFIX$sequence" +
            ".${HistoryDirectory.ENTRY_EXTENSION}"
        val destination = File(entries.directory, id)
        AtomicFiles.write(destination, content, "packet_")
        destination.setLastModified(instant.toEpochMilli())
        trimToLimit(keep = destination)
        entries.toEntry(destination)
    }

    fun list(): List<Entry> = entries.list()

    fun read(id: String): String? = entries.read(id)

    fun delete(ids: Collection<String>): Int = entries.delete(ids)

    /**
     * Deletes the earliest-recorded entries beyond [MAX_ENTRIES], never [keep]. Entries written
     * before IDs carried a persisted sequence count as recorded first, in modification order.
     */
    private fun trimToLimit(keep: File) {
        val files = entries.files()
        files.filter { it.name != keep.name }
            .sortedWith(
                compareBy<File> { recordingSequence(it) ?: 0L }
                    .thenBy { it.lastModified() }
                    .thenBy { it.name },
            )
            .take((files.size - MAX_ENTRIES).coerceAtLeast(0))
            .forEach(File::delete)
    }

    /** The persisted recording sequence in an entry ID, or null for an ID written without one. */
    private fun recordingSequence(file: File): Long? = file.nameWithoutExtension
        .split('_')
        .getOrNull(2)
        ?.takeIf { it.startsWith(SEQUENCE_PREFIX) }
        ?.removePrefix(SEQUENCE_PREFIX)
        ?.toLongOrNull()
        ?.takeIf { it > 0 }

    data class Entry(
        val id: String,
        val title: String,
        val payloadType: Int?,
    )

    companion object {
        const val MAX_ENTRIES = 100
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
        const val HISTORY_DIRECTORY = "capture-history"
        private const val SEQUENCE_PREFIX = "s"
    }
}
