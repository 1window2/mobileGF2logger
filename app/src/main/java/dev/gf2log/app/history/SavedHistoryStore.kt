package dev.gf2log.app.history

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SavedHistoryStore(
    private val directory: File,
) {
    private val entries = CaptureHistoryStore(directory)

    @Synchronized
    fun saveFrom(source: CaptureHistoryStore, ids: Collection<String>): SaveResult {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create saved-history directory" }

        var saved = 0
        var alreadySaved = 0
        var missing = 0
        var limitReached = false
        var storedCount = savedFiles().size

        ids.distinct().forEach { id ->
            if (id.isBlank() || File(id).name != id) {
                missing += 1
                return@forEach
            }

            val destination = File(directory, id)
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

            writeAtomically(destination, content)
            id.substringBefore('_').toLongOrNull()?.let(destination::setLastModified)
            saved += 1
            storedCount += 1
        }

        return SaveResult(saved, alreadySaved, missing, limitReached)
    }

    fun list(): List<CaptureHistoryStore.Entry> = entries.list()

    fun read(id: String): String? = entries.read(id)

    fun delete(ids: Collection<String>): Int = entries.delete(ids)

    private fun writeAtomically(destination: File, content: String) {
        val temporary = File.createTempFile("saved_packet_", ".tmp", directory)
        try {
            temporary.writeText(content, Charsets.UTF_8)
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
    }

    private fun savedFiles(): List<File> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "txt" }

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
