package dev.gf2log.app.management

import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PlatoonMemberCsv {
    const val HEADER =
        "uid,name,status,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin," +
            "joinedAt,withdrawalAt,note"

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
                    if (status.isActive) "ACTIVE" else "WITHDRAWN",
                    latest?.level ?: status.level,
                    latest?.weeklyMerit,
                    latest?.totalMerit,
                    latest?.highScore,
                    latest?.totalScore,
                    latest?.lastLogin,
                    tenure?.let {
                        formatBoundary(
                            date = it.joinedDate,
                            instant = it.joinedAt,
                            timeKnown = it.joinedTimeKnown,
                            zoneId = zoneId,
                        )
                    },
                    tenure?.let {
                        formatBoundary(
                            date = it.leftDate,
                            instant = it.leftAt,
                            timeKnown = it.leftTimeKnown ?: (it.leftAt != null),
                            zoneId = zoneId,
                        )
                    },
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

    private fun formatBoundary(
        date: java.time.LocalDate?,
        instant: java.time.Instant?,
        timeKnown: Boolean,
        zoneId: ZoneId,
    ): String {
        val displayDate = date ?: instant?.atZone(zoneId)?.toLocalDate() ?: return ""
        return if (timeKnown && instant != null) {
            instant.atZone(zoneId).format(TIME)
        } else {
            displayDate.format(DATE)
        }
    }

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
}
