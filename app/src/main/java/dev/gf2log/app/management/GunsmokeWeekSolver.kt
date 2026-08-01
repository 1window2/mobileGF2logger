package dev.gf2log.app.management

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reconciles Gunsmoke captures as complete counter-compatible day paths.
 *
 * The latest capture on a day is a prefix of that day's final state. Between
 * consecutive days, total counters contain the previous tail plus the current
 * prefix, while the Monday-reset weekly counter constrains merit. Contiguous
 * observed runs are solved independently so missing days never discard later
 * usable evidence. Only values shared by every compatible path are published.
 */
internal object GunsmokeWeekSolver {
    fun solve(
        uid: Long,
        days: List<LocalDate>,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        cells: List<WeeklyReportBuilder.DayCell>,
        dailyPatrolFacts: List<DailyPatrolFact>,
    ): List<WeeklyReportBuilder.DayCell> {
        if (days.size != cells.size || days.isEmpty()) return cells

        val periodStart = PlatoonPeriods.periodStartInstant(days.first(), zoneId)
        val memberSnapshots = snapshots.mapNotNull { snapshot ->
            snapshot.member(uid)?.let { Checkpoint(snapshot.capturedAt, it) }
        }.sortedBy(Checkpoint::capturedAt)
        val anchor = memberSnapshots.lastOrNull { it.capturedAt.isBefore(periodStart) }
        val checkpoints = days.map { day ->
            memberSnapshots.lastOrNull { checkpoint ->
                reportDay(checkpoint.capturedAt, days, zoneId) == day
            }
        }
        val factsByDay = dailyPatrolFacts.asSequence()
            .filter { it.uid == uid }
            .groupBy { PlatoonPeriods.gameDay(it.occurredAt, zoneId) }
        val resolved = cells.toMutableList()
        var index = 0
        while (index < days.size) {
            while (index < days.size && checkpoints[index] == null) index++
            if (index >= days.size) break
            val runStart = index
            while (index + 1 < days.size && checkpoints[index + 1] != null) index++
            val runEnd = index
            solveRun(
                runStart = runStart,
                runEnd = runEnd,
                days = days,
                zoneId = zoneId,
                anchor = anchor,
                periodStart = periodStart,
                checkpoints = checkpoints,
                cells = resolved,
                factsByDay = factsByDay,
            ).forEachIndexed { cellIndex, cell -> resolved[cellIndex] = cell }
            index++
        }
        return resolved
    }

