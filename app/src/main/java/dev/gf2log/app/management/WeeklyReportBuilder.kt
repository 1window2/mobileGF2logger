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
        val isGunsmokeWeek: Boolean = false,
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

        val loginDays: Int?
            get() = if (hasUnknownGunsmokeActivityTotals) {
                null
            } else {
                observedDays.count { it.attended == true }
            }

        val patrolDays: Int?
            get() = if (hasUnknownGunsmokeActivityTotals) {
                null
            } else {
                observedDays.count { it.dailyPatrol == true }
            }

        val hasUnknownGunsmokeActivityTotals: Boolean
            get() = isGunsmokeWeek && days.any { cell ->
                cell.isGunsmokeActivityUnknown
            }

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
        val hasDailyPatrolFact: Boolean = false,
    ) {
        val attempts: Int?
            get() = if (manualOverride != null) manualOverride.attempts else inference?.selected?.attempts

        val attended: Boolean?
            get() = if (manualOverride != null) {
                manualOverride.attended
            } else {
                true.takeIf { hasDailyPatrolFact } ?: inference?.selected?.attended
            }

        val dailyPatrol: Boolean?
            get() = if (manualOverride != null) {
                manualOverride.dailyPatrol
            } else {
                true.takeIf { hasDailyPatrolFact } ?: inference?.selected?.dailyPatrol
            }

        val precision: EvidencePrecision?
            get() = if (manualOverride != null) EvidencePrecision.MANUAL else inference?.precision

        val observed: Boolean
            get() = evidence == DailyEvidence.MANUAL ||
                evidence == DailyEvidence.ATTRIBUTED ||
                evidence == DailyEvidence.PARTIAL_DAY ||
                evidence == DailyEvidence.SPARSE_INFERRED

        val isGunsmokeActivityUnknown: Boolean
            get() = manualOverride == null &&
                !hasDailyPatrolFact &&
                evidence in setOf(
                    DailyEvidence.NO_OBSERVATION,
                    DailyEvidence.INCOMPLETE_BOUNDARY,
                    DailyEvidence.SPARSE_INFERRED,
                )
    }

    fun build(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        overrides: List<WeeklyCellOverride> = emptyList(),
        dailyPatrolFacts: List<DailyPatrolFact> = emptyList(),
    ): Report {
        val start = PlatoonPeriods.weekStart(referenceDay)
        val isGunsmoke = PlatoonPeriods.isGunsmokeWeek(start)
        val days = (0L..6L).map(start::plusDays)
        val overridesByCell = overrides
            .filter { it.periodStart == start && it.gameDay in days }
            .associateBy { it.uid to it.gameDay }
        val sortedSnapshots = snapshots.sortedBy(PlatoonSnapshot::capturedAt)
        val patrolFactsByMemberDay = dailyPatrolFacts
            .map { it.uid to PlatoonPeriods.gameDay(it.occurredAt, zoneId) }
            .filter { it.second in days }
            .toSet()
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
                    hasDailyPatrolFact = latestKnown.uid to day in patrolFactsByMemberDay,
                )
            }
            val cells = if (isGunsmoke) {
                derivedCells
            } else {
                inferStandardWeekCells(
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
                isGunsmokeWeek = isGunsmoke,
            )
        }.filter { row ->
            // Weekly rows represent the roster that is active at the end of the
            // selected reporting period (or the latest roster for an in-progress
            // week). A member observed earlier in the week must disappear as soon
            // as a later complete roster records their withdrawal; the immutable
            // membership event remains available in the Join / Withdraw section.
            row.uid in rosterAtPeriodEnd
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
        hasDailyPatrolFact: Boolean,
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
                hasDailyPatrolFact = hasDailyPatrolFact,
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
            return applyDailyPatrolFact(
                DayCell(
                gameDay = day,
                meritDelta = null,
                scoreDelta = null,
                inference = null,
                evidence = if (after == null) {
                    DailyEvidence.NO_OBSERVATION
                } else {
                    DailyEvidence.INCOMPLETE_BOUNDARY
                },
                ),
                isGunsmoke,
                hasDailyPatrolFact,
            )
        }
        if (observations.isEmpty()) {
            return applyDailyPatrolFact(
                DayCell(day, null, null, null, DailyEvidence.NO_OBSERVATION),
                isGunsmoke,
                hasDailyPatrolFact,
            )
        }
        if (after == null && before == null) {
            return applyDailyPatrolFact(
                DayCell(day, null, null, null, DailyEvidence.NO_OBSERVATION),
                isGunsmoke,
                hasDailyPatrolFact,
            )
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
        return applyDailyPatrolFact(
            DayCell(
                gameDay = day,
                meritDelta = meritDelta,
                scoreDelta = scoreDelta,
                inference = scoreDelta?.let { ActivityInference.infer(meritDelta, it, isGunsmoke) },
                evidence = if (closingSnapshot != null && closingMember != null) {
                    DailyEvidence.ATTRIBUTED
                } else {
                    DailyEvidence.PARTIAL_DAY
                },
            ),
            isGunsmoke,
            hasDailyPatrolFact,
        )
    }

    private fun applyDailyPatrolFact(
        cell: DayCell,
        isGunsmoke: Boolean,
        hasDailyPatrolFact: Boolean,
    ): DayCell {
        if (!hasDailyPatrolFact || cell.manualOverride != null) return cell
        val merit = maxOf(cell.meritDelta ?: 0L, STANDARD_PATROL_MERIT)
        val score = cell.scoreDelta ?: if (isGunsmoke) null else 0L
        return cell.copy(
            meritDelta = merit,
            scoreDelta = score,
            inference = score?.let { ActivityInference.infer(merit, it, isGunsmoke) },
            evidence = if (isGunsmoke) {
                DailyEvidence.PARTIAL_DAY
            } else {
                DailyEvidence.ATTRIBUTED
            },
            hasDailyPatrolFact = true,
        )
    }

    private fun List<PlatoonSnapshot>.lastBefore(instant: Instant): PlatoonSnapshot? =
        lastOrNull { it.capturedAt.isBefore(instant) }

    /**
     * Reconciles Monday-through-Saturday cells with the latest weekly counter.
     *
     * The game's weekly counter resets on Monday while this report starts on
     * Sunday. Every standard-week merit day is one of 0/50/90, so the
     * captured cumulative counter can be solved into all compatible daily
     * sequences. Values shared by every sequence are exact; ambiguous values
     * use the documented Monday-first estimate instead of remaining unknown.
     * The latest in-progress day remains a lower bound until it reaches the
     * daily maximum or a boundary capture closes it.
     */
    private fun inferStandardWeekCells(
        uid: Long,
        days: List<LocalDate>,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        cells: List<DayCell>,
    ): List<DayCell> {
        val mutable = inferSundayMerit(
            uid = uid,
            days = days,
            zoneId = zoneId,
            snapshots = snapshots,
            cells = cells,
        ).toMutableList()
        val observations = snapshots.mapNotNull { snapshot ->
            snapshot.member(uid)?.let { snapshot to it }
        }
        val monday = days.first().plusDays(1)
        val observationsInCounterWeek = observations.mapNotNull { observation ->
            val day = PlatoonPeriods.gameDay(observation.first.capturedAt, zoneId)
            observation.takeIf { day in monday..days.last() }?.let { day to it }
        }
        val latest = observationsInCounterWeek.maxByOrNull { it.second.first.capturedAt }
            ?: return mutable
        val latestDay = latest.first
        val latestCounter = latest.second.second.weeklyMerit
        val firstObserved = observationsInCounterWeek.minBy { it.second.first.capturedAt }
        val knownAbsentBeforeFirstObservation = snapshots.any { snapshot ->
            val gameDay = PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId)
            !gameDay.isBefore(monday) &&
                gameDay.isBefore(firstObserved.first) &&
                snapshot.capturedAt.isBefore(firstObserved.second.first.capturedAt) &&
                snapshot.member(uid) == null
        }
        val activeStart = if (knownAbsentBeforeFirstObservation) firstObserved.first else monday
        val activeIndexes = days.indices.filter { index -> days[index] in activeStart..latestDay }
        if (activeIndexes.isEmpty()) return mutable

        val lowerBoundsByDay = observationsInCounterWeek
            .groupBy({ it.first }, { it.second.second.weeklyMerit })
            .mapValues { (_, values) -> values.max() }
        val compatible = compatibleStandardAllocations(
            indexes = activeIndexes,
            days = days,
            cells = mutable,
            lowerBoundsByDay = lowerBoundsByDay,
            target = latestCounter,
        )
        if (compatible.isEmpty()) return mutable

        val selected = compatible.first()
        activeIndexes.forEachIndexed { position, index ->
            val existing = mutable[index]
            if (existing.manualOverride != null) return@forEachIndexed
            val merit = selected[position]
            val exactAcrossCandidates = compatible.all { it[position] == merit }
            val isLatestObservedDay = days[index] == latestDay
            val latestDayClosed = existing.evidence == DailyEvidence.ATTRIBUTED ||
                merit == MAX_STANDARD_DAILY_MERIT
            val evidence = when {
                isLatestObservedDay && exactAcrossCandidates && !latestDayClosed ->
                    DailyEvidence.PARTIAL_DAY
                exactAcrossCandidates -> DailyEvidence.ATTRIBUTED
                else -> DailyEvidence.SPARSE_INFERRED
            }
            mutable[index] = existing.copy(
                meritDelta = merit,
                scoreDelta = 0L,
                inference = ActivityInference.infer(
                    meritDelta = merit,
                    scoreDelta = 0L,
                    gunsmokeActive = false,
                ),
                evidence = evidence,
            )
        }
        return mutable
    }

    private fun inferSundayMerit(
        uid: Long,
        days: List<LocalDate>,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        cells: List<DayCell>,
    ): List<DayCell> {
        val sundayCell = cells.first()
        if (sundayCell.manualOverride != null || sundayCell.evidence == DailyEvidence.ATTRIBUTED) {
            return cells
        }
        val monday = days.first().plusDays(1)
        val mondayStart = PlatoonPeriods.periodStartInstant(monday, zoneId)
        val sundayObservation = snapshots.lastOrNull { snapshot ->
            snapshot.capturedAt.isBefore(mondayStart) &&
                PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId) == days.first() &&
                snapshot.member(uid) != null
        } ?: return cells
        val laterObservation = snapshots.firstOrNull { snapshot ->
            !snapshot.capturedAt.isBefore(mondayStart) &&
                PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId) in monday..days.last() &&
                snapshot.member(uid) != null
        } ?: return cells
        val sundayMember = requireNotNull(sundayObservation.member(uid))
        val laterMember = requireNotNull(laterObservation.member(uid))
        val remainingSundayMerit = laterMember.totalMerit -
            sundayMember.totalMerit -
            laterMember.weeklyMerit
        if (remainingSundayMerit < 0L) return cells

        val merit = (sundayCell.meritDelta ?: 0L) + remainingSundayMerit
        val evidence = if (sundayCell.meritDelta != null) {
            DailyEvidence.ATTRIBUTED
        } else {
            DailyEvidence.SPARSE_INFERRED
        }
        return cells.toMutableList().apply {
            this[0] = sundayCell.copy(
                meritDelta = merit,
                scoreDelta = 0L,
                inference = ActivityInference.infer(
                    meritDelta = merit,
                    scoreDelta = 0L,
                    gunsmokeActive = false,
                ),
                evidence = evidence,
            )
        }
    }

    private fun compatibleStandardAllocations(
        indexes: List<Int>,
        days: List<LocalDate>,
        cells: List<DayCell>,
        lowerBoundsByDay: Map<LocalDate, Long>,
        target: Long,
    ): List<List<Long>> {
        if (target < 0L || target > indexes.size * MAX_STANDARD_DAILY_MERIT) {
            return emptyList()
        }
        val results = mutableListOf<List<Long>>()
        val candidate = mutableListOf<Long>()

        fun search(position: Int, cumulative: Long) {
            if (position == indexes.size) {
                if (cumulative == target) results += candidate.toList()
                return
            }
            val index = indexes[position]
            val fixed = cells[index].manualOverride?.meritDelta
                ?: cells[index].meritDelta?.takeIf {
                    cells[index].evidence == DailyEvidence.ATTRIBUTED
                }
            val options = fixed?.let { longArrayOf(it) } ?: STANDARD_DAILY_MERIT
            options.forEach { merit ->
                val next = cumulative + merit
                if (next > target) return@forEach
                val lowerBound = lowerBoundsByDay[days[index]]
                if (lowerBound != null && next < lowerBound) return@forEach
                val remainingSlots = indexes.size - position - 1
                if (next + remainingSlots * MAX_STANDARD_DAILY_MERIT < target) {
                    return@forEach
                }
                candidate += merit
                search(position + 1, next)
                candidate.removeAt(candidate.lastIndex)
            }
        }

        search(position = 0, cumulative = 0L)
        return results
    }

    private fun PlatoonSnapshot.member(uid: Long): SnapshotMember? =
        members.firstOrNull { it.uid == uid }

    private fun counterDelta(current: Long, previous: Long): Long =
        if (current >= previous) current - previous else current

    private const val BOUNDARY_WINDOW_MINUTES = 15L
    private const val MAX_STANDARD_DAILY_MERIT = 90L
    private const val STANDARD_PATROL_MERIT = 90L
    private val STANDARD_DAILY_MERIT = longArrayOf(90L, 50L, 0L)
}
