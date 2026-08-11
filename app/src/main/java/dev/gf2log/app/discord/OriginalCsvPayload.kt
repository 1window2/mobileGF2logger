package dev.gf2log.app.discord

import dev.gf2log.protocol.ParsedPacketTableParser

/** Extracts only the validated CSV body from a parsed-packet history envelope. */
object OriginalCsvPayload {
    fun extract(historyContent: String): String {
        require(historyContent.length <= ParsedPacketTableParser.MAX_CONTENT_CHARS)
        val normalized = historyContent.replace("\r\n", "\n").replace('\r', '\n')
        val separator = normalized.indexOf("\n\n")
        require(separator >= 0 && separator + 2 < normalized.length) {
            "Parsed packet does not contain an original CSV body"
        }
        requireNotNull(ParsedPacketTableParser.parse(normalized)) {
            "Parsed packet CSV failed bounded validation"
        }
        return normalized.substring(separator + 2).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_CSV_BYTES) {
                "Original CSV exceeds the Discord send limit"
            }
        }
    }

    const val MAX_CSV_BYTES = 512 * 1024
}
