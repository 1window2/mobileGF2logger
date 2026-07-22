package dev.gf2log.app.history

import dev.gf2log.protocol.ParsedPayloadTextFormatter
import dev.gf2log.protocol.model.ParsedPayload
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

class CaptureHistoryStore(
    private val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val displayZone: ZoneId? = null,
) {
    @Synchronized
    fun save(payload: ParsedPayload): Entry {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create history directory" }
        val instant = Instant.now(clock)
        val capturedAt = LOG_TIME_FORMAT.format(instant)
        val id = "${instant.toEpochMilli()}_${payload.payloadType}_${sequence.incrementAndGet()}.txt"
        val destination = File(directory, id)
        val temporary = File.createTempFile("packet_", ".tmp", directory)
        try {
            temporary.writeText(ParsedPayloadTextFormatter.format(payload, capturedAt), Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath())
            }
        } finally {
            temporary.delete()
        }
        destination.setLastModified(instant.toEpochMilli())
        trimToLimit()
        return destination.toEntry()
    }

    @Synchronized
    fun list(): List<Entry> {
        directory.mkdirs()
        trimToLimit()
        return historyFiles()
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .map { it.toEntry() }
    }

    @Synchronized
    fun read(id: String): String? {
        if (id.isBlank() || File(id).name != id) return null
        val file = File(directory, id)
        if (!file.isFile || file.parentFile?.canonicalFile != directory.canonicalFile) return null
        return file.readText(Charsets.UTF_8)
    }

    @Synchronized
    fun delete(ids: Collection<String>): Int = ids
        .asSequence()
        .filter { it.isNotBlank() && File(it).name == it }
        .map { File(directory, it) }
        .filter { it.isFile && it.parentFile?.canonicalFile == directory.canonicalFile }
        .count(File::delete)

    private fun trimToLimit() {
        val oldestFirst = historyFiles()
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        oldestFirst.take((oldestFirst.size - MAX_ENTRIES).coerceAtLeast(0)).forEach(File::delete)
    }

    private fun historyFiles(): List<File> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "txt" }

    private fun File.toEntry(): Entry = Entry(
        id = name,
        title = TITLE_TIME_FORMAT
            .withZone(displayZone ?: ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(lastModified())),
        payloadType = name.split('_').getOrNull(1)?.toIntOrNull(),
    )

    data class Entry(
        val id: String,
        val title: String,
        val payloadType: Int?,
    )

    companion object {
        const val MAX_ENTRIES = 100
        const val HISTORY_DIRECTORY = "capture-history"
        private val sequence = AtomicLong()
        private val LOG_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
        private val TITLE_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yy/MM/dd HH:mm:ss")
    }
}
