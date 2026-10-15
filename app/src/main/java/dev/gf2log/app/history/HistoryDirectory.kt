package dev.gf2log.app.history

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Path-safe access to one directory of parsed-packet text entries.
 *
 * Activities and the capture service construct their own stores, so every instance for the same
 * canonical directory shares one process-wide lock. Reads never delete; rotation belongs to writers.
 */
internal class HistoryDirectory(
    val directory: File,
    private val displayZone: ZoneId? = null,
) {
    val lock: Any = locks.computeIfAbsent(directory.canonicalPath) { Any() }

    fun ensureExists(description: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create $description directory" }
    }

    fun list(): List<CaptureHistoryStore.Entry> = synchronized(lock) {
        files()
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .map(::toEntry)
    }

    fun read(id: String): String? = synchronized(lock) {
        val file = resolveExisting(id) ?: return null
        if (file.length() > CaptureHistoryStore.MAX_ENTRY_BYTES) return null
        file.readText(Charsets.UTF_8)
    }

    fun delete(ids: Collection<String>): Int = synchronized(lock) {
        ids.mapNotNull(::resolveExisting).count(File::delete)
    }

    /** Returns the entry file named by [id] without allowing traversal; it may not exist yet. */
    fun resolve(id: String): File? =
        File(directory, id).takeIf { id.isNotBlank() && File(id).name == id }

    fun files(): List<File> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == ENTRY_EXTENSION }

    private fun resolveExisting(id: String): File? = resolve(id)?.takeIf {
        it.isFile && it.parentFile?.canonicalFile == directory.canonicalFile
    }

    fun toEntry(file: File) = CaptureHistoryStore.Entry(
        id = file.name,
        title = TITLE_TIME_FORMAT
            .withZone(displayZone ?: ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(file.lastModified())),
        payloadType = file.name.split('_').getOrNull(1)?.toIntOrNull(),
    )

    companion object {
        const val ENTRY_EXTENSION = "txt"
        private val locks = ConcurrentHashMap<String, Any>()
        private val TITLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")
    }
}
