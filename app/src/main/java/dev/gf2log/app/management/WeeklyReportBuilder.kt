package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object WeeklyReportBuilder {
    data class Report(
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val isGunsmokeWeek: Boolean,
        val days: List<LocalDate>,
        val members: List<MemberRow>,
    ) {
        val hasIncompleteDailyEvidence: Boolean
            get() = members.any { row ->
                row.days.any {
                    it.evidence == DailyEvidence.INCOMPLETE_BOUNDARY ||
                        it.evidence == DailyEvidence.PARTIAL_DAY ||
                        it.evidence == DailyEvidence.SPARSE_INFERRED
                }
            }
    }

    data class MemberRow(
        val uid: Long,
        val name: String,
        val days: List<DayCell>,
        val totalMerit: Long,
        val totalScore: Long,
    ) {
        val observedDays: List<DayCell>
            get() = days.filter { it.observed }

        val totalAttempts: Int?
            get() {
                val attempts = observedDays.map(DayCell::attempts)
                return attempts.takeIf { values -> values.all { it != null } }
                    ?.filterNotNull()
                    ?.sum()
            }

        val loginDays: Int
            get() = observedDays.count { it.attended == true }

        val patrolDays: Int
            get() = observedDays.count { it.dailyPatrol == true }

        val hasIncompleteEvidence: Boolean
            get() = days.any {
                it.evidence == DailyEvidence.INCOMPLETE_BOUNDARY ||
                    it.evidence == DailyEvidence.PARTIAL_DAY
            }
    }

    data class DayCell(
        val gameDay: LocalDate,
        val meritDelta: Long?,
        val scoreDelta: Long?,
        val inference: ActivityInference.Result?,
        val evidence: DailyEvidence,
        val manualOverride: WeeklyCellOverride? = null,
    ) {
        val attempts: Int?
            get() = if (manualOverride != null) manualOverride.attempts else inference?.selected?.attempts

        val attended: Boolean?
            get() = if (manualOverride != null) manualOverride.attended else inference?.selected?.attended

        val dailyPatrol: Boolean?
            get() = if (manualOverride != null) {
                manualOverride.dailyPatrol
            } else {
                inference?.selected?.dailyPatrol
            }

        val precision: EvidencePrecision?
            get() = if (manualOverride != null) EvidencePrecision.MANUAL else inference?.precision

        val observed: Boolean
            get() = evidence == DailyEvidence.MANUAL ||
                evidence == DailyEvidence.ATTRIBUTED ||
                evidence == DailyEvidence.PARTIAL_DAY ||
                evidence == DailyEvidence.SPARSE_INFERRED
    }

    fun build(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        overrides: List<WeeklyCellOverride> = emptyList(),
    ): Report {
        val start = PlatoonPeriods.weekStart(referenceDay)
        val isGunsmoke = PlatoonPeriods.isGunsmokeWeek(start)
        val days = (0L..6L).map(start::plusDays)
        val overridesByCell = overrides
            .filter { it.periodStart == start && it.gameDay in days }
            .associateBy { it.uid to it.gameDay }
        val sortedSnapshots = snapshots.sortedBy(PlatoonSnapshot::capturedAt)
        val uids = sortedSnapshots.flatMap { it.members }.associateBy(SnapshotMember::uid).toMutableMap()
        overridesByCell.keys.map { it.first }.forEach { uid ->
            if (uid !in uids) {
                uids[uid] = SnapshotMember(uid, "#$uid", 0, 0, 0, 0, 0, 0)
            }
        }
        val rosterAtPeriodEnd = sortedSnapshots.lastBefore(
            PlatoonPeriods.periodStartInstant(start.plusDays(7), zoneId),
        )?.members?.map(SnapshotMember::uid).orEmpty().toSet()

        val rows = uids.values.map { latestKnown ->
            val derivedCells = days.map { day ->
                buildCell(
                    uid = latestKnown.uid,
                    day = day,
                    zoneId = zoneId,
                    snapshots = sortedSnapshots,
                    isGunsmoke = isGunsmoke,
                    manualOverride = overridesByCell[latestKnown.uid to day],
                )
            }
            val cells = if (isGunsmoke) {
                derivedCells
            } else {
                inferSparseStandardCells(
                    uid = latestKnown.uid,
                    days = days,
                    zoneId = zoneId,
                    snapshots = sortedSnapshots,
                    cells = derivedCells,
                )
            }
            val snapshotsInPeriod = sortedSnapshots.mapNotNull { snapshot ->
                val gameDay = PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId)
                snapshot.member(latestKnown.uid)
                    ?.takeIf { gameDay in days }
                    ?.let { gameDay to it }
            }
            val derivedMeritTotal = cells.sumOf { it.meritDelta ?: 0L }
            val sundayMerit = cells.firstOrNull()?.meritDelta ?: 0L
            val mondayThroughSaturday = snapshotsInPeriod
                .lastOrNull { (gameDay, _) -> gameDay.dayOfWeek.value in 1..6 }
                ?.second
                ?.weeklyMerit
                ?: 0L
            val derivedScoreTotal = cells.sumOf { it.scoreDelta ?: 0L }
            val latestCapturedScore = snapshotsInPeriod.lastOrNull()?.second?.totalScore ?: 0L
            MemberRow(
                uid = latestKnown.uid,
                name = latestKnown.name,
                days = cells,
                totalMerit = maxOf(derivedMeritTotal, sundayMerit + mondayThroughSaturday),
                totalScore = if (isGunsmoke) {
                    maxOf(derivedScoreTotal, latestCapturedScore)
                } else {
                    derivedScoreTotal
                },
            )
        }.filter { row ->
            row.uid in rosterAtPeriodEnd || row.days.any(DayCell::observed)
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
        manualOverride: WeeklyCellOverride?,
    ): DayCell {
        val start = PlatoonPeriods.periodStartInstant(day, zoneId)
        val end = PlatoonPeriods.periodStartInstant(day.plusDays(1), zoneId)
        if (manualOverride != null) {
            return DayCell(
                gameDay = day,
                meritDelta = manualOverride.meritDelta,
                scoreDelta = manualOverride.scoreDelta,
                inference = null,
                evidence = DailyEvidence.MANUAL,
                manualOverride = manualOverride,
            )
        }
        val observations = snapshots.filter { it.capturedAt.isAfter(start) && it.capturedAt.isBefore(end) }
        val closingSnapshot = snapshots.lastOrNull { snapshot ->
            snapshot.capturedAt.isBefore(end) &&
                !snapshot.capturedAt.isBefore(
                    end.minus(BOUNDARY_WINDOW_MINUTES, ChronoUnit.MINUTES),
                )
        }
        val baselineSnapshot = snapshots.lastOrNull { snapshot ->
            !snapshot.capturedAt.isAfter(start) &&
                !snapshot.capturedAt.isBefore(start.minus(BOUNDARY_WINDOW_MINUTES, ChronoUnit.MINUTES))
        }
        val before = baselineSnapshot?.member(uid)
        val after = observations.asReversed().firstNotNullOfOrNull { it.member(uid) }
        val closingMember = closingSnapshot?.member(uid)
        if (baselineSnapshot == null) {
            return DayCell(
                gameDay = day,
                meritDelta = null,
                scoreDelta = null,
                inference = null,
                evidence = if (after == null) {
                    DailyEvidence.NO_OBSERVATION
                } else {
                    DailyEvidence.INCOMPLETE_BOUNDARY
                },
            )
        }
        if (observations.isEmpty()) {
            return DayCell(day, null, null, null, DailyEvidence.NO_OBSERVATION)
        }
        if (after == null && before == null) {
            return DayCell(day, null, null, null, DailyEvidence.NO_OBSERVATION)
        }

        val meritDelta: Long
        val scoreDelta: Long?
        if (before == null) {
            val joined = requireNotNull(after)
            // A newcomer has no prior total-merit counter in the platoon dataset.
            // The current weekly value is the only bounded merit evidence for the
            // join day; established members always use the monotonic total counter.
            meritDelta = joined.weeklyMerit
            scoreDelta = if (isGunsmoke) null else 0L
        } else {
            val lastKnown = after ?: before
            meritDelta = counterDelta(lastKnown.totalMerit, before.totalMerit)
            scoreDelta = if (isGunsmoke) {
                counterDelta(lastKnown.totalScore, before.totalScore)
            } else {
                0L
            }
        }
        return DayCell(
            gameDay = day,
            meritDelta = meritDelta,
            scoreDelta = scoreDelta,
            inference = scoreDelta?.let { ActivityInference.infer(meritDelta, it, isGunsmoke) },
            evidence = if (closingSnapshot != null && closingMember != null) {
                DailyEvidence.ATTRIBUTED
            } else {
                DailyEvidence.PARTIAL_DAY
            },
        )
    }

    private fun List<PlatoonSnapshot>.lastBefore(instant: Instant): PlatoonSnapshot? =
        lastOrNull { it.capturedAt.isBefore(instant) }

    /**
     * Allocates a standard-week aggregate across otherwise unknown days.
     *
     * Total Merit is monotonic, so two sparse captures still constrain the
     * aggregate earned between them. The exact daily placement does not. The
     * deterministic 90/50/0 allocation favors complete login-and-patrol
     * days, follows Monday-through-Sunday order, and is always marked as
     * SPARSE_INFERRED for transparent UI/CSV handling.
     */
    private fun inferSparseStandardCells(
        uid: Long,
        days: List<LocalDate>,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        cells: List<DayCell>,
    ): List<DayCell> {
        val mutable = cells.toMutableList()
        val observations = snapshots.mapNotNull { snapshot ->
            snapshot.member(uid)?.let { snapshot to it }
        }
        observations.zipWithNext().forEach { (earlier, later) ->
            val totalDelta = later.second.totalMerit - earlier.second.totalMerit
            if (totalDelta < 0L) return@forEach

            val earlierDay = PlatoonPeriods.gameDay(earlier.first.capturedAt, zoneId)
            val earlierBoundary = PlatoonPeriods.periodStartInstant(earlierDay.plusDays(1), zoneId)
            val intervalStart = if (
                earlier.first.capturedAt.isBefore(earlierBoundary) &&
                !earlier.first.capturedAt.isBefore(
                    earlierBoundary.minus(BOUNDARY_WINDOW_MINUTES, ChronoUnit.MINUTES),
                )
            ) {
                earlierDay.plusDays(1)
            } else {
                earlierDay
            }
            val laterDay = PlatoonPeriods.gameDay(later.first.capturedAt, zoneId)
            val laterBoundary = PlatoonPeriods.periodStartInstant(laterDay.plusDays(1), zoneId)
            val intervalEnd = if (
                later.first.capturedAt.isBefore(laterBoundary) &&
                !later.first.capturedAt.isBefore(
                    laterBoundary.minus(BOUNDARY_WINDOW_MINUTES, ChronoUnit.MINUTES),
                )
            ) {
                laterDay
            } else {
                laterDay.minusDays(1)
            }
            if (intervalEnd.isBefore(intervalStart)) return@forEach
            if (intervalStart.isBefore(days.first()) || intervalEnd.isAfter(days.last())) {
                return@forEach
            }

            val intervalIndexes = days.indices.filter { index ->
                !days[index].isBefore(intervalStart) && !days[index].isAfter(intervalEnd)
            }
            if (intervalIndexes.isEmpty()) return@forEach
            if (intervalIndexes.any { mutable[it].evidence == DailyEvidence.PARTIAL_DAY }) {
                return@forEach
            }

            val knownMerit = intervalIndexes.sumOf { index ->
                mutable[index].meritDelta ?: 0L
            }
            val unknownIndexes = intervalIndexes.filter { index ->
                mutable[index].evidence == DailyEvidence.NO_OBSERVATION ||
                    mutable[index].evidence == DailyEvidence.INCOMPLETE_BOUNDARY
            }.sortedBy { index -> days[index].dayOfWeek.value }
            if (unknownIndexes.isEmpty()) return@forEach

            val mondayReset = days.first().plusDays(1)
            val resetAdjustedDelta = if (
                !intervalStart.isAfter(days.first()) &&
                intervalEnd.isBefore(mondayReset) &&
                !laterDay.isBefore(mondayReset)
            ) {
                // A Monday packet's weekly counter contains Monday only. Remove
                // it from the monotonic Total Merit interval before assigning
                // the preceding Sunday.
                totalDelta - later.second.weeklyMerit
            } else {
                totalDelta
            }
            val remaining = resetAdjustedDelta - knownMerit
            val allocation = allocateSparseMerit(remaining, unknownIndexes.size)
                ?: return@forEach
            unknownIndexes.zip(allocation).forEach { (index, merit) ->
                mutable[index] = mutable[index].copy(
                    meritDelta = merit,
                    scoreDelta = 0L,
                    inference = ActivityInference.infer(
                        meritDelta = merit,
                        scoreDelta = 0L,
                        gunsmokeActive = false,
                    ),
                    evidence = DailyEvidence.SPARSE_INFERRED,
                )
            }
        }
        return mutable
    }

    private fun allocateSparseMerit(total: Long, slots: Int): List<Long>? {
        if (total < 0L || slots <= 0) return null
        val result = mutableListOf<Long>()
        fun allocate(remaining: Long, openSlots: Int): Boolean {
            if (openSlots == 0) return remaining == 0L
            for (candidate in SPARSE_DAILY_MERIT) {
                if (candidate > remaining) continue
                result += candidate
                if (allocate(remaining - candidate, openSlots - 1)) return true
                result.removeAt(result.lastIndex)
            }
            return false
        }
        return result.takeIf { allocate(total, slots) }?.toList()
    }

    private fun PlatoonSnapshot.member(uid: Long): SnapshotMember? =
        members.firstOrNull { it.uid == uid }

    private fun counterDelta(current: Long, previous: Long): Long =
        if (current >= previous) current - previous else current

    private const val BOUNDARY_WINDOW_MINUTES = 15L
    private val SPARSE_DAILY_MERIT = longArrayOf(90L, 50L, 0L)
}