    /** Solves one contiguous observed run without depending on an earlier run. */
    private fun solveRun(
        runStart: Int,
        runEnd: Int,
        days: List<LocalDate>,
        zoneId: ZoneId,
        anchor: Checkpoint?,
        periodStart: Instant,
        checkpoints: List<Checkpoint?>,
        cells: List<WeeklyReportBuilder.DayCell>,
        factsByDay: Map<LocalDate, List<DailyPatrolFact>>,
    ): List<WeeklyReportBuilder.DayCell> {
        val firstCheckpoint = requireNotNull(checkpoints[runStart])
        val firstMetrics = initialPrefixMetrics(
            runStart = runStart,
            anchor = anchor,
            checkpoint = firstCheckpoint,
            periodStart = periodStart,
            cell = cells[runStart],
        ) ?: return cells
        val weeklyBase = firstCheckpoint.member.weeklyMerit - firstMetrics.merit
        if (runStart >= 1 && weeklyBase < 0L) return cells
        if (runStart == 1 && weeklyBase != 0L) return cells

        var partials = statesForMetrics(firstMetrics.merit, firstMetrics.score)
            .asSequence()
            .filter { state ->
                matchesCapturedEvidence(
                    state = state,
                    checkpoint = firstCheckpoint,
                    day = days[runStart],
                    zoneId = zoneId,
                    facts = factsByDay[days[runStart]].orEmpty(),
                )
            }
            .map { state ->
                PartialSolution(
                    captured = state,
                    weeklyFinalizedMerit = 0L,
                    finalStatesByDay = emptyList(),
                )
            }
            .toList()
        if (partials.isEmpty()) return cells
        val searchBudget = SearchBudget(MAX_SEARCH_OPERATIONS)

        for (absoluteIndex in runStart + 1..runEnd) {
            val currentCheckpoint = requireNotNull(checkpoints[absoluteIndex])
            val previousCheckpoint = requireNotNull(checkpoints[absoluteIndex - 1])
            if (
                currentCheckpoint.member.totalMerit < previousCheckpoint.member.totalMerit ||
                currentCheckpoint.member.totalScore < previousCheckpoint.member.totalScore
            ) {
                return cells
            }
            val meritDelta = currentCheckpoint.member.totalMerit - previousCheckpoint.member.totalMerit
            val scoreDelta = currentCheckpoint.member.totalScore - previousCheckpoint.member.totalScore
            val currentFacts = factsByDay[days[absoluteIndex]].orEmpty()
            val previousFacts = factsByDay[days[absoluteIndex - 1]].orEmpty()
            val extensionCache = mutableMapOf<ActivityState, List<ActivityState>>()
            val capturedCache = mutableMapOf<Metrics, List<ActivityState>>()
            val nextPartials = linkedMapOf<PartialKey, PartialSolution>()

            partials.forEach { partial ->
                val previousCaptured = partial.captured
                val maximumPreviousMerit = previousCaptured.merit + meritDelta
                val resetCrossed = runStart == 0 && absoluteIndex == 1
                val requiredPreviousFinalMerit = if (resetCrossed) {
                    previousCaptured.merit + meritDelta - currentCheckpoint.member.weeklyMerit
                } else {
                    null
                }
                val extensionStates = extensionCache.getOrPut(previousCaptured) {
                    if (
                        cells[absoluteIndex - 1].hasClosingBoundary ||
                        cells[absoluteIndex - 1].hasFinalGunsmokeScore ||
                        previousCaptured.attempts == ActivityInference.MAX_DAILY_ATTEMPTS
                    ) {
                        finalCandidates(
                            captured = previousCaptured,
                            cell = cells[absoluteIndex - 1],
                            facts = previousFacts,
                        )
                    } else {
                        statesExtending(
                            prefix = previousCaptured,
                            minimumMerit = requiredPreviousFinalMerit ?: previousCaptured.merit,
                            maximumMerit = maximumPreviousMerit,
                            maximumScore = previousCaptured.score + scoreDelta,
                            searchBudget = searchBudget,
                        ) ?: return cells
                    }
                }
                extensionStates.asSequence()
                        .filter { final ->
                            matchesFinalEvidence(
                                state = final,
                                cell = cells[absoluteIndex - 1],
                                facts = previousFacts,
                            )
                        }
                        .forEach { previousFinal ->
                            val currentMerit = meritDelta - (previousFinal.merit - previousCaptured.merit)
                            val currentScore = scoreDelta - (previousFinal.score - previousCaptured.score)
                            if (currentMerit < 0L || currentScore < 0L) return@forEach
                            val weeklyFinalizedMerit = if (resetCrossed) {
                                0L
                            } else {
                                partial.weeklyFinalizedMerit + previousFinal.merit
                            }
                            val activeWeeklyBase = if (runStart == 0 && absoluteIndex >= 1) {
                                0L
                            } else {
                                weeklyBase
                            }
                            val expectedWeeklyMerit =
                                activeWeeklyBase + weeklyFinalizedMerit + currentMerit
                            if (expectedWeeklyMerit != currentCheckpoint.member.weeklyMerit) {
                                return@forEach
                            }
                            capturedCache.getOrPut(Metrics(currentMerit, currentScore)) {
                                statesForMetrics(currentMerit, currentScore)
                            }.asSequence()
                                .filter { current ->
                                    matchesCapturedEvidence(
                                        state = current,
                                        checkpoint = currentCheckpoint,
                                        day = days[absoluteIndex],
                                        zoneId = zoneId,
                                        facts = currentFacts,
                                    )
                                }
                                .forEach { current ->
                                    val key = PartialKey(current, weeklyFinalizedMerit)
                                    val candidate = PartialSolution(
                                        captured = current,
                                        weeklyFinalizedMerit = weeklyFinalizedMerit,
                                        finalStatesByDay = partial.finalStatesByDay +
                                            listOf(setOf(previousFinal)),
                                    )
                                    nextPartials[key] = nextPartials[key]
                                        ?.mergeHistory(candidate)
                                        ?: candidate
                                    if (nextPartials.size > MAX_PARTIAL_SOLUTIONS) return cells
                                }
                        }
            }
            partials = nextPartials.values.toList()
            if (partials.isEmpty()) return cells
        }

        val completed = partials.mapNotNull { partial ->
            val finalStates = finalCandidates(
                captured = partial.captured,
                cell = cells[runEnd],
                facts = factsByDay[days[runEnd]].orEmpty(),
            ).distinct()
            finalStates.takeIf(List<*>::isNotEmpty)?.let {
                CompletedSolution(
                    partial = partial,
                    finalStates = finalStates,
                    lastDayFinalized = cells[runEnd].hasClosingBoundary ||
                        partial.captured.attempts == ActivityInference.MAX_DAILY_ATTEMPTS ||
                        cells[runEnd].hasFinalGunsmokeScore,
                )
            }
        }
        if (completed.isEmpty()) return cells

        val possibleFinalStates = MutableList(runEnd - runStart + 1) {
            linkedSetOf<ActivityState>()
        }
        completed.forEach { solution ->
            solution.partial.finalStatesByDay.forEachIndexed { dayIndex, states ->
                possibleFinalStates[dayIndex].addAll(states)
            }
            possibleFinalStates.last().addAll(solution.finalStates)
        }

        val resolved = cells.toMutableList()
        repeat(runEnd - runStart + 1) { localIndex ->
            val absoluteIndex = runStart + localIndex
            val finalized = localIndex < possibleFinalStates.lastIndex ||
                completed.all(CompletedSolution::lastDayFinalized)
            resolved[absoluteIndex] = project(
                cell = cells[absoluteIndex],
                states = possibleFinalStates[localIndex].toList(),
                finalized = finalized,
                observedAt = checkpoints[absoluteIndex]?.capturedAt,
            )
        }
        return resolved
    }

