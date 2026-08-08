package dev.gf2log.protocol

import dev.gf2log.protocol.model.GuildMember

object GuildMembersCsv {
    const val HEADER = "uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime"

    fun row(member: GuildMember, logTime: String): String = listOf(
        member.uid.toString(),
        member.name,
        member.level.toString(),
        member.weeklyMerit.toString(),
        member.totalMerit.toString(),
        member.highScore.toString(),
        member.totalScore.toString(),
        member.lastLogin.toString(),
        logTime,
    ).joinToString(",") { CsvCell.escape(it, spreadsheetSafe = false) }

    internal fun rowForSpreadsheet(member: GuildMember, logTime: String): String = listOf(
        member.uid,
        member.name,
        member.level,
        member.weeklyMerit,
        member.totalMerit,
        member.highScore,
        member.totalScore,
        member.lastLogin,
        logTime,
    ).joinToString(",") { CsvCell.escape(it) }

    // Function Name: formatForSpreadsheet
    // Description:
    // - Formats a parsed roster for explicit export without allowing names to execute formulas.
    // - Leaves the retained/importable capture representation unchanged.
    // Parameters:
    // - snapshot: Validated roster snapshot selected for user export.
    // Returns:
    // - A complete spreadsheet-safe CSV document.
    fun formatForSpreadsheet(snapshot: Snapshot): String = buildString {
        appendLine(HEADER)
        snapshot.members.forEach { member ->
            appendLine(rowForSpreadsheet(member, snapshot.logTime))
        }
    }.trimEnd() + "\n"

    fun hasValidMemberBounds(members: List<GuildMember>): Boolean =
        members.size <= MAX_ROSTER_MEMBERS &&
            members.all { it.name.length <= MAX_MEMBER_NAME_CHARS }

    fun isValidRoster(members: List<GuildMember>): Boolean =
        members.isNotEmpty() &&
            hasValidMemberBounds(members) &&
            members.map(GuildMember::uid).distinct().size == members.size

    // Function Name: parse
    // Description:
    // - Parses a complete roster snapshot while preserving member names exactly.
    // - Tolerates surrounding whitespace in scalar fields introduced by spreadsheet copies.
    // Parameters:
    // - content: CSV text containing one roster snapshot.
    // Returns:
    // - Returns the parsed snapshot when the schema and rows are valid.
    // - Returns null when any required field is malformed or capture times disagree.
    fun parse(content: String): Snapshot? {
        if (content.length > MAX_CONTENT_CHARS) return null
        val records = parseRecords(content) ?: return null
        val header = records.firstOrNull() ?: return null
        if (header != HEADER.split(',')) return null

        var logTime: String? = null
        val seenUids = mutableSetOf<UInt>()
        val members = records.drop(1)
            .filterNot { row -> row.all(String::isBlank) }
            .map { row ->
                if (row.size != header.size) return null
                val rowLogTime = row[8].trim().takeIf(String::isNotBlank) ?: return null
                if (logTime == null) logTime = rowLogTime
                if (rowLogTime != logTime) return null
                val uid = row[0].trim().toUIntOrNull() ?: return null
                if (!seenUids.add(uid)) return null
                if (row[1].length > MAX_MEMBER_NAME_CHARS) return null
                GuildMember(
                    uid = uid,
                    name = row[1],
                    level = row[2].trim().toUIntOrNull() ?: return null,
                    weeklyMerit = parseOptionalCounter(row[3]) ?: return null,
                    totalMerit = parseOptionalCounter(row[4]) ?: return null,
                    highScore = parseOptionalCounter(row[5]) ?: return null,
                    totalScore = parseOptionalCounter(row[6]) ?: return null,
                    lastLogin = row[7].trim().toUIntOrNull() ?: return null,
                )
            }
        if (!isValidRoster(members) || logTime == null) return null
        return Snapshot(logTime = logTime!!, members = members)
    }

    private fun parseRecords(content: String): List<List<String>>? {
        val csv = content.replace("\r\n", "\n").replace('\r', '\n')
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
                ',' -> if (quoted) {
                    field.append(character)
                } else {
                    if (row.size >= MAX_COLUMNS || field.length > MAX_FIELD_CHARS) return null
                    row += field.toString()
                    field.clear()
                }
                '\n' -> if (quoted) {
                    field.append(character)
                } else {
                    if (row.size >= MAX_COLUMNS || field.length > MAX_FIELD_CHARS) return null
                    row += field.toString()
                    field.clear()
                    if (records.size >= MAX_RECORDS) return null
                    records += row.toList()
                    row.clear()
                }
                else -> {
                    if (field.length >= MAX_FIELD_CHARS) return null
                    field.append(character)
                }
            }
            index += 1
        }

        if (quoted) return null
        if (field.isNotEmpty() || row.isNotEmpty()) {
            if (row.size >= MAX_COLUMNS || field.length > MAX_FIELD_CHARS) return null
            row += field.toString()
            if (records.size >= MAX_RECORDS) return null
            records += row.toList()
        }
        return records
    }

    // Function Name: parseOptionalCounter
    // Description:
    // - Matches protobuf scalar semantics by treating an omitted counter as zero.
    // - Rejects non-empty malformed values instead of silently converting them.
    // Parameters:
    // - value: One unsigned counter field from a CSV row.
    // Returns:
    // - Returns zero for a blank field, the parsed value for valid digits, or null otherwise.
    private fun parseOptionalCounter(value: String): UInt? =
        value.trim().let { normalized ->
            if (normalized.isEmpty()) 0u else normalized.toUIntOrNull()
        }

    data class Snapshot(
        val logTime: String,
        val members: List<GuildMember>,
    )

    private const val MAX_CONTENT_CHARS = 2 * 1024 * 1024
    const val MAX_ROSTER_MEMBERS = 256
    const val MAX_MEMBER_NAME_CHARS = 256
    private const val MAX_COLUMNS = 9
    private const val MAX_RECORDS = MAX_ROSTER_MEMBERS + 2
    private const val MAX_FIELD_CHARS = 512
}
