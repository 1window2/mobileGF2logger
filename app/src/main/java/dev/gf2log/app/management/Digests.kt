package dev.gf2log.app.management

import java.io.File
import java.security.MessageDigest

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lowercase hexadecimal, two characters per byte. */
internal fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

internal fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

/** Streams the file through SHA-256 and returns the lowercase hexadecimal digest. */
internal fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}