    private fun initialPrefixMetrics(
        runStart: Int,
        anchor: Checkpoint?,
        checkpoint: Checkpoint,
        periodStart: Instant,
        cell: WeeklyReportBuilder.DayCell,
    ): Metrics? {
        val direct = cell.meritDelta?.let { merit ->
            cell.scoreDelta?.let { score -> Metrics(merit, score) }
        }
        if (direct != null) return direct
        if (runStart != 0) return null
        if (
            anchor != null &&
            !anchor.capturedAt.isAfter(periodStart) &&
            Duration.between(anchor.capturedAt, periodStart) <= MAX_BOUNDARY_DISTANCE &&
            checkpoint.member.totalMerit >= anchor.member.totalMerit
        ) {
            val merit = checkpoint.member.totalMerit - anchor.member.totalMerit
            if (anchor.member.weeklyMerit + merit != checkpoint.member.weeklyMerit) return null
            return Metrics(merit = merit, score = checkpoint.member.totalScore)
        }
        return null
    }

    private fun statesForMetrics(merit: Long, score: Long): List<ActivityState> {
        if (merit < 0L || score < 0L) return emptyList()
        return ActivityInference.infer(merit, score, gunsmokeActive = true).candidates.map { candidate ->
            ActivityState(
                merit = merit,
                score = score,
                attempts = candidate.attempts,
                scoreMerit = candidate.scoreMerit,
                attended = candidate.attended,
                patrol = candidate.dailyPatrol,
            )
        }.distinct()
    }

