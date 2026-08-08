package dev.gf2log.protocol

object ParsedPacketTableParser {
    fun parse(content: String): Table? {
        if (content.length > MAX_CONTENT_CHARS) return null
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val separator = normalized.indexOf("\n\n")
        if (separator < 0 || separator + 2 >= normalized.length) return null

        val records = parseCsv(normalized.substring(separator + 2)) ?: return null
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

    private fun parseCsv(csv: String): List<List<String>>? {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        while (index < csv.length) {
            when (val character = csv[index]) {
                '"' -> {
                    if (quoted && index + 1 < csv.length && csv[index + 1] == '"') {
                        field.append('"')
                        index += 1
                    } else {
                        quoted = !quoted
                    }
                }
                ',' -> if (quoted) field.append(character) else {
                    if (row.size >= MAX_COLUMNS || field.length > MAX_CELL_CHARS) return null
                    row += field.toString()
                    field.clear()
                }
                '\n' -> if (quoted) field.append(character) else {
                    if (row.size >= MAX_COLUMNS || field.length > MAX_CELL_CHARS) return null
                    row += field.toString()
                    field.clear()
                    if (records.size >= MAX_RECORDS) return null
                    records += row.toList()
                    row.clear()
                }
                else -> {
                    if (field.length >= MAX_CELL_CHARS) return null
                    field.append(character)
                }
            }
            index += 1
        }

        if (quoted) return null
        if (field.isNotEmpty() || row.isNotEmpty()) {
            if (row.size >= MAX_COLUMNS || field.length > MAX_CELL_CHARS) return null
            row += field.toString()
            if (records.size >= MAX_RECORDS) return null
            records += row.toList()
        }
        return records
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
