package dev.gf2log.app.management

import dev.gf2log.protocol.CsvCell

object SnapshotComparisonCsv {
    const val HEADER =
        "olderCapturedAt,newerCapturedAt,changeType,uid,name,weeklyMeritDelta," +
            "totalMeritDelta,totalScoreDelta,lastLoginChanged"

    fun format(result: SnapshotComparison.Result): String = buildString {
        appendLine(HEADER)
        result.joined.forEach { member ->
            appendRow(result, "JOINED", member.uid, member.name, null, null, null, null)
        }
        result.left.forEach { member ->
            appendRow(result, "WITHDREW", member.uid, member.name, null, null, null, null)
        }
        result.changes.forEach { change ->
            appendRow(
                result,
                "CHANGED",
                change.uid,
                change.name,
                change.weeklyMeritDelta,
                change.totalMeritDelta,
                change.totalScoreDelta,
                change.lastLoginChanged,
            )
        }
    }.trimEnd() + "\n"

    private fun StringBuilder.appendRow(
        result: SnapshotComparison.Result,
        type: String,
        uid: Long,
        name: String,
        weeklyMerit: Long?,
        totalMerit: Long?,
        totalScore: Long?,
        lastLoginChanged: Boolean?,
    ) {
        appendLine(
            listOf(
                result.older.capturedAt,
                result.newer.capturedAt,
                type,
                uid,
                name,
                weeklyMerit,
                totalMerit,
                totalScore,
                lastLoginChanged,
            ).joinToString(",") { CsvCell.escape(it) },
        )
    }
}
