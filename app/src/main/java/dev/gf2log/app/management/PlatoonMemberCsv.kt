package dev.gf2log.app.management

import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PlatoonMemberCsv {
    const val HEADER =
        "uid,name,status,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin," +
            "joinedAt,leftAt,note"

    fun format(
        statuses: List<MemberStatus>,
        latestMembers: Map<Long, SnapshotMember>,
        zoneId: ZoneId,
    ): String = buildString {
        appendLine(HEADER)
        statuses.forEach { status ->
            val latest = latestMembers[status.uid]
            val tenure = status.tenures.firstOrNull()
            appendLine(
                listOf(
                    status.uid,
                    status.name,
                    if (status.isActive) "ACTIVE" else "DEPARTED",
                    latest?.level ?: status.level,
                    latest?.weeklyMerit,
                    latest?.totalMerit,
                    latest?.highScore,
                    latest?.totalScore,
                    latest?.lastLogin,
                    tenure?.joinedAt?.atZone(zoneId)?.format(TIME),
                    tenure?.leftAt?.atZone(zoneId)?.format(TIME),
                    status.note,
                ).joinToString(",", transform = ::escape),
            )
        }
    }.trimEnd() + "\n"

    private fun escape(value: Any?): String {
        val text = value?.toString().orEmpty()
        if (text.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return text
        return "\"${text.replace("\"", "\"\"")}\""
    }

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
