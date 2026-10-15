package dev.gf2log.app.discord

import dev.gf2log.protocol.ParsedPacketTableParser

/** Extracts only the validated CSV body from a parsed-packet history envelope. */
object OriginalCsvPayload {
    fun extract(historyContent: String): String {
        require(historyContent.length <= ParsedPacketTableParser.MAX_CONTENT_CHARS)
        val body = requireNotNull(ParsedPacketTableParser.csvBody(historyContent)) {
            "Parsed packet does not contain an original CSV body"
        }
        requireNotNull(ParsedPacketTableParser.parse(historyContent)) {
            "Parsed packet CSV failed bounded validation"
        }
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_CSV_BYTES) {
            "Original CSV exceeds the Discord send limit"
        }
        return body
    }

    const val MAX_CSV_BYTES = 512 * 1024
}
