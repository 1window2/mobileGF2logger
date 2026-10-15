package dev.gf2log.protocol

import dev.gf2log.protocol.internal.CsvRecords

object ParsedPacketTableParser {
    fun parse(content: String): Table? {
        if (content.length > MAX_CONTENT_CHARS) return null
        val body = csvBody(content) ?: return null
        val records = CsvRecords.parse(body, MAX_COLUMNS, MAX_RECORDS, MAX_CELL_CHARS) ?: return null
        val header = records.firstOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_COLUMNS }
            ?: return null
        val rows = records.drop(1)
        if (rows.size > MAX_ROWS || rows.any { it.size > header.size }) return null
        if ((rows.size + 1) * header.size > MAX_CELLS) return null
        return Table(
            header = header,
            rows = rows.map { row ->
                if (row.size >= header.size) row else row + List(header.size - row.size) { "" }
            },
        )
    }

    /** Returns the line-ending-normalized CSV after a history envelope's blank separator line. */
    fun csvBody(content: String): String? {
        val normalized = CsvRecords.normalizeLineEndings(content)
        val separator = normalized.indexOf("\n\n")
        if (separator < 0 || separator + 2 >= normalized.length) return null
        return normalized.substring(separator + 2)
    }

    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
    )

    const val MAX_CONTENT_CHARS = 512 * 1024
    const val MAX_ROWS = 250
    const val MAX_COLUMNS = 16
    const val MAX_CELLS = 2_048
    const val MAX_CELL_CHARS = 8 * 1024
    private const val MAX_RECORDS = MAX_ROWS + 1
}
