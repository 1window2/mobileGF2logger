package dev.gf2log.protocol.internal

/** Bounded RFC-4180-style tokenizer shared by every CSV reader in this module. */
internal object CsvRecords {
    /**
     * Returns null for unbalanced quotes or when any bound would be exceeded. Quoted fields keep
     * their content exactly, line breaks included; only an unquoted CRLF, CR, or LF ends a record.
     */
    fun parse(
        content: String,
        maxColumns: Int,
        maxRecords: Int,
        maxFieldChars: Int,
    ): List<List<String>>? {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun endField(): Boolean {
            if (row.size >= maxColumns || field.length > maxFieldChars) return false
            row += field.toString()
            field.clear()
            return true
        }

        fun endRecord(): Boolean {
            if (records.size >= maxRecords) return false
            records += row.toList()
            row.clear()
            return true
        }

        while (index < content.length) {
            when (val character = content[index]) {
                '"' -> {
                    if (quoted && index + 1 < content.length && content[index + 1] == '"') {
                        field.append('"')
                        index += 1
                    } else {
                        quoted = !quoted
                    }
                }
                ',' -> if (quoted) field.append(character) else if (!endField()) return null
                '\r', '\n' -> if (quoted) {
                    field.append(character)
                } else {
                    if (!endField() || !endRecord()) return null
                    if (character == '\r' && content.getOrNull(index + 1) == '\n') index += 1
                }
                else -> {
                    if (field.length >= maxFieldChars) return null
                    field.append(character)
                }
            }
            index += 1
        }

        if (quoted) return null
        if (field.isNotEmpty() || row.isNotEmpty()) {
            if (!endField() || !endRecord()) return null
        }
        return records
    }
}
