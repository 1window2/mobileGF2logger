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
        val hasFinalGunsmokeScore: Boolean = false,
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
                    it.evidence == DailyEvidence.PARTIAL_DAY ||
                    it.evidence == DailyEvidence.SPARSE_INFERRED
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
        val hasLoginFact: Boolean = false,
        val hasFinalGunsmokeScore: Boolean = false,
    ) {
        val attempts: Int?
            get() = if (manualOverride != null) manualOverride.attempts else inference?.selected?.attempts

        val attended: Boolean?
            get() = if (manualOverride != null) {
                manualOverride.attended
            } else {
                true.takeIf { hasDailyPatrolFact || hasLoginFact }
                    ?: inference?.selected?.attended?.takeIf {
                        evidence == DailyEvidence.ATTRIBUTED
                    }
            }

        val dailyPatrol: Boolean?
            get() = if (manualOverride != null) {
                manualOverride.dailyPatrol
            } else {
                true.takeIf { hasDailyPatrolFact }
                    ?: inference?.selected?.dailyPatrol?.takeIf {
                        evidence == DailyEvidence.ATTRIBUTED
                    }
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
                    DailyEvidence.PARTIAL_DAY,
                    DailyEvidence.SPARSE_INFERRED,
                )
    }

    fun build(
        referenceDay: LocalDate,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        overrides: List<WeeklyCellOverride> = emptyList(),
        dailyPatrolFacts: List<DailyPatrolFact> = emptyList(),
        asOf: Instant = Instant.now(),
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
        val loginFactsByMemberDay = sortedSnapshots
            .flatMap { snapshot ->
                snapshot.members.mapNotNull { member ->
                    member.lastLogin
                        .takeIf { it > 0L }
                        ?.let(Instant::ofEpochSecond)
                        ?.let { member.uid to PlatoonPeriods.gameDay(it, zoneId) }
                }
            }
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
                    hasLoginFact = latestKnown.uid to day in loginFactsByMemberDay,
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
                    asOf = asOf,
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
                hasFinalGunsmokeScore = cells.any(DayCell::hasFinalGunsmokeScore),
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
        hasLoginFact: Boolean,
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
                hasLoginFact = hasLoginFact,
            )
        }
        val observations = snapshots.filter { it.capturedAt.isAfter(start) && it.capturedAt.isBefore(end) }
        val baselineSnapshot = snapshots.lastOrNull { snapshot ->
            !snapshot.capturedAt.isAfter(start) &&
                !snapshot.capturedAt.isBefore(start.minus(BOUNDARY_WINDOW_MINUTES, ChronoUnit.MINUTES))
        }
        val before = baselineSnapshot?.member(uid)
        val lastObservation = observations.asReversed().firstNotNullOfOrNull { snapshot ->
            snapshot.member(uid)?.let { snapshot to it }
        }
        val after = lastObservation?.second
        val hasFinalGunsmokeScore = lastObservation?.let { (snapshot, _) ->
            PlatoonPeriods.isFinalGunsmokeScoreCapture(day, snapshot.capturedAt, zoneId)
        } == true
        if (baselineSnapshot == null) {
            return applyActivityFacts(
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
                    hasFinalGunsmokeScore = hasFinalGunsmokeScore,
                ),
                isGunsmoke,
                hasDailyPatrolFact,
                hasLoginFact,
            )
        }
        if (observations.isEmpty()) {
            return applyActivityFacts(
                DayCell(day, null, null, null, DailyEvidence.NO_OBSERVATION),
                isGunsmoke,
                hasDailyPatrolFact,
                hasLoginFact,
            )
        }
        if (after == null && before == null) {
            return applyActivityFacts(
                DayCell(day, null, null, null, DailyEvidence.NO_OBSERVATION),
                isGunsmoke,
                hasDailyPatrolFact,
                hasLoginFact,
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
        return applyActivityFacts(
            DayCell(
                gameDay = day,
                meritDelta = meritDelta,
                scoreDelta = scoreDelta,
                inference = scoreDelta?.let { ActivityInference.infer(meritDelta, it, isGunsmoke) },
                // A capture immediately before 05:00 is still not a final
                // boundary: merit can change before reset. Exactness is
                // established later by the counter constraint solver.
                evidence = DailyEvidence.PARTIAL_DAY,
                hasFinalGunsmokeScore = hasFinalGunsmokeScore,
            ),
            isGunsmoke,
            hasDailyPatrolFact,
            hasLoginFact,
        )
    }

    private fun applyActivityFacts(
        cell: DayCell,
        isGunsmoke: Boolean,
        hasDailyPatrolFact: Boolean,
        hasLoginFact: Boolean,
    ): DayCell {
        if (cell.manualOverride != null) return cell
        if (!hasDailyPatrolFact && !hasLoginFact) return cell
        val minimumMerit = if (hasDailyPatrolFact) STANDARD_PATROL_MERIT else STANDARD_LOGIN_MERIT
        val merit = maxOf(cell.meritDelta ?: 0L, minimumMerit)
        val score = cell.scoreDelta ?: if (isGunsmoke) null else 0L
        return cell.copy(
            meritDelta = merit,
            scoreDelta = score,
            inference = score?.let { ActivityInference.infer(merit, it, isGunsmoke) },
            evidence = if (isGunsmoke) {
                DailyEvidence.PARTIAL_DAY
            } else if (hasDailyPatrolFact) {
                DailyEvidence.ATTRIBUTED
            } else {
                DailyEvidence.PARTIAL_DAY
            },
            hasDailyPatrolFact = hasDailyPatrolFact,
            hasLoginFact = hasLoginFact,
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
        asOf: Instant,
    ): List<DayCell> {
        val mutable = inferSundayMerit(
            uid = uid,
            days = days,
            zoneId = zoneId,
            snapshots = snapshots,
            cells = cells,
        ).toMutableList()
        val observations = snapshots.mapNotNull { snapshot ->
            snapshot.member(uid)?.let { member ->
                CounterObservation(
                    capturedAt = snapshot.capturedAt,
                    gameDay = PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId),
                    counter = member.weeklyMerit,
                    lastLoginDay = member.lastLogin
                        .takeIf { it > 0L }
                        ?.let(Instant::ofEpochSecond)
                        ?.let { PlatoonPeriods.gameDay(it, zoneId) },
                )
            }
        }
        val monday = days.first().plusDays(1)
        val observationsInCounterWeek = observations.filter { it.gameDay in monday..days.last() }
        val latest = observationsInCounterWeek.maxByOrNull(CounterObservation::capturedAt)
            ?: return mutable
        val latestDay = latest.gameDay
        val firstObserved = observationsInCounterWeek.minBy(CounterObservation::capturedAt)
        val knownAbsentBeforeFirstObservation = snapshots.any { snapshot ->
            val gameDay = PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId)
            !gameDay.isBefore(monday) &&
                gameDay.isBefore(firstObserved.gameDay) &&
                snapshot.capturedAt.isBefore(firstObserved.capturedAt) &&
                snapshot.member(uid) == null
        }
        val activeStart = if (knownAbsentBeforeFirstObservation) firstObserved.gameDay else monday
        val activeIndexes = days.indices.filter { index -> days[index] in activeStart..latestDay }
        if (activeIndexes.isEmpty()) return mutable

        val confirmedNoLoginDays = activeIndexes.mapNotNull { index ->
            val day = days[index]
            val start = PlatoonPeriods.periodStartInstant(day, zoneId)
            val end = PlatoonPeriods.periodStartInstant(day.plusDays(1), zoneId)
            day.takeIf {
                !end.isAfter(asOf) &&
                    snapshots.any { snapshot ->
                        !snapshot.capturedAt.isBefore(end) &&
                            snapshot.member(uid)?.lastLogin?.takeIf { it > 0L }?.let {
                                Instant.ofEpochSecond(it).isBefore(start)
                            } == true
                    }
            }
        }.toSet()
        val compatible = compatibleStandardAllocations(
            indexes = activeIndexes,
            days = days,
            cells = mutable,
            observations = observationsInCounterWeek,
            confirmedNoLoginDays = confirmedNoLoginDays,
        )
        if (compatible.isEmpty()) return mutable

        // Prefer the smallest final total consistent with captured progress;
        // ties retain the documented Monday-first 90/50/0 ordering. This keeps
        // unobserved future activity out of the displayed estimate.
        val selected = compatible.minWith(
            compareBy<List<Long>> { it.sum() }
                .thenComparator { left, right ->
                    left.indices.firstNotNullOfOrNull { index ->
                        java.lang.Long.compare(right[index], left[index]).takeIf { it != 0 }
                    } ?: 0
                },
        )
        activeIndexes.forEachIndexed { position, index ->
            val existing = mutable[index]
            if (existing.manualOverride != null) return@forEachIndexed
            val selectedMerit = selected[position]
            val exactAcrossCandidates = compatible.all { it[position] == selectedMerit }
            val day = days[index]
            val dayClosed = !PlatoonPeriods.periodStartInstant(day.plusDays(1), zoneId).isAfter(asOf)
            val latestCheckpoint = observationsInCounterWeek
                .filter { it.gameDay == day }
                .maxByOrNull(CounterObservation::capturedAt)
            val stageValues = latestCheckpoint?.let { checkpoint ->
                compatible.mapNotNull { allocation ->
                    checkpointStage(
                        allocation = allocation,
                        indexes = activeIndexes,
                        days = days,
                        checkpoint = checkpoint,
                    )
                }
            }.orEmpty()
            val currentLowerBound = stageValues.minOrNull()
            val merit = when {
                day == latestDay && !dayClosed && !exactAcrossCandidates ->
                    currentLowerBound ?: existing.meritDelta
                else -> selectedMerit
            }
            val evidence = when {
                existing.hasDailyPatrolFact -> DailyEvidence.ATTRIBUTED
                exactAcrossCandidates && (dayClosed || selectedMerit == MAX_STANDARD_DAILY_MERIT) ->
                    DailyEvidence.ATTRIBUTED
                day == latestDay && !dayClosed -> DailyEvidence.PARTIAL_DAY
                exactAcrossCandidates -> DailyEvidence.ATTRIBUTED
                else -> DailyEvidence.SPARSE_INFERRED
            }
            mutable[index] = existing.copy(
                meritDelta = merit,
                scoreDelta = 0L,
                inference = merit?.let {
                    ActivityInference.infer(
                        meritDelta = it,
                        scoreDelta = 0L,
                        gunsmokeActive = false,
                    )
                },
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
        observations: List<CounterObservation>,
        confirmedNoLoginDays: Set<LocalDate>,
    ): List<List<Long>> {
        val results = mutableListOf<List<Long>>()
        val candidate = mutableListOf<Long>()

        fun search(position: Int) {
            if (position == indexes.size) {
                if (
                    observations.all { observation ->
                        checkpointStage(candidate, indexes, days, observation) != null
                    }
                ) {
                    results += candidate.toList()
                }
                return
            }
            val index = indexes[position]
            val cell = cells[index]
            val fixed = cell.manualOverride?.meritDelta
                ?: STANDARD_PATROL_MERIT.takeIf { cell.hasDailyPatrolFact }
                ?: 0L.takeIf { days[index] in confirmedNoLoginDays }
            val options = when {
                fixed != null -> longArrayOf(fixed)
                cell.hasLoginFact -> longArrayOf(MAX_STANDARD_DAILY_MERIT, STANDARD_LOGIN_MERIT)
                else -> STANDARD_DAILY_MERIT
            }
            options.forEach { merit ->
                candidate += merit
                val partialIsCompatible = observations
                    .filter { observation ->
                        val observationIndex = days.indexOf(observation.gameDay)
                        observationIndex >= 0 && observationIndex <= index
                    }
                    .all { observation ->
                        val observationIndex = days.indexOf(observation.gameDay)
                        if (observationIndex > index) {
                            true
                        } else {
                            checkpointStage(candidate, indexes.take(candidate.size), days, observation) != null
                        }
                    }
                if (partialIsCompatible) {
                    search(position + 1)
                }
                candidate.removeAt(candidate.lastIndex)
            }
        }

        search(position = 0)
        return results
    }

    private fun checkpointStage(
        allocation: List<Long>,
        indexes: List<Int>,
        days: List<LocalDate>,
        checkpoint: CounterObservation,
    ): Long? {
        val checkpointIndex = days.indexOf(checkpoint.gameDay)
        val position = indexes.indexOf(checkpointIndex)
        if (position !in allocation.indices) return null
        val completedPrefix = allocation.take(position).sum()
        val stage = checkpoint.counter - completedPrefix
        val finalForDay = allocation[position]
        if (stage !in STANDARD_DAILY_STAGE || stage > finalForDay) return null
        val loginStageCompatible = when {
            checkpoint.lastLoginDay == checkpoint.gameDay ->
                stage == STANDARD_LOGIN_MERIT || stage == STANDARD_PATROL_MERIT
            checkpoint.lastLoginDay != null &&
                checkpoint.lastLoginDay.isBefore(checkpoint.gameDay) -> stage == 0L
            else -> true
        }
        return stage.takeIf { loginStageCompatible }
    }

    private data class CounterObservation(
        val capturedAt: Instant,
        val gameDay: LocalDate,
        val counter: Long,
        val lastLoginDay: LocalDate?,
    )

    private fun PlatoonSnapshot.member(uid: Long): SnapshotMember? =
        members.firstOrNull { it.uid == uid }

    private fun counterDelta(current: Long, previous: Long): Long =
        if (current >= previous) current - previous else current

    private const val BOUNDARY_WINDOW_MINUTES = 15L
    private const val MAX_STANDARD_DAILY_MERIT = 90L
    private const val STANDARD_LOGIN_MERIT = 50L
    private const val STANDARD_PATROL_MERIT = 90L
    private val STANDARD_DAILY_MERIT = longArrayOf(90L, 50L, 0L)
    private val STANDARD_DAILY_STAGE = setOf(0L, 50L, 90L)
}
