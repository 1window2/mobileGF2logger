package dev.gf2log.app.history

import java.io.File
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
    fun write(destination: File, content: ByteArray, temporaryPrefix: String) {
        val temporary = File.createTempFile(temporaryPrefix, ".tmp", destination.parentFile)
        try {
            temporary.writeBytes(content)
            move(temporary, destination)
        } finally {
            temporary.delete()
        }
    }
}
