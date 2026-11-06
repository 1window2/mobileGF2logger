package dev.gf2log.app.management

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object SnapshotComparison {
    data class MeritChange(
        val uid: Long,
        val name: String,
        val weeklyMeritDelta: Long,
        val totalMeritDelta: Long,
        val totalScoreDelta: Long,
        val lastLoginChanged: Boolean,
        val weeklyMeritDeltaCertainty: MetricCertainty = MetricCertainty.EXACT,
        val totalScoreDeltaCertainty: MetricCertainty = MetricCertainty.EXACT,
        val totalMeritDeltaCertainty: MetricCertainty = MetricCertainty.EXACT,
    ) {
        /** Whether a counter changed; a member who only logged in has none to show. */
        val hasCounterChange: Boolean
            get() = weeklyMeritDelta != 0L || totalMeritDelta != 0L || totalScoreDelta != 0L
    }

    data class Result(
        val older: PlatoonSnapshot,
        val newer: PlatoonSnapshot,
        val joined: List<SnapshotMember>,
        val left: List<SnapshotMember>,
        val changes: List<MeritChange>,
    ) {
        /** The changes the comparison screen lists; the CSV export keeps login-only rows too. */
        val counterChanges: List<MeritChange>
            get() = changes.filter(MeritChange::hasCounterChange)
    }

    /**
     * Compares two rosters captured in the Platoon's [zoneId]. Merit This Week restarts at every
     * Monday 05:00 and the Gunsmoke score at every event start, so a delta across either reset is
     * only the newer counter's lower bound: the older counter's gain before the reset is unknown.
     * Total Merit never resets, so a drop in it is reported the same way rather than as a gain.
     */
    fun compare(older: PlatoonSnapshot, newer: PlatoonSnapshot, zoneId: ZoneId): Result {
        require(!newer.capturedAt.isBefore(older.capturedAt))
        val olderByUid = older.members.associateBy(SnapshotMember::uid)
        val newerByUid = newer.members.associateBy(SnapshotMember::uid)
        val weeklyReset = resetBetween(older.capturedAt, newer.capturedAt, zoneId) { day ->
            day.dayOfWeek == DayOfWeek.MONDAY
        }
        val eventReset = resetBetween(older.capturedAt, newer.capturedAt, zoneId) { day ->
            day.dayOfWeek == DayOfWeek.SUNDAY && PlatoonPeriods.isGunsmokeWeek(day)
        }
        return Result(
            older = older,
            newer = newer,
            joined = newerByUid.filterKeys { it !in olderByUid }.values.sortedBy { it.name },
            left = olderByUid.filterKeys { it !in newerByUid }.values.sortedBy { it.name },
            changes = newerByUid.values.mapNotNull { current ->
                val previous = olderByUid[current.uid] ?: return@mapNotNull null
                val (weeklyDelta, weeklyCertainty) =
                    resettingDelta(previous.weeklyMerit, current.weeklyMerit, weeklyReset)
                val (scoreDelta, scoreCertainty) =
                    resettingDelta(previous.totalScore, current.totalScore, eventReset)
                val (totalMeritDelta, totalMeritCertainty) =
                    resettingDelta(previous.totalMerit, current.totalMerit, resetBetween = false)
                MeritChange(
                    uid = current.uid,
                    name = current.name,
                    weeklyMeritDelta = weeklyDelta,
                    totalMeritDelta = totalMeritDelta,
                    totalScoreDelta = scoreDelta,
                    lastLoginChanged = current.lastLogin != previous.lastLogin,
                    weeklyMeritDeltaCertainty = weeklyCertainty,
                    totalScoreDeltaCertainty = scoreCertainty,
                    totalMeritDeltaCertainty = totalMeritCertainty,
                )
            }.filter { it.hasCounterChange || it.lastLoginChanged }
                .sortedByDescending(MeritChange::weeklyMeritDelta),
        )
    }

    /** A reset between the captures, or an unexplained drop, leaves only the newer counter. */
    private fun resettingDelta(
        previous: Long,
        current: Long,
        resetBetween: Boolean,
    ): Pair<Long, MetricCertainty> = if (resetBetween || current < previous) {
        current to MetricCertainty.LOWER_BOUND
    } else {
        current - previous to MetricCertainty.EXACT
    }

    /**
     * Whether a 05:00 reset of a game day matching [isReset] falls within [from]..[to] and can
     * separate the two counters. A reset at the older capture counts, since that capture may
     * precede the restart. One at the newer capture does not: that capture is the closing value of
     * the day before, and a restart it did see shows as a drop.
     */
    private fun resetBetween(
        from: Instant,
        to: Instant,
        zoneId: ZoneId,
        isReset: (LocalDate) -> Boolean,
    ): Boolean {
        val last = PlatoonPeriods.gameDay(to, zoneId)
        var day = PlatoonPeriods.gameDay(from, zoneId)
        while (!day.isAfter(last)) {
            if (isReset(day)) {
                val resetAt = PlatoonPeriods.periodStartInstant(day, zoneId)
                if (!resetAt.isBefore(from) && resetAt.isBefore(to)) return true
            }
            day = day.plusDays(1)
        }
        return false
    }
}
