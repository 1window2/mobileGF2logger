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
                    it.evidence == DailyEvidence.NO_OBSERVATION ||
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
                val known = days.mapNotNull(DayCell::attempts)
                val total = known.sum()
                return total.takeIf {
                    known.isNotEmpty() && (total > 0 || days.all { day -> day.attempts != null })
                }
            }

        val totalAttemptsCertainty: MetricCertainty
            get() = when {
                totalAttempts == null -> MetricCertainty.UNKNOWN
                totalAttempts == ActivityInference.MAX_WEEKLY_ATTEMPTS -> MetricCertainty.EXACT
                days.all { it.attemptsCertainty == MetricCertainty.EXACT } -> MetricCertainty.EXACT
                else -> MetricCertainty.LOWER_BOUND
            }

        val totalMeritCertainty: MetricCertainty
            get() = when {
                !isGunsmokeWeek && totalMerit == MAX_STANDARD_WEEKLY_MERIT -> MetricCertainty.EXACT
                days.all { it.meritCertainty == MetricCertainty.EXACT } -> MetricCertainty.EXACT
                totalMerit > 0L || days.any { it.meritDelta != null } -> MetricCertainty.LOWER_BOUND
                else -> MetricCertainty.UNKNOWN
            }

        val totalScoreCertainty: MetricCertainty
            get() = when {
                !isGunsmokeWeek -> MetricCertainty.EXACT
                hasFinalGunsmokeScore -> MetricCertainty.EXACT
                days.all { it.scoreCertainty == MetricCertainty.EXACT } -> MetricCertainty.EXACT
                totalScore > 0L || days.any { it.scoreDelta != null } -> MetricCertainty.LOWER_BOUND
                else -> MetricCertainty.UNKNOWN
            }

        val loginDays: Int?
            get() = days.count { it.attended == true }.takeIf { count ->
                count > 0 || days.all { it.attended != null }
            }

        val loginDaysCertainty: MetricCertainty
            get() = aggregateBooleanCertainty(days.map(DayCell::attended), loginDays)

        val patrolDays: Int?
            get() = days.count { it.dailyPatrol == true }.takeIf { count ->
                count > 0 || days.all { it.dailyPatrol != null }
            }

        val patrolDaysCertainty: MetricCertainty
            get() = aggregateBooleanCertainty(days.map(DayCell::dailyPatrol), patrolDays)

        val hasUnknownGunsmokeActivityTotals: Boolean
            get() = isGunsmokeWeek && days.any { cell ->
                cell.isGunsmokeActivityUnknown
            }

        val hasIncompleteEvidence: Boolean
            get() = days.any {
                it.evidence == DailyEvidence.NO_OBSERVATION ||
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
        val isGunsmokeWeek: Boolean = false,
        val metricObservedAt: Instant? = null,
        val hasClosingBoundary: Boolean = false,
        val solvedAttempts: Int? = null,
        val solvedMeritCertainty: MetricCertainty? = null,
        val solvedScoreCertainty: MetricCertainty? = null,
        val solvedAttemptsCertainty: MetricCertainty? = null,
        val solvedAttended: Boolean? = null,
        val solvedDailyPatrol: Boolean? = null,
    ) {
        val attempts: Int?
            get() = manualOverride?.attempts
                ?: solvedAttempts
                ?: inference?.exactAttempts?.takeUnless {
                    it == 0 && evidence == DailyEvidence.PARTIAL_DAY
                }
                ?: inference?.attemptsLowerBound?.takeIf { it > 0 }
                ?: 1.takeIf { scoreDelta?.let { it > 0L } == true }

        val meritCertainty: MetricCertainty
            get() = when {
                meritDelta == null -> MetricCertainty.UNKNOWN
                manualOverride?.meritDelta != null -> MetricCertainty.EXACT
                solvedMeritCertainty != null -> solvedMeritCertainty
                hasClosingBoundary ||
                    evidence == DailyEvidence.ATTRIBUTED ||
                    hasExactGunsmokeMeritComponents -> MetricCertainty.EXACT
                evidence == DailyEvidence.PARTIAL_DAY -> MetricCertainty.LOWER_BOUND
                else -> MetricCertainty.UNKNOWN
            }

        val attemptsCertainty: MetricCertainty
            get() = when {
                attempts == null -> MetricCertainty.UNKNOWN
                manualOverride?.attempts != null -> MetricCertainty.EXACT
                solvedAttemptsCertainty != null -> solvedAttemptsCertainty
                hasClosingBoundary && inference?.exactAttempts != null -> MetricCertainty.EXACT
                evidence == DailyEvidence.ATTRIBUTED && inference?.exactAttempts != null ->
                    MetricCertainty.EXACT
                attempts == ActivityInference.MAX_DAILY_ATTEMPTS -> MetricCertainty.EXACT
                else -> MetricCertainty.LOWER_BOUND
            }

        val scoreCertainty: MetricCertainty
            get() = when {
                scoreDelta == null -> MetricCertainty.UNKNOWN
                manualOverride?.scoreDelta != null -> MetricCertainty.EXACT
                solvedScoreCertainty != null -> solvedScoreCertainty
                hasClosingBoundary || hasFinalGunsmokeScore -> MetricCertainty.EXACT
                evidence == DailyEvidence.ATTRIBUTED && inference?.exactAttempts != null ->
                    MetricCertainty.EXACT
                attempts == ActivityInference.MAX_DAILY_ATTEMPTS -> MetricCertainty.EXACT
                else -> MetricCertainty.LOWER_BOUND
            }

        val attended: Boolean?
            get() = true.takeIf {
                hasDailyPatrolFact || manualOverride?.dailyPatrol == true || hasLoginFact ||
                    scoreDelta?.let { it > 0L } == true ||
                    attempts?.let { it > 0 } == true ||
                    inference?.attended == true
            } ?: manualOverride?.attended
                ?: solvedAttended
                ?: inference?.attended?.takeIf { evidence == DailyEvidence.ATTRIBUTED }

        val dailyPatrol: Boolean?
            get() = true.takeIf { hasDailyPatrolFact }
                ?: manualOverride?.dailyPatrol
                ?: solvedDailyPatrol
                ?: inference?.dailyPatrol?.takeIf {
                    if (!isGunsmokeWeek) {
                        evidence == DailyEvidence.ATTRIBUTED
                    } else {
                        attemptsCertainty == MetricCertainty.EXACT &&
                            scoreCertainty == MetricCertainty.EXACT &&
                            attended == true &&
                            (it || hasClosingBoundary || evidence == DailyEvidence.ATTRIBUTED)
                    }
                }

        private val hasExactGunsmokeMeritComponents: Boolean
            get() = isGunsmokeWeek &&
                scoreCertainty == MetricCertainty.EXACT &&
                attemptsCertainty == MetricCertainty.EXACT &&
                attended == true &&
                dailyPatrol == true &&
                inference?.candidates?.singleOrNull()?.let { candidate ->
                    candidate.attempts == attempts &&
                        candidate.attended &&
                        candidate.dailyPatrol
                } == true

        val precision: EvidencePrecision?
            get() = if (manualOverride != null) EvidencePrecision.MANUAL else inference?.precision

        val observed: Boolean
            get() = manualOverride != null ||
                evidence == DailyEvidence.MANUAL ||
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
        membershipEvents: List<MemberEvent> = emptyList(),
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
            .filter { PlatoonPeriods.gameDay(it.occurredAt, zoneId) in days }
            .groupBy { it.uid to PlatoonPeriods.gameDay(it.occurredAt, zoneId) }
            .mapValues { (_, facts) -> facts.minBy(DailyPatrolFact::occurredAt) }
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
        val rosterCutoff = minOf(
            PlatoonPeriods.periodStartInstant(start.plusDays(7), zoneId),
            asOf.plusMillis(1),
        )
        val uids = sortedSnapshots.flatMap { it.members }.associateBy(SnapshotMember::uid).toMutableMap()
        val latestSnapshotAtByUid = buildMap {
            sortedSnapshots.forEach { snapshot ->
                snapshot.members.forEach { member -> put(member.uid, snapshot.capturedAt) }
            }
        }
        membershipEvents.asSequence()
            .filter { event ->
                event.source == EvidenceSource.GAME_UPDATES &&
                    event.precision == EvidencePrecision.EXACT &&
                    event.occurredAt?.isBefore(rosterCutoff) == true
            }
            .sortedWith(membershipEventComparator())
            .forEach { event ->
                val occurredAt = requireNotNull(event.occurredAt)
                val snapshotAt = latestSnapshotAtByUid[event.uid]
                if (snapshotAt != null && !occurredAt.isAfter(snapshotAt)) return@forEach
                val existing = uids[event.uid]
                if (existing != null && event.note.isNotBlank()) {
                    uids[event.uid] = existing.copy(name = event.note)
                } else if (
                    existing == null &&
                    event.type in setOf(MemberEventType.JOINED, MemberEventType.REJOINED)
                ) {
                    uids[event.uid] = SnapshotMember(
                        uid = event.uid,
                        name = event.note.ifBlank { "#${event.uid}" },
                        level = 0,
                        weeklyMerit = 0,
                        totalMerit = 0,
                        highScore = 0,
                        totalScore = 0,
                        lastLogin = 0,
                    )
                }
            }
        overridesByCell.keys.map { it.first }.forEach { uid ->
            if (uid !in uids) {
                uids[uid] = SnapshotMember(uid, "#$uid", 0, 0, 0, 0, 0, 0)
            }
        }
        val rosterSnapshot = sortedSnapshots.lastBefore(rosterCutoff)
        val rosterAtPeriodEnd = applyMembershipEventsToRoster(
            rosterSnapshot?.members?.map(SnapshotMember::uid).orEmpty().toSet(),
            membershipEvents,
            rosterSnapshot?.capturedAt,
            rosterCutoff,
        )

        val rows = uids.values.map { latestKnown ->
            val derivedCells = days.map { day ->
                buildCell(
                    uid = latestKnown.uid,
                    day = day,
                    zoneId = zoneId,
                    snapshots = sortedSnapshots,
                    isGunsmoke = isGunsmoke,
                    manualOverride = overridesByCell[latestKnown.uid to day],
                    dailyPatrolFact = patrolFactsByMemberDay[latestKnown.uid to day],
                    hasLoginFact = latestKnown.uid to day in loginFactsByMemberDay,
                )
            }
            val cells = if (isGunsmoke) {
                GunsmokeWeekSolver.solve(
                    uid = latestKnown.uid,
                    days = days,
                    zoneId = zoneId,
                    snapshots = sortedSnapshots,
                    cells = derivedCells,
                    dailyPatrolFacts = dailyPatrolFacts,
                )
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
        dailyPatrolFact: DailyPatrolFact?,
        hasLoginFact: Boolean,
    ): DayCell {
        if (manualOverride != null) {
            val derived = buildCell(
                uid = uid,
                day = day,
                zoneId = zoneId,
                snapshots = snapshots,
                isGunsmoke = isGunsmoke,
                manualOverride = null,
                dailyPatrolFact = dailyPatrolFact,
                hasLoginFact = hasLoginFact,
            )
            val merit = manualOverride.meritDelta ?: derived.meritDelta
            val score = manualOverride.scoreDelta ?: derived.scoreDelta
            return derived.copy(
                meritDelta = merit,
                scoreDelta = score,
                inference = if (merit != null && score != null) {
                    ActivityInference.infer(merit, score, isGunsmoke)
                } else {
                    derived.inference
                },
                manualOverride = manualOverride,
            )
        }
        val start = PlatoonPeriods.periodStartInstant(day, zoneId)
        val end = PlatoonPeriods.periodStartInstant(day.plusDays(1), zoneId)
        val observations = snapshots.filter {
            it.capturedAt.isAfter(start) && !it.capturedAt.isAfter(end)
        }
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
        val hasClosingBoundary = lastObservation?.first?.capturedAt == end
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
                    isGunsmokeWeek = isGunsmoke,
                    hasClosingBoundary = hasClosingBoundary,
                ),
                isGunsmoke,
                dailyPatrolFact,
                hasLoginFact,
            )
        }
        if (observations.isEmpty()) {
            return applyActivityFacts(
                DayCell(
                    day,
                    null,
                    null,
                    null,
                    DailyEvidence.NO_OBSERVATION,
                    isGunsmokeWeek = isGunsmoke,
                ),
                isGunsmoke,
                dailyPatrolFact,
                hasLoginFact,
            )
        }
        if (after == null && before == null) {
            return applyActivityFacts(
                DayCell(
                    day,
                    null,
                    null,
                    null,
                    DailyEvidence.NO_OBSERVATION,
                    isGunsmokeWeek = isGunsmoke,
                ),
                isGunsmoke,
                dailyPatrolFact,
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
                isGunsmokeWeek = isGunsmoke,
                metricObservedAt = lastObservation?.first?.capturedAt,
                hasClosingBoundary = hasClosingBoundary,
            ),
            isGunsmoke,
            dailyPatrolFact,
            hasLoginFact,
        )
    }

    private fun applyActivityFacts(
        cell: DayCell,
        isGunsmoke: Boolean,
        dailyPatrolFact: DailyPatrolFact?,
        hasLoginFact: Boolean,
    ): DayCell {
        val hasDailyPatrolFact = dailyPatrolFact != null
        if (!hasDailyPatrolFact && !hasLoginFact) return cell
        val minimumMerit = if (hasDailyPatrolFact) STANDARD_PATROL_MERIT else STANDARD_LOGIN_MERIT
        val merit = when {
            cell.meritDelta == null -> minimumMerit
            else -> maxOf(cell.meritDelta, minimumMerit)
        }
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

    private fun applyMembershipEventsToRoster(
        snapshotRoster: Set<Long>,
        events: List<MemberEvent>,
        snapshotAt: Instant?,
        cutoff: Instant,
    ): Set<Long> = snapshotRoster.toMutableSet().apply {
        events.asSequence()
            .filter { event ->
                event.source == EvidenceSource.GAME_UPDATES &&
                    event.precision == EvidencePrecision.EXACT &&
                    event.occurredAt?.let { occurredAt ->
                        occurredAt.isBefore(cutoff) &&
                            (snapshotAt == null || occurredAt.isAfter(snapshotAt))
                    } == true
            }
            .sortedWith(membershipEventComparator())
            .forEach { event ->
                when (event.type) {
                    MemberEventType.JOINED, MemberEventType.REJOINED -> add(event.uid)
                    MemberEventType.LEFT, MemberEventType.REMOVED -> remove(event.uid)
                    MemberEventType.RENAMED -> Unit
                }
            }
    }

    private fun membershipEventComparator(): Comparator<MemberEvent> =
        compareBy<MemberEvent> { it.occurredAt }
            .thenBy { event ->
                when (event.type) {
                    MemberEventType.JOINED, MemberEventType.REJOINED -> 0
                    MemberEventType.RENAMED -> 1
                    MemberEventType.LEFT, MemberEventType.REMOVED -> 2
                }
            }
            .thenBy(MemberEvent::id)

    private fun aggregateBooleanCertainty(
        values: List<Boolean?>,
        count: Int?,
    ): MetricCertainty = when {
        count == null -> MetricCertainty.UNKNOWN
        count == values.size -> MetricCertainty.EXACT
        values.all { it != null } -> MetricCertainty.EXACT
        else -> MetricCertainty.LOWER_BOUND
    }

    /**
     * Reconciles Monday-through-Saturday cells with the latest weekly counter.
     *
     * The game's weekly counter resets on Monday while this report starts on
     * Sunday. Every standard-week merit day is one of 0/50/90, so the
     * captured cumulative counter can be solved into all compatible daily
     * sequences. Values shared by every sequence are exact. Ambiguous closed
     * days remain unknown, while the latest in-progress stage is a lower bound
     * until it reaches the daily maximum.
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

        // Selection is used only when every compatible allocation agrees for a
        // day. Ambiguous positions never expose an arbitrary allocation.
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
            val currentLowerBound = stageValues.minOrNull()?.takeIf { it > 0L }
            val openLatestDay = day == latestDay && !dayClosed
            val merit = when {
                existing.hasDailyPatrolFact -> MAX_STANDARD_DAILY_MERIT
                openLatestDay -> currentLowerBound
                exactAcrossCandidates -> selectedMerit
                else -> null
            }
            val evidence = when {
                existing.hasDailyPatrolFact -> DailyEvidence.ATTRIBUTED
                openLatestDay && currentLowerBound == MAX_STANDARD_DAILY_MERIT ->
                    DailyEvidence.ATTRIBUTED
                openLatestDay -> DailyEvidence.PARTIAL_DAY
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
        if (remainingSundayMerit !in STANDARD_DAILY_STAGE) return cells

        val observedSundayPrefix = sundayCell.meritDelta ?: return cells
        val merit = observedSundayPrefix + remainingSundayMerit
        if (merit !in STANDARD_DAILY_STAGE) return cells
        return cells.toMutableList().apply {
            this[0] = sundayCell.copy(
                meritDelta = merit,
                scoreDelta = 0L,
                inference = ActivityInference.infer(
                    meritDelta = merit,
                    scoreDelta = 0L,
                    gunsmokeActive = false,
                ),
                evidence = DailyEvidence.ATTRIBUTED,
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
    private const val MAX_STANDARD_WEEKLY_MERIT = MAX_STANDARD_DAILY_MERIT * 7
    private const val STANDARD_LOGIN_MERIT = 50L
    private const val STANDARD_PATROL_MERIT = 90L
    private val STANDARD_DAILY_MERIT = longArrayOf(90L, 50L, 0L)
    private val STANDARD_DAILY_STAGE = setOf(0L, 50L, 90L)
}
