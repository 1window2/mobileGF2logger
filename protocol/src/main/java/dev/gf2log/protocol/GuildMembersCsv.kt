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
}
