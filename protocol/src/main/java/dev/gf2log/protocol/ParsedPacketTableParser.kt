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

    /**
     * Returns the CSV after a history envelope's blank separator line. The body keeps its line
     * endings, so a quoted carriage return in a member name survives to the table and upload.
     */
    fun csvBody(content: String): String? {
        var index = 0
        var previousWasLineBreak = false
        while (index < content.length) {
            val character = content[index]
            if (character != '\r' && character != '\n') {
                previousWasLineBreak = false
                index += 1
                continue
            }
            index += if (character == '\r' && content.getOrNull(index + 1) == '\n') 2 else 1
            if (previousWasLineBreak) return content.substring(index).takeIf(String::isNotEmpty)
            previousWasLineBreak = true
        }
        return null
    }

    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
    )

    const val MAX_CONTENT_CHARS = 512 * 1024
    /** Every roster that [GuildMembersCsv.isValidRoster] accepts must render and upload. */
    const val MAX_ROWS = GuildMembersCsv.MAX_ROSTER_MEMBERS
    const val MAX_COLUMNS = 16
    const val MAX_CELLS = (MAX_ROWS + 1) * GuildMembersCsv.COLUMN_COUNT
    const val MAX_CELL_CHARS = 8 * 1024
    private const val MAX_RECORDS = MAX_ROWS + 1
}