    // Function Name: statesExtending
    // Description:
    // - Enumerates valid activity states directly from attempts, baseline merit and score rounding.
    // - Avoids rescanning every merit value and re-running ActivityInference for every score.
    // - Refuses pathological counter ranges so malformed data degrades to conservative cells.
    // Parameters:
    // - prefix: State already captured during the previous game day.
    // - maximumMerit: Largest previous-day final merit allowed by the counter transition.
    // - maximumScore: Largest previous-day final score allowed by the counter transition.
    // Returns:
    // - Valid extensions, or null when the deterministic search budget would be exceeded.
    private fun statesExtending(
        prefix: ActivityState,
        minimumMerit: Long,
        maximumMerit: Long,
        maximumScore: Long,
        searchBudget: SearchBudget,
    ): List<ActivityState>? {
        val effectiveMinimumMerit = maxOf(prefix.merit, minimumMerit)
        if (maximumMerit < effectiveMinimumMerit || maximumScore < prefix.score) {
            return emptyList()
        }
        val states = linkedSetOf<ActivityState>()
        var examinedScoreMerits = 0L
        for (attempts in prefix.attempts..ActivityInference.MAX_DAILY_ATTEMPTS) {
            for (baseline in ACTIVITY_BASELINES) {
                if (attempts > 0 && !baseline.attended) continue
                if (prefix.attended && !baseline.attended) continue
                if (prefix.patrol && !baseline.patrol) continue
                val fixedMerit = baseline.merit +
                    attempts * ActivityInference.MERIT_PER_ATTEMPT
                if (attempts == prefix.attempts) {
                    val merit = fixedMerit + prefix.scoreMerit
                    if (merit !in effectiveMinimumMerit..maximumMerit) continue
                    val state = ActivityState(
                        merit = merit,
                        score = prefix.score,
                        attempts = attempts,
                        scoreMerit = prefix.scoreMerit,
                        attended = baseline.attended,
                        patrol = baseline.patrol,
                    )
                    if (extends(prefix, state)) states += state
                    continue
                }
                val minimumScoreMerit = maxOf(
                    0L,
                    prefix.scoreMerit,
                    effectiveMinimumMerit - fixedMerit,
                )
                val maximumScoreMerit = minOf(
                    maximumScore / ActivityInference.SCORE_POINTS_PER_MERIT,
                    maximumMerit - fixedMerit,
                )
                if (maximumScoreMerit < minimumScoreMerit) continue
                val span = maximumScoreMerit - minimumScoreMerit + 1L
                examinedScoreMerits += span
                if (examinedScoreMerits > MAX_SCORE_MERIT_VALUES) return null

                for (scoreMerit in minimumScoreMerit..maximumScoreMerit) {
                    if (!searchBudget.consume()) return null
                    val merit = fixedMerit + scoreMerit
                    if (attempts == 0) {
                        if (scoreMerit != 0L) continue
                        val state = ActivityState(
                            merit = merit,
                            score = 0L,
                            attempts = 0,
                            scoreMerit = 0L,
                            attended = baseline.attended,
                            patrol = baseline.patrol,
                        )
                        if (extends(prefix, state)) states += state
                        continue
                    }

                    for (roundingLoss in 0 until attempts) {
                        val maximumRemainder = minOf(
                            9,
                            9 * attempts -
                                roundingLoss * ActivityInference.SCORE_POINTS_PER_MERIT.toInt(),
                        )
                        if (maximumRemainder < 0) continue
                        for (remainder in 0..maximumRemainder) {
                            val score =
                                (scoreMerit + roundingLoss) *
                                    ActivityInference.SCORE_POINTS_PER_MERIT + remainder
                            if (score > maximumScore) continue
                            val state = ActivityState(
                                merit = merit,
                                score = score,
                                attempts = attempts,
                                scoreMerit = scoreMerit,
                                attended = true,
                                patrol = baseline.patrol,
                            )
                            if (!searchBudget.consume()) return null
                            if (extends(prefix, state)) states += state
                            if (states.size > MAX_EXTENSION_STATES) return null
                        }
                    }
                }
            }
        }
        return states.toList()
    }

