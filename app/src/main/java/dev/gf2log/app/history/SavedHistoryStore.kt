package dev.gf2log.app.history

import java.io.File

/** Explicitly saved history; capped at [MAX_ENTRIES] and never rotated. */
class SavedHistoryStore(
    directory: File,
) {
    private val entries = HistoryDirectory(directory)

    fun saveFrom(source: CaptureHistoryStore, ids: Collection<String>): SaveResult =
        synchronized(entries.lock) {
            entries.ensureExists("saved-history")

            var saved = 0
            var alreadySaved = 0
            var missing = 0
            var limitReached = false
            var storedCount = entries.files().size

            ids.distinct().forEach { id ->
                val destination = entries.resolve(id)
                if (destination == null) {
                    missing += 1
                    return@forEach
                }
                if (destination.isFile) {
                    alreadySaved += 1
                    return@forEach
                }

                val content = source.read(id)
                if (content == null) {
                    missing += 1
                    return@forEach
                }
                if (storedCount >= MAX_ENTRIES) {
                    limitReached = true
                    return@forEach
                }

                AtomicFiles.write(destination, content.toByteArray(Charsets.UTF_8), "saved_packet_")
                id.substringBefore('_').toLongOrNull()?.let(destination::setLastModified)
                saved += 1
                storedCount += 1
            }

            SaveResult(saved, alreadySaved, missing, limitReached)
        }

    fun list(): List<CaptureHistoryStore.Entry> = entries.list()

    fun read(id: String): String? = entries.read(id)

    fun delete(ids: Collection<String>): Int = entries.delete(ids)

    data class SaveResult(
        val saved: Int,
        val alreadySaved: Int,
        val missing: Int,
        val limitReached: Boolean,
    )

    companion object {
        const val MAX_ENTRIES = 50
        const val SAVED_HISTORY_DIRECTORY = "saved-history"
    }
}
