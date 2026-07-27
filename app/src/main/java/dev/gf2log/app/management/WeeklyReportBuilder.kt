package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object WeeklyReportBuilder {
    data class Report(
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val isGunsmokeWeek: Boolean,
        val days: List<LocalDate>,
        val members: List<MemberRow>,
    )

    data class MemberRow(
        val uid: Long,
        val name: String,
        val days: List<DayCell>,
        val totalMerit: Long,
        val totalScore: Long,
    ) {
        val observedDays: List<DayCell>
            get() = days.filter { it.meritDelta != null }

        val totalAttempts: Int?
            get() {
                val attempts = observedDays.map { it.inference?.selected?.attempts }
                return attempts.takeIf { values -> values.all { it != null } }
                    ?.filterNotNull()
                    ?.sum()
            }

        val loginDays: Int
            get() = observedDays.count { it.inference?.selected?.attended == true }

        val patrolDays: Int
            get() = observedDays.count { it.inference?.selected?.dailyPatrol == true }
    }

    data class DayCell(
        val gameDay: LocalDate,
        val meritDelta: Long?,
        val scoreDelta: Long?,
        val inference: ActivityInference.Result?,
    )

    fun build(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
    ): Report {
        val start = PlatoonPeriods.weekStart(referenceDay)
        val isGunsmoke = PlatoonPeriods.isGunsmokeWeek(start)
        val days = (0L..6L).map(start::plusDays)
        val sortedSnapshots = snapshots.sortedBy(PlatoonSnapshot::capturedAt)
        val uids = sortedSnapshots.flatMap { it.members }.associateBy(SnapshotMember::uid)
        val rosterAtPeriodEnd = sortedSnapshots.lastBefore(
            PlatoonPeriods.periodStartInstant(start.plusDays(7), zoneId),
        )?.members?.map(SnapshotMember::uid).orEmpty().toSet()

        val rows = uids.values.map { latestKnown ->
            val cells = days.map { day ->
                buildCell(
                    uid = latestKnown.uid,
                    day = day,
                    zoneId = zoneId,
                    snapshots = sortedSnapshots,
                    isGunsmoke = isGunsmoke,
                )
            }
            MemberRow(
                uid = latestKnown.uid,
                name = latestKnown.name,
                days = cells,
                totalMerit = cells.sumOf { it.meritDelta ?: 0 },
                totalScore = cells.sumOf { it.scoreDelta ?: 0 },
            )
        }.filter { row ->
            row.uid in rosterAtPeriodEnd || row.days.any { it.meritDelta != null }
        }
            .sortedWith(
                if (isGunsmoke) {
                    compareByDescending<MemberRow> { it.totalScore }
                        .thenByDescending { it.totalMerit }
                        .thenBy { it.name }
                } else {
                    compareByDescending<MemberRow> { it.totalMerit }.thenBy { it.name }
                },
            )

        return Report(start, start.plusDays(6), isGunsmoke, days, rows)
    }

    private fun buildCell(
        uid: Long,
        day: LocalDate,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        isGunsmoke: Boolean,
    ): DayCell {
        val start = PlatoonPeriods.periodStartInstant(day, zoneId)
        val end = PlatoonPeriods.periodStartInstant(day.plusDays(1), zoneId)
        val before = snapshots.lastAtOrBefore(start)?.member(uid)
        val after = snapshots.lastBefore(end)?.member(uid)
        if (after == null || after === before) return DayCell(day, null, null, null)
        if (before == null) return DayCell(day, null, null, null)

        val meritDelta = counterDelta(after.weeklyMerit, before.weeklyMerit)
        val scoreDelta = if (isGunsmoke) {
            counterDelta(after.totalScore, before.totalScore)
        } else {
            0L
        }
        return DayCell(
            gameDay = day,
            meritDelta = meritDelta,
            scoreDelta = scoreDelta,
            inference = ActivityInference.infer(meritDelta, scoreDelta, isGunsmoke),
        )
    }

    private fun List<PlatoonSnapshot>.lastBefore(instant: Instant): PlatoonSnapshot? =
        lastOrNull { it.capturedAt.isBefore(instant) }

    private fun List<PlatoonSnapshot>.lastAtOrBefore(instant: Instant): PlatoonSnapshot? =
        lastOrNull { !it.capturedAt.isAfter(instant) }

    private fun PlatoonSnapshot.member(uid: Long): SnapshotMember? =
        members.firstOrNull { it.uid == uid }

    private fun counterDelta(current: Long, previous: Long): Long =
        if (current >= previous) current - previous else current
}