    private fun finalCandidates(
        captured: ActivityState,
        cell: WeeklyReportBuilder.DayCell,
        facts: List<DailyPatrolFact>,
    ): List<ActivityState> {
        val gunsmokeFixed = cell.hasClosingBoundary ||
            captured.attempts == ActivityInference.MAX_DAILY_ATTEMPTS ||
            cell.hasFinalGunsmokeScore
        val candidates = if (cell.hasClosingBoundary) {
            listOf(captured)
        } else if (gunsmokeFixed) {
            buildList {
                add(captured)
                if (!captured.attended) {
                    add(captured.copy(merit = captured.merit + LOGIN_MERIT, attended = true))
                    add(
                        captured.copy(
                            merit = captured.merit + LOGIN_MERIT + PATROL_MERIT,
                            attended = true,
                            patrol = true,
                        ),
                    )
                } else if (!captured.patrol) {
                    add(captured.copy(merit = captured.merit + PATROL_MERIT, patrol = true))
                }
            }
        } else {
            // An open sub-cap day can still gain score and attempts. Preserve
            // the captured state as a lower bound instead of inventing a final.
            listOf(captured)
        }
        return candidates.filter { final ->
            matchesFinalEvidence(final, cell, facts)
        }.ifEmpty {
            listOf(captured).filter { final -> matchesFinalEvidence(final, cell, facts) }
        }
    }

    private fun matchesCapturedEvidence(
        state: ActivityState,
        checkpoint: Checkpoint,
        day: LocalDate,
        zoneId: ZoneId,
        facts: List<DailyPatrolFact>,
    ): Boolean {
        val patrolBeforeCapture = facts.any { !it.occurredAt.isAfter(checkpoint.capturedAt) }
        if (patrolBeforeCapture && !state.patrol) return false
        val loginAt = checkpoint.member.lastLogin.takeIf { it > 0L }?.let(Instant::ofEpochSecond)
        if (
            loginAt != null &&
            !loginAt.isAfter(checkpoint.capturedAt) &&
            PlatoonPeriods.gameDay(loginAt, zoneId) == day &&
            !state.attended
        ) {
            return false
        }
        return true
    }

    private fun matchesFinalEvidence(
        state: ActivityState,
        cell: WeeklyReportBuilder.DayCell,
        facts: List<DailyPatrolFact>,
    ): Boolean {
        if (facts.isNotEmpty() && !state.patrol) return false
        if (cell.hasLoginFact && !state.attended) return false
        val override = cell.manualOverride ?: return true
        return (override.meritDelta == null || override.meritDelta == state.merit) &&
            (override.scoreDelta == null || override.scoreDelta == state.score) &&
            (override.attempts == null || override.attempts == state.attempts) &&
            (override.attended == null || override.attended == state.attended) &&
            (override.dailyPatrol == null || override.dailyPatrol == state.patrol)
    }

    private fun extends(prefix: ActivityState, final: ActivityState): Boolean {
        if (
            final.merit < prefix.merit ||
            final.score < prefix.score ||
            final.attempts < prefix.attempts ||
            final.scoreMerit < prefix.scoreMerit ||
            (prefix.attended && !final.attended) ||
            (prefix.patrol && !final.patrol)
        ) {
            return false
        }
        if (final.attempts == prefix.attempts) {
            return final.score == prefix.score && final.scoreMerit == prefix.scoreMerit
        }
        return prefix.attempts < ActivityInference.MAX_DAILY_ATTEMPTS
    }

    private fun project(
        cell: WeeklyReportBuilder.DayCell,
        states: List<ActivityState>,
        finalized: Boolean,
        observedAt: Instant?,
    ): WeeklyReportBuilder.DayCell {
        val merits = states.map(ActivityState::merit).distinct()
        val scores = states.map(ActivityState::score).distinct()
        val attempts = states.map(ActivityState::attempts).distinct()
        val attendedConsensus = states.map(ActivityState::attended).distinct().singleOrNull()
        val patrolConsensus = states.map(ActivityState::patrol).distinct().singleOrNull()
        val merit = cell.manualOverride?.meritDelta ?: merits.minOrNull()
        val score = cell.manualOverride?.scoreDelta ?: scores.minOrNull()
        val attemptValue = cell.manualOverride?.attempts ?: attempts.minOrNull()
        val exactScore = (finalized || attemptValue == ActivityInference.MAX_DAILY_ATTEMPTS || cell.hasFinalGunsmokeScore) &&
            scores.size == 1
        val exactAttempts = (finalized || attemptValue == ActivityInference.MAX_DAILY_ATTEMPTS || cell.hasFinalGunsmokeScore) &&
            attempts.size == 1
        val exactMerit = merits.size == 1 &&
            (finalized ||
                (exactScore &&
                    exactAttempts &&
                    attendedConsensus == true &&
                    patrolConsensus == true))
        val attended = attendedConsensus?.takeIf { it || finalized }
        val patrol = patrolConsensus?.takeIf { it || finalized }

        return cell.copy(
            meritDelta = merit,
            scoreDelta = score,
            inference = if (merit != null && score != null) {
                ActivityInference.infer(merit, score, gunsmokeActive = true)
            } else {
                cell.inference
            },
            evidence = if (exactMerit && exactScore && exactAttempts) {
                DailyEvidence.ATTRIBUTED
            } else {
                DailyEvidence.PARTIAL_DAY
            },
            isGunsmokeWeek = true,
            metricObservedAt = observedAt ?: cell.metricObservedAt,
            solvedAttempts = attemptValue,
            solvedMeritCertainty = certainty(merit, exactMerit),
            solvedScoreCertainty = certainty(score, exactScore),
            solvedAttemptsCertainty = certainty(attemptValue, exactAttempts),
            solvedAttended = attended,
            solvedDailyPatrol = patrol,
        )
    }

