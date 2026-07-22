package dev.gf2log.protocol

object ParsedPacketTableParser {
    fun parse(content: String): Table? {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val separator = normalized.indexOf("\n\n")
        if (separator < 0 || separator + 2 >= normalized.length) return null

        val records = parseCsv(normalized.substring(separator + 2)) ?: return null
        val header = records.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Table(
            header = header,
            rows = records.drop(1).map { row ->
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
                    row += field.toString()
                    field.clear()
                }
                '\n' -> if (quoted) field.append(character) else {
                    row += field.toString()
                    field.clear()
                    records += row.toList()
                    row.clear()
                }
                else -> field.append(character)
            }
            index += 1
        }

        if (quoted) return null
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            records += row.toList()
        }
        return records
    }

    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
    )
}
