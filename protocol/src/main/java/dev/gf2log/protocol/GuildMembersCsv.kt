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
    ).joinToString(",", transform = ::escape)

    private fun escape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    fun parse(content: String): Snapshot? {
        val records = parseRecords(content) ?: return null
        val header = records.firstOrNull() ?: return null
        if (header != HEADER.split(',')) return null

        var logTime: String? = null
        val members = records.drop(1)
            .filterNot { row -> row.all(String::isBlank) }
            .map { row ->
                if (row.size != header.size) return null
                val rowLogTime = row[8].takeIf(String::isNotBlank) ?: return null
                if (logTime == null) logTime = rowLogTime
                if (rowLogTime != logTime) return null
                GuildMember(
                    uid = row[0].toUIntOrNull() ?: return null,
                    name = row[1],
                    level = row[2].toUIntOrNull() ?: return null,
                    weeklyMerit = row[3].toUIntOrNull() ?: return null,
                    totalMerit = row[4].toUIntOrNull() ?: return null,
                    highScore = row[5].toUIntOrNull() ?: 0u,
                    totalScore = row[6].toUIntOrNull() ?: 0u,
                    lastLogin = row[7].toUIntOrNull() ?: return null,
                )
            }
        if (members.isEmpty() || logTime == null) return null
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
                    row += field.toString()
                    field.clear()
                }
                '\n' -> if (quoted) {
                    field.append(character)
                } else {
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

    data class Snapshot(
        val logTime: String,
        val members: List<GuildMember>,
    )
}
