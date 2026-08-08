package dev.gf2log.app.management

import dev.gf2log.protocol.GuildMembersCsv
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Validates and durably retains user-selected Platoon roster CSV evidence. */
class PlatoonCsvImportStore(private val directory: File) {
    // Function Name: retain
    // Description:
    // - Reads a bounded UTF-8 roster CSV, validates its complete schema, and hashes its bytes.
    // - Publishes the file under a deterministic identity with a durable temporary write.
    // Parameters:
    // - input: User-selected document stream owned by the caller.
    // Returns:
    // - Stable retained file identity and whether identical bytes already existed.
    fun retain(input: InputStream): RetainResult {
        val bytes = readBounded(input)
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val parsed = requireNotNull(GuildMembersCsv.parse(text)) { "Invalid Platoon CSV" }
        require(parsed.members.isNotEmpty()) { "Platoon CSV contains no roster members" }
        val capturedAt = Instant.parse(parsed.logTime)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(HASH_BYTES)
            .joinToString("") { "%02x".format(it) }
        val file = File(
            directory,
            "import-${FILE_TIME.format(capturedAt)}-$hash.csv",
        )
        directory.mkdirs()
        require(directory.isDirectory) { "Unable to create the Platoon CSV directory" }
        if (file.isFile) {
            require(file.readBytes().contentEquals(bytes)) { "CSV identity collision" }
            require(file.setLastModified(capturedAt.toEpochMilli())) {
                "Unable to preserve the imported Platoon CSV capture time"
            }
            return RetainResult(file, duplicate = true)
        }

        val temporary = File.createTempFile(".csv-import-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath())
            }
            if (!file.setLastModified(capturedAt.toEpochMilli())) {
                file.delete()
                error("Unable to preserve the imported Platoon CSV capture time")
            }
        } finally {
            temporary.delete()
        }
        return RetainResult(file, duplicate = false)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= MAX_BYTES) {
                "Platoon CSV exceeds the import size limit"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    data class RetainResult(val file: File, val duplicate: Boolean)

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val READ_BUFFER_BYTES = 8 * 1024
        const val HASH_BYTES = 10
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
    }
}
