package dev.gf2log.app.history

import dev.gf2log.protocol.LogTime
import dev.gf2log.protocol.ParsedPayloadTextFormatter
import dev.gf2log.protocol.model.ParsedPayload
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/** Recent parsed-packet history; each save rotates out the oldest entries beyond [MAX_ENTRIES]. */
class CaptureHistoryStore(
    directory: File,
    private val clock: Clock = Clock.systemUTC(),
    displayZone: ZoneId? = null,
) {
    private val entries = HistoryDirectory(directory, displayZone)

    fun save(payload: ParsedPayload): Entry = synchronized(entries.lock) {
        entries.ensureExists("history")
        val instant = Instant.now(clock)
        val content = ParsedPayloadTextFormatter.format(payload, LogTime.format(instant))
            .toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_ENTRY_BYTES) {
            "Parsed-packet history entry exceeds the storage limit"
        }
        val id = "${instant.toEpochMilli()}_${payload.payloadType}_${sequence.incrementAndGet()}" +
            ".${HistoryDirectory.ENTRY_EXTENSION}"
        val destination = File(entries.directory, id)
        AtomicFiles.write(destination, content, "packet_")
        destination.setLastModified(instant.toEpochMilli())
        trimToLimit()
        entries.toEntry(destination)
    }

    fun list(): List<Entry> = entries.list()

    fun read(id: String): String? = entries.read(id)

    fun delete(ids: Collection<String>): Int = entries.delete(ids)

    private fun trimToLimit() {
        val oldestFirst = entries.files()
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        oldestFirst.take((oldestFirst.size - MAX_ENTRIES).coerceAtLeast(0)).forEach(File::delete)
    }

    data class Entry(
        val id: String,
        val title: String,
        val payloadType: Int?,
    )

    companion object {
        const val MAX_ENTRIES = 100
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
        const val HISTORY_DIRECTORY = "capture-history"
        private val sequence = AtomicLong()
    }
}