    private fun certainty(value: Any?, exact: Boolean): MetricCertainty = when {
        value == null -> MetricCertainty.UNKNOWN
        exact -> MetricCertainty.EXACT
        else -> MetricCertainty.LOWER_BOUND
    }

    private fun reportDay(instant: Instant, days: List<LocalDate>, zoneId: ZoneId): LocalDate {
        val gameDay = PlatoonPeriods.gameDay(instant, zoneId)
        return if (
            gameDay > days.first() &&
            instant == PlatoonPeriods.periodStartInstant(gameDay, zoneId)
        ) {
            gameDay.minusDays(1)
        } else {
            gameDay
        }
    }

    private fun PlatoonSnapshot.member(uid: Long): SnapshotMember? = members.firstOrNull { it.uid == uid }

    private data class Metrics(val merit: Long, val score: Long)

    private data class Checkpoint(val capturedAt: Instant, val member: SnapshotMember)

    private data class ActivityState(
        val merit: Long,
        val score: Long,
        val attempts: Int,
        val scoreMerit: Long,
        val attended: Boolean,
        val patrol: Boolean,
    )

    private data class ActivityBaseline(
        val merit: Long,
        val attended: Boolean,
        val patrol: Boolean,
    )

    private data class PartialKey(
        val captured: ActivityState,
        val weeklyFinalizedMerit: Long,
    )

    private data class PartialSolution(
        val captured: ActivityState,
        val weeklyFinalizedMerit: Long,
        val finalStatesByDay: List<Set<ActivityState>>,
    ) {
        fun mergeHistory(other: PartialSolution): PartialSolution {
            require(captured == other.captured)
            require(weeklyFinalizedMerit == other.weeklyFinalizedMerit)
            require(finalStatesByDay.size == other.finalStatesByDay.size)
            return copy(
                finalStatesByDay = finalStatesByDay.zip(other.finalStatesByDay) { left, right ->
                    left + right
                },
            )
        }
    }

    private data class CompletedSolution(
        val partial: PartialSolution,
        val finalStates: List<ActivityState>,
        val lastDayFinalized: Boolean,
    )

    private class SearchBudget(private var remaining: Int) {
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining -= 1
            return true
        }
    }

    private val ACTIVITY_BASELINES = listOf(
        ActivityBaseline(merit = 0L, attended = false, patrol = false),
        ActivityBaseline(merit = LOGIN_MERIT, attended = true, patrol = false),
        ActivityBaseline(
            merit = LOGIN_MERIT + PATROL_MERIT,
            attended = true,
            patrol = true,
        ),
    )
    private val MAX_BOUNDARY_DISTANCE: Duration = Duration.ofMinutes(15)
    private const val MAX_SCORE_MERIT_VALUES = 250_000L
    private const val MAX_EXTENSION_STATES = 100_000
    private const val MAX_PARTIAL_SOLUTIONS = 2_000
    private const val MAX_SEARCH_OPERATIONS = 100_000
    private const val LOGIN_MERIT = 50L
    private const val PATROL_MERIT = 40L
}
