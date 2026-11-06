package dev.gf2log.app.history

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AtomicFiles {
    /** Moves [source] to [destination], atomically when the filesystem supports it. */
    fun move(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    /** Writes through a sibling temporary file so readers never observe partial content. */
    fun write(
        destination: File,
        content: ByteArray,
        temporaryPrefix: String,
        sync: (FileOutputStream) -> Unit = ::sync,
    ) {
        val temporary = File.createTempFile(temporaryPrefix, ".tmp", destination.parentFile)
        try {
            writeSynced(temporary, sync) { it.write(content) }
            move(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    /**
     * Writes [file] through [write] and syncs its data before returning. A rename orders only the
     * name, not the data, so a file renamed into place unsynced can survive a power loss under its
     * final name with zero length.
     */
    fun writeSynced(
        file: File,
        sync: (FileOutputStream) -> Unit = ::sync,
        write: (OutputStream) -> Unit,
    ) {
        FileOutputStream(file).use { output ->
            write(output)
            output.flush()
            sync(output)
        }
    }

    fun sync(output: FileOutputStream) = output.fd.sync()
}
