package dev.gf2log.app.management

import dev.gf2log.app.management.ActivityInference.saturatedAdd
import dev.gf2log.app.management.ActivityInference.saturatedMultiply
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
 * When the detailed point search would exceed its budget, the rest of the run
 * continues on attempt and Login / Daily Patrol classes with merit and score
 * ranges, which admit every point path and therefore stay conservative.
 */
internal object GunsmokeWeekSolver {
    /**
     * Solves daily Gunsmoke states and preserves whole-week totals shared by every valid path.
     * Keeps daily exactness independent from aggregate exactness when only the day placement
     * varies. Returns resolved daily cells and an optional final-week aggregate consensus.
     */
    fun resolve(
        uid: Long,
        days: List<LocalDate>,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        cells: List<WeeklyReportBuilder.DayCell>,
        dailyPatrolFacts: List<DailyPatrolFact>,
    ): Resolution {
        if (days.size != cells.size || days.isEmpty()) return Resolution(cells, null)

        val periodStart = PlatoonPeriods.periodStartInstant(days.first(), zoneId)
        val memberSnapshots = snapshots.mapNotNull { snapshot ->
            snapshot.member(uid)?.let { Checkpoint(snapshot.capturedAt, it) }
        }.sortedBy(Checkpoint::capturedAt)
        if (memberSnapshots.any { checkpoint -> !checkpoint.member.hasValidActivityCounters() }) {
            return Resolution(cells, null)
        }
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
        var resolvedTotals: WeeklyReportBuilder.ResolvedGunsmokeTotals? = null
        var index = 0
        while (index < days.size) {
            while (index < days.size && checkpoints[index] == null) index++
            if (index >= days.size) break
            val runStart = index
            while (index + 1 < days.size && checkpoints[index + 1] != null) index++
            val runEnd = index
            val run = solveRun(
                runStart = runStart,
                runEnd = runEnd,
                days = days,
                zoneId = zoneId,
                anchor = anchor,
                periodStart = periodStart,
                checkpoints = checkpoints,
                cells = resolved,
                factsByDay = factsByDay,
            )
            run.cells.forEachIndexed { cellIndex, cell -> resolved[cellIndex] = cell }
            if (run.totals != null) resolvedTotals = run.totals
            index++
        }
        if (resolvedTotals == null && resolved.last().hasFinalGunsmokeScore) {
            val aggregate = resolveFinalEventTotals(
                days = days,
                zoneId = zoneId,
                anchor = anchor,
                periodStart = periodStart,
                checkpoints = checkpoints,
                cells = resolved,
                factsByDay = factsByDay,
            )
            if (aggregate != null) {
                resolvedTotals = aggregate.totals
                aggregate.dayConsensus.forEachIndexed { dayOffset, consensus ->
                    val cellIndex = dayOffset + 1
                    val cell = resolved[cellIndex]
                    // A final event packet before the 05:00 reset that ends Saturday can miss a
                    // later Saturday login or patrol, so only a positive consensus holds there.
                    val finalized = cellIndex < resolved.lastIndex || cell.hasClosingBoundary
                    if (cell.manualOverride == null) {
                        resolved[cellIndex] = cell.copy(
                            solvedAttempts = consensus.attempts ?: cell.solvedAttempts,
                            solvedAttemptsCertainty = consensus.attempts?.let {
                                MetricCertainty.EXACT
                            } ?: cell.solvedAttemptsCertainty,
                            solvedAttended = consensus.attended?.takeIf { it || finalized }
                                ?: cell.solvedAttended,
                            solvedDailyPatrol = consensus.patrol?.takeIf { it || finalized }
                                ?: cell.solvedDailyPatrol,
                        )
                    }
                }
            }
        }
        return Resolution(
            cells = resolved,
            totals = resolvedTotals,
            attemptsFloor = weeklyAttemptsFloor(checkpoints),
        )
    }

    /**
     * Computes conservative whole-week activity totals when detailed path expansion reaches its
     * budget. Uses the Monday reset to establish Sunday and the final event counters to solve
     * six-day aggregates. Never assigns an aggregate possibility to a specific day. Returns minimum
     * or exact aggregate values compatible with all end-event arithmetic.
     */
    private fun resolveFinalEventTotals(
        days: List<LocalDate>,
        zoneId: ZoneId,
        anchor: Checkpoint?,
        periodStart: Instant,
        checkpoints: List<Checkpoint?>,
        cells: List<WeeklyReportBuilder.DayCell>,
        factsByDay: Map<LocalDate, List<DailyPatrolFact>>,
    ): FinalAggregateResolution? {
        if (days.size != 7 || checkpoints.size != days.size) return null
        val sunday = checkpoints[0] ?: return null
        val monday = checkpoints[1] ?: return null
        val final = checkpoints.last() ?: return null
        val ordered = checkpoints.filterNotNull()
        if (
            monday.member.totalMerit < sunday.member.totalMerit ||
            monday.member.totalScore < sunday.member.totalScore ||
            ordered.zipWithNext().any { (earlier, later) ->
                later.member.totalMerit < earlier.member.totalMerit ||
                    later.member.totalScore < earlier.member.totalScore
            } ||
            ordered.filter { !it.capturedAt.isBefore(monday.capturedAt) }
                .zipWithNext().any { (earlier, later) ->
                later.member.weeklyMerit < earlier.member.weeklyMerit
            }
        ) {
            return null
        }
        val meritDelta = monday.member.totalMerit - sunday.member.totalMerit
        val scoreDelta = monday.member.totalScore - sunday.member.totalScore
        val sundayStates = initialCapturedStates(
            runStart = 0,
            anchor = anchor,
            checkpoint = sunday,
            zoneId = zoneId,
            periodStart = periodStart,
            cell = cells[0],
        ).filter { state ->
            matchesCapturedEvidence(
                state = state,
                checkpoint = sunday,
                day = days[0],
                zoneId = zoneId,
                facts = factsByDay[days[0]].orEmpty(),
            )
        }
        val searchBudget = SearchBudget(MAX_AGGREGATE_SEARCH_OPERATIONS)
        val sundayFinals = sundayStates.flatMap { captured ->
            val requiredFinalMerit = captured.merit + meritDelta - monday.member.weeklyMerit
            if (requiredFinalMerit < captured.merit) return@flatMap emptyList()
            val finals = if (
                cells[0].hasClosingBoundary ||
                cells[0].hasFinalGunsmokeScore ||
                captured.attempts == ActivityInference.MAX_DAILY_ATTEMPTS
            ) {
                finalCandidates(captured, cells[0], factsByDay[days[0]].orEmpty())
            } else {
                statesExtending(
                    prefix = captured,
                    minimumMerit = requiredFinalMerit,
                    maximumMerit = requiredFinalMerit,
                    meritDelta = meritDelta,
                    scoreDelta = scoreDelta,
                    maximumAttemptScore = monday.member.highScore,
                    searchBudget = searchBudget,
                ) ?: return null
            }
            finals.filter { sundayFinal ->
                if (sundayFinal.merit != requiredFinalMerit) return@filter false
                val mondayMerit = meritDelta - (sundayFinal.merit - captured.merit)
                val mondayScore = scoreDelta - (sundayFinal.score - captured.score)
                statesForMetrics(mondayMerit, mondayScore, monday.member.highScore).any { mondayState ->
                    matchesCapturedEvidence(
                        state = mondayState,
                        checkpoint = monday,
                        day = days[1],
                        zoneId = zoneId,
                        facts = factsByDay[days[1]].orEmpty(),
                    )
                }
            }
        }.distinct()
        if (sundayFinals.isEmpty()) return null

        val constrainedSummaries = buildConstrainedActivitySummaries(cells.drop(1))
        val candidates = sundayFinals.flatMap { sundayFinal ->
            val remainingScore = final.member.totalScore - sundayFinal.score
            if (remainingScore < 0L) return@flatMap emptyList()
            constrainedSummaries.mapNotNull { (summary, marginals) ->
                val scoreMerit = final.member.weeklyMerit -
                    summary.baselineMerit -
                    summary.attempts * ActivityInference.MERIT_PER_ATTEMPT
                if (
                    !ActivityInference.isCompatibleScore(
                        scoreDelta = remainingScore,
                        scoreMerit = scoreMerit,
                        attempts = summary.attempts,
                        maximumAttemptScore = final.member.highScore,
                    )
                ) {
                    return@mapNotNull null
                }
                AggregateCandidate(
                    totals = ActivityTotals(
                        merit = sundayFinal.merit + final.member.weeklyMerit,
                        attempts = sundayFinal.attempts + summary.attempts,
                        loginDays = (if (sundayFinal.attended) 1 else 0) + summary.loginDays,
                        patrolDays = (if (sundayFinal.patrol) 1 else 0) + summary.patrolDays,
                    ),
                    dayOptions = marginals,
                )
            }
        }.distinct()
        if (candidates.isEmpty()) return null
        val totals = candidates.map(AggregateCandidate::totals)
        val minimumMerit = totals.minOf(ActivityTotals::merit)
        val maximumMerit = totals.maxOf(ActivityTotals::merit)
        val minimumAttempts = totals.minOf(ActivityTotals::attempts)
        val maximumAttempts = totals.maxOf(ActivityTotals::attempts)
        val minimumLoginDays = totals.minOf(ActivityTotals::loginDays)
        val maximumLoginDays = totals.maxOf(ActivityTotals::loginDays)
        val minimumPatrolDays = totals.minOf(ActivityTotals::patrolDays)
        val maximumPatrolDays = totals.maxOf(ActivityTotals::patrolDays)
        val dayConsensus = List(6) { dayIndex ->
            val options = candidates.flatMap { candidate -> candidate.dayOptions[dayIndex] }
            DayConsensus(
                attempts = options.map(ActivityOption::attempts).distinct().singleOrNull(),
                attended = options.map(ActivityOption::attended).distinct().singleOrNull(),
                patrol = options.map(ActivityOption::patrol).distinct().singleOrNull(),
            )
        }
        return FinalAggregateResolution(
            totals = WeeklyReportBuilder.ResolvedGunsmokeTotals(
                merit = minimumMerit,
                meritCertainty = exactOrLowerBound(
                    minimumMerit,
                    maximumMerit,
                    finalDayCanStillGainBaseline = minimumLoginDays < 7 || minimumPatrolDays < 7,
                ),
                attempts = minimumAttempts,
                attemptsCertainty = exactOrLowerBound(minimumAttempts, maximumAttempts),
                loginDays = minimumLoginDays,
                loginDaysCertainty = exactOrLowerBound(
                    minimumLoginDays,
                    maximumLoginDays,
                    finalDayCanStillGainBaseline = minimumLoginDays < 7,
                ),
                patrolDays = minimumPatrolDays,
                patrolDaysCertainty = exactOrLowerBound(
                    minimumPatrolDays,
                    maximumPatrolDays,
                    finalDayCanStillGainBaseline = minimumPatrolDays < 7,
                ),
            ),
            dayConsensus = dayConsensus,
        )
    }

    private fun exactOrLowerBound(
        minimum: Number,
        maximum: Number,
        finalDayCanStillGainBaseline: Boolean = false,
    ): MetricCertainty = if (
        minimum.toLong() == maximum.toLong() && !finalDayCanStillGainBaseline
    ) {
        MetricCertainty.EXACT
    } else {
        MetricCertainty.LOWER_BOUND
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
    ): RunResolution {
        val firstCheckpoint = requireNotNull(checkpoints[runStart])
        val initialStates = initialCapturedStates(
            runStart = runStart,
            anchor = anchor,
            checkpoint = firstCheckpoint,
            zoneId = zoneId,
            periodStart = periodStart,
            cell = cells[runStart],
        )

        var partials = initialStates.asSequence()
            .filter { state ->
                matchesCapturedEvidence(
                    state = state,
                    checkpoint = firstCheckpoint,
                    day = days[runStart],
                    zoneId = zoneId,
                    facts = factsByDay[days[runStart]].orEmpty(),
                )
            }
            .mapNotNull { state ->
                val weeklyBase = firstCheckpoint.member.weeklyMerit - state.merit
                if (weeklyBase < 0L || (runStart == 1 && weeklyBase != 0L)) {
                    return@mapNotNull null
                }
                PartialSolution(
                    captured = state,
                    weeklyBase = weeklyBase,
                    weeklyFinalizedMerit = 0L,
                    activityTotals = ActivityTotalBounds.ZERO,
                    finalStatesByDay = emptyList(),
                )
            }
            .toList()
        if (partials.isEmpty()) return RunResolution(cells, null)
        var bestEffortCells = cells.toMutableList().apply {
            this[runStart] = project(
                cell = cells[runStart],
                states = partials.map(PartialSolution::captured).distinct(),
                finalized = false,
                observedAt = firstCheckpoint.capturedAt,
            )
        }
        val searchBudget = SearchBudget(MAX_SEARCH_OPERATIONS)

        for (absoluteIndex in runStart + 1..runEnd) {
            val currentCheckpoint = requireNotNull(checkpoints[absoluteIndex])
            val previousCheckpoint = requireNotNull(checkpoints[absoluteIndex - 1])
            if (
                currentCheckpoint.member.totalMerit < previousCheckpoint.member.totalMerit ||
                currentCheckpoint.member.totalScore < previousCheckpoint.member.totalScore
            ) {
                return RunResolution(bestEffortCells, null)
            }
            val meritDelta = currentCheckpoint.member.totalMerit - previousCheckpoint.member.totalMerit
            val scoreDelta = currentCheckpoint.member.totalScore - previousCheckpoint.member.totalScore
            val currentFacts = factsByDay[days[absoluteIndex]].orEmpty()
            val previousFacts = factsByDay[days[absoluteIndex - 1]].orEmpty()
            val extensionCache = mutableMapOf<ActivityState, List<ActivityState>>()
            val capturedCache = mutableMapOf<Metrics, List<ActivityState>>()
            val nextPartials = linkedMapOf<PartialKey, PartialSolution>()
            val resetCrossed = runStart == 0 && absoluteIndex == 1
            val previousCell = cells[absoluteIndex - 1]
            val highScore = currentCheckpoint.member.highScore
            val tailMeritWindows = mutableMapOf<Pair<ActivityOption, ActivityOption>, Bounds?>()
            fun hasFixedTail(captured: ActivityState) = previousCell.hasClosingBoundary ||
                previousCell.hasFinalGunsmokeScore ||
                captured.attempts == ActivityInference.MAX_DAILY_ATTEMPTS
            fun extensionMerits(captured: ActivityState): Pair<Long, Long> {
                val required = if (resetCrossed) {
                    captured.merit + meritDelta - currentCheckpoint.member.weeklyMerit
                } else {
                    null
                }
                return (required ?: captured.merit) to minOf(
                    required ?: (captured.merit + meritDelta),
                    maximumDailyMerit(highScore),
                )
            }
            fun intervalBounds() = resolveIntervalBounds(
                runStart = runStart,
                firstIndex = absoluteIndex - 1,
                runEnd = runEnd,
                partials = partials,
                days = days,
                zoneId = zoneId,
                checkpoints = checkpoints,
                cells = cells,
                factsByDay = factsByDay,
                bestEffortCells = bestEffortCells,
            )

            // Every open-tail score-merit value costs at least one search step. A transition
            // that cannot fit the remaining budget goes straight to the interval bounds instead
            // of spending the budget first.
            val extensionWork = partials.asSequence()
                .map(PartialSolution::captured)
                .distinct()
                .filterNot(::hasFixedTail)
                .sumOf { captured ->
                    val (minimumMerit, maximumMerit) = extensionMerits(captured)
                    extensionRanges(
                        prefix = captured,
                        minimumMerit = minimumMerit,
                        maximumMerit = maximumMerit,
                        meritDelta = meritDelta,
                        scoreDelta = scoreDelta,
                        maximumAttemptScore = highScore,
                        tailMeritWindows = tailMeritWindows,
                    ).sumOf(ExtensionRange::span)
                }
            if (extensionWork > searchBudget.remaining) return intervalBounds()

            partials.forEach { partial ->
                val previousCaptured = partial.captured
                val extensionStates = extensionCache.getOrPut(previousCaptured) {
                    if (hasFixedTail(previousCaptured)) {
                        finalCandidates(
                            captured = previousCaptured,
                            cell = previousCell,
                            facts = previousFacts,
                        )
                    } else {
                        val (minimumMerit, maximumMerit) = extensionMerits(previousCaptured)
                        statesExtending(
                            prefix = previousCaptured,
                            minimumMerit = minimumMerit,
                            maximumMerit = maximumMerit,
                            meritDelta = meritDelta,
                            scoreDelta = scoreDelta,
                            maximumAttemptScore = highScore,
                            searchBudget = searchBudget,
                            tailMeritWindows = tailMeritWindows,
                        ) ?: return intervalBounds()
                    }
                }
                extensionStates.asSequence()
                        .filter { final ->
                            matchesFinalEvidence(
                                state = final,
                                cell = cells[absoluteIndex - 1],
                                facts = previousFacts,
                            ) && matchesMaximumAttemptScore(
                                state = final,
                                maximumAttemptScore = currentCheckpoint.member.highScore,
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
                                partial.weeklyBase
                            }
                            val expectedWeeklyMerit =
                                activeWeeklyBase + weeklyFinalizedMerit + currentMerit
                            if (expectedWeeklyMerit != currentCheckpoint.member.weeklyMerit) {
                                return@forEach
                            }
                            capturedCache.getOrPut(Metrics(currentMerit, currentScore)) {
                                statesForMetrics(
                                    currentMerit,
                                    currentScore,
                                    currentCheckpoint.member.highScore,
                                )
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
                                    val key = PartialKey(
                                        captured = current,
                                        weeklyBase = activeWeeklyBase,
                                        weeklyFinalizedMerit = weeklyFinalizedMerit,
                                    )
                                    val candidate = PartialSolution(
                                        captured = current,
                                        weeklyBase = activeWeeklyBase,
                                        weeklyFinalizedMerit = weeklyFinalizedMerit,
                                        activityTotals = partial.activityTotals + previousFinal,
                                        finalStatesByDay = partial.finalStatesByDay +
                                            listOf(setOf(previousFinal)),
                                    )
                                    nextPartials[key] = nextPartials[key]
                                        ?.mergeHistory(candidate)
                                        ?: candidate
                                    if (nextPartials.size > MAX_PARTIAL_SOLUTIONS) {
                                        return intervalBounds()
                                    }
                                }
                        }
            }
            partials = nextPartials.values.toList()
            if (partials.isEmpty()) return RunResolution(bestEffortCells, null)
            bestEffortCells = projectPartialPrefix(
                runStart = runStart,
                currentIndex = absoluteIndex,
                cells = bestEffortCells,
                checkpoints = checkpoints,
                partials = partials,
            )
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
        if (completed.isEmpty()) return RunResolution(bestEffortCells, null)

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
        val totals = if (
            runStart == 0 &&
            runEnd == cells.lastIndex &&
            cells[runEnd].hasFinalGunsmokeScore
        ) {
            summarizeTotals(completed)
        } else {
            null
        }
        return RunResolution(resolved, totals)
    }

    /** Retains only consensus from transitions whose complete state space was enumerated. */
    private fun projectPartialPrefix(
        runStart: Int,
        currentIndex: Int,
        cells: List<WeeklyReportBuilder.DayCell>,
        checkpoints: List<Checkpoint?>,
        partials: List<PartialSolution>,
    ): MutableList<WeeklyReportBuilder.DayCell> {
        val projected = cells.toMutableList()
        for (absoluteIndex in runStart until currentIndex) {
            val localIndex = absoluteIndex - runStart
            val states = partials.flatMap { partial ->
                partial.finalStatesByDay[localIndex]
            }.distinct()
            projected[absoluteIndex] = project(
                cell = cells[absoluteIndex],
                states = states,
                finalized = true,
                observedAt = checkpoints[absoluteIndex]?.capturedAt,
            )
        }
        projected[currentIndex] = project(
            cell = cells[currentIndex],
            states = partials.map(PartialSolution::captured).distinct(),
            finalized = false,
            observedAt = checkpoints[currentIndex]?.capturedAt,
        )
        return projected
    }

    /**
     * Continues a run whose detailed search would exceed its budget at the transition after
     * [firstIndex]. Keeps the enumerated captured states of that checkpoint, then tracks every
     * later capture only by its attempts, Login, and Daily Patrol, with merit and score ranges.
     * Within such a class pair an interval splits between the previous day's tail and the next
     * prefix in closed form, so every split the detailed search accepts is still admitted: the
     * published lower bounds and shared states stay defensible, only less precise. Earlier days
     * keep their detailed consensus over captured states that still have a complete path. Returns
     * the run's cells; whole-week totals are left to the final-event aggregate.
     */
    private fun resolveIntervalBounds(
        runStart: Int,
        firstIndex: Int,
        runEnd: Int,
        partials: List<PartialSolution>,
        days: List<LocalDate>,
        zoneId: ZoneId,
        checkpoints: List<Checkpoint?>,
        cells: List<WeeklyReportBuilder.DayCell>,
        factsByDay: Map<LocalDate, List<DailyPatrolFact>>,
        bestEffortCells: List<WeeklyReportBuilder.DayCell>,
    ): RunResolution {
        val layers = mutableListOf(
            partials.groupBy { it.captured.activityOption }
                .mapValues { (_, group) -> IntervalRange.of(group.map(PartialSolution::captured)) },
        )
        val transitions = mutableListOf<List<IntervalEdge>>()
        var lastIndex = firstIndex
        while (lastIndex < runEnd) {
            val edges = intervalEdges(
                from = layers.last(),
                runStart = runStart,
                absoluteIndex = lastIndex + 1,
                days = days,
                zoneId = zoneId,
                checkpoints = checkpoints,
                cells = cells,
                factsByDay = factsByDay,
            ) ?: break
            if (edges.isEmpty()) return RunResolution(bestEffortCells, null)
            transitions += edges
            layers += edges.groupBy(IntervalEdge::to).mapValues { (_, incoming) ->
                incoming.map(IntervalEdge::next).reduce(IntervalRange::hull)
            }
            lastIndex++
        }
        // Decreasing counters stop the run early, as in the detailed search, and leave the last
        // reachable capture an open prefix.
        val truncated = lastIndex < runEnd
        val lastCell = cells[lastIndex]
        val lastFacts = factsByDay[days[lastIndex]].orEmpty()
        val gunsmokeClosed = { option: ActivityOption ->
            lastCell.hasClosingBoundary ||
                lastCell.hasFinalGunsmokeScore ||
                option.attempts == ActivityInference.MAX_DAILY_ATTEMPTS
        }
        val finals = layers.last().mapValues { (option, range) ->
            when {
                truncated -> listOf(option to range)
                lastCell.hasClosingBoundary || !gunsmokeClosed(option) -> listOf(option to range)
                else -> baselineCompletions(option).map { final ->
                    val extra = final.baselineMerit - option.baselineMerit
                    final to range.copy(merit = range.merit + Bounds.of(extra))
                }
            }.filter { (final, finalRange) ->
                truncated ||
                    matchesFinalEvidence(final, finalRange.merit, finalRange.score, lastCell, lastFacts)
            }
        }

        val alive = MutableList(layers.size) { mutableSetOf<ActivityOption>() }
        alive[layers.lastIndex] += finals.filterValues { it.isNotEmpty() }.keys
        val liveTransitions = MutableList(transitions.size) { emptyList<IntervalEdge>() }
        for (layer in transitions.indices.reversed()) {
            liveTransitions[layer] = transitions[layer].filter { it.to in alive[layer + 1] }
            alive[layer] += liveTransitions[layer].map(IntervalEdge::from)
        }
        if (alive.first().isEmpty()) return RunResolution(bestEffortCells, null)

        val projected = projectPartialPrefix(
            runStart = runStart,
            currentIndex = firstIndex,
            cells = cells,
            checkpoints = checkpoints,
            partials = partials.filter { it.captured.activityOption in alive.first() },
        )
        liveTransitions.forEachIndexed { layer, edges ->
            val absoluteIndex = firstIndex + layer
            projected[absoluteIndex] = project(
                cell = cells[absoluteIndex],
                summary = edges.map { edge ->
                    val prefix = layers[layer].getValue(edge.from)
                    StateSummary.of(
                        option = edge.final,
                        merit = prefix.merit + edge.tail.merit,
                        score = prefix.score + edge.tail.score,
                    )
                }.reduce(StateSummary::merge),
                finalized = true,
                observedAt = checkpoints[absoluteIndex]?.capturedAt,
            )
        }
        projected[lastIndex] = project(
            cell = lastCell,
            summary = alive.last().flatMap { option -> finals.getValue(option) }
                .map { (final, range) -> StateSummary.of(final, range.merit, range.score) }
                .reduce(StateSummary::merge),
            finalized = !truncated && alive.last().all(gunsmokeClosed),
            observedAt = checkpoints[lastIndex]?.capturedAt,
        )
        return RunResolution(projected, null)
    }

    /**
     * Splits the counter interval ending at checkpoint [absoluteIndex] between each previous
     * prefix class's tail and each next prefix class. The weekly counter fixes the tail only
     * across the Monday reset; elsewhere it must move with the total counter. Returns the
     * admissible edges, or null when the counters decrease.
     */
    private fun intervalEdges(
        from: Map<ActivityOption, IntervalRange>,
        runStart: Int,
        absoluteIndex: Int,
        days: List<LocalDate>,
        zoneId: ZoneId,
        checkpoints: List<Checkpoint?>,
        cells: List<WeeklyReportBuilder.DayCell>,
        factsByDay: Map<LocalDate, List<DailyPatrolFact>>,
    ): List<IntervalEdge>? {
        val current = requireNotNull(checkpoints[absoluteIndex])
        val previous = requireNotNull(checkpoints[absoluteIndex - 1])
        val meritDelta = current.member.totalMerit - previous.member.totalMerit
        val scoreDelta = current.member.totalScore - previous.member.totalScore
        if (meritDelta < 0L || scoreDelta < 0L) return null
        val requiredTailMerit = if (runStart == 0 && absoluteIndex == 1) {
            (meritDelta - current.member.weeklyMerit).takeIf { it >= 0L } ?: return emptyList()
        } else {
            if (current.member.weeklyMerit - previous.member.weeklyMerit != meritDelta) return emptyList()
            null
        }
        val previousCell = cells[absoluteIndex - 1]
        val previousFacts = factsByDay[days[absoluteIndex - 1]].orEmpty()
        val currentFacts = factsByDay[days[absoluteIndex]].orEmpty()
        // A cell measured from an exact opening boundary fixes the new prefix, and with it the tail.
        val currentCell = cells[absoluteIndex]
        val direct = currentCell.meritDelta?.takeIf { currentCell.manualOverride == null }?.let { merit ->
            currentCell.scoreDelta?.let { score -> Metrics(merit, score) }
        }
        val directOptions = direct?.let {
            statesForMetrics(it.merit, it.score, current.member.highScore).map { state -> state.activityOption }
        }
        val nextOptions = ALL_ACTIVITY_OPTIONS.filter { option ->
            (directOptions == null || option in directOptions) &&
                matchesCapturedEvidence(option, current, days[absoluteIndex], zoneId, currentFacts)
        }
        return buildList {
            from.forEach { (prefix, prefixRange) ->
                tailFinals(prefix, previousCell).forEach { final ->
                    nextOptions.forEach { next ->
                        var tail = splitInterval(
                            prefix = prefix,
                            final = final,
                            next = next,
                            meritDelta = meritDelta,
                            scoreDelta = scoreDelta,
                            maximumAttemptScore = current.member.highScore,
                            requiredTailMerit = requiredTailMerit,
                        ) ?: return@forEach
                        if (direct != null) {
                            val exact = IntervalRange(
                                merit = Bounds.of(meritDelta - direct.merit),
                                score = Bounds.of(scoreDelta - direct.score),
                            )
                            if (exact.merit.minimum !in tail.merit || exact.score.minimum !in tail.score) {
                                return@forEach
                            }
                            tail = exact
                        }
                        if (
                            matchesFinalEvidence(
                                option = final,
                                merit = prefixRange.merit + tail.merit,
                                score = prefixRange.score + tail.score,
                                cell = previousCell,
                                facts = previousFacts,
                            )
                        ) {
                            add(
                                IntervalEdge(
                                    from = prefix,
                                    final = final,
                                    to = next,
                                    tail = tail,
                                    next = IntervalRange(
                                        merit = tail.merit.subtractedFrom(meritDelta),
                                        score = tail.score.subtractedFrom(scoreDelta),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Final classes a captured prefix class can still reach before its day closes. */
    private fun tailFinals(
        prefix: ActivityOption,
        cell: WeeklyReportBuilder.DayCell,
    ): List<ActivityOption> {
        if (cell.hasClosingBoundary) return listOf(prefix)
        val gunsmokeClosed = cell.hasFinalGunsmokeScore ||
            prefix.attempts == ActivityInference.MAX_DAILY_ATTEMPTS
        val maximumAttempts = if (gunsmokeClosed) prefix.attempts else ActivityInference.MAX_DAILY_ATTEMPTS
        return baselineCompletions(prefix).flatMap { completed ->
            (prefix.attempts..maximumAttempts).mapNotNull { attempts ->
                completed.copy(attempts = attempts).takeIf { attempts == 0 || it.attended }
            }
        }
    }

    /** The captured class with every Login and Daily Patrol completion it can still gain. */
    private fun baselineCompletions(option: ActivityOption): List<ActivityOption> =
        ACTIVITY_BASELINES.filter { baseline ->
            (baseline.attended || !option.attended) && (baseline.patrol || !option.patrol)
        }.map { baseline -> option.copy(attended = baseline.attended, patrol = baseline.patrol) }

    /**
     * Splits one interval between a previous-day tail ([prefix] to [final]) and the [next] prefix.
     * Both pieces share the interval's score merit and score; each attempt adds at most nine
     * rounding points and at most the captured high score, which bounds how much the tail can
     * take. Returns the tail's merit and score ranges, or null when no split fits.
     */
    private fun splitInterval(
        prefix: ActivityOption,
        final: ActivityOption,
        next: ActivityOption,
        meritDelta: Long,
        scoreDelta: Long,
        maximumAttemptScore: Long,
        requiredTailMerit: Long?,
    ): IntervalRange? {
        val tailAttempts = final.attempts - prefix.attempts
        val tailFixedMerit = final.baselineMerit - prefix.baselineMerit +
            tailAttempts * ActivityInference.MERIT_PER_ATTEMPT
        val sharedScoreMerit = meritDelta - tailFixedMerit -
            next.baselineMerit - next.attempts * ActivityInference.MERIT_PER_ATTEMPT
        if (sharedScoreMerit < 0L) return null
        if (sharedScoreMerit > Long.MAX_VALUE / ActivityInference.SCORE_POINTS_PER_MERIT) return null
        val remainder = scoreDelta - sharedScoreMerit * ActivityInference.SCORE_POINTS_PER_MERIT
        if (remainder < 0L || remainder > MAX_ROUNDING_REMAINDER * (tailAttempts + next.attempts)) return null
        if (
            tailAttempts == 0 &&
            !ActivityInference.isCompatibleScore(scoreDelta, sharedScoreMerit, next.attempts, maximumAttemptScore)
        ) {
            return null
        }
        if (
            next.attempts == 0 &&
            !ActivityInference.isCompatibleScore(scoreDelta, sharedScoreMerit, tailAttempts, maximumAttemptScore)
        ) {
            return null
        }
        val attemptScoreMerit = if (maximumAttemptScore > 0L) {
            maximumAttemptScore / ActivityInference.SCORE_POINTS_PER_MERIT
        } else {
            Long.MAX_VALUE
        }
        var lowest = maxOf(0L, sharedScoreMerit - saturatedMultiply(attemptScoreMerit, next.attempts))
        var highest = minOf(sharedScoreMerit, saturatedMultiply(attemptScoreMerit, tailAttempts))
        if (requiredTailMerit != null) {
            val required = requiredTailMerit - tailFixedMerit
            if (required !in lowest..highest) return null
            lowest = required
            highest = required
        }
        // An attempt at the high score's quotient keeps at most its remainder, so the shared
        // remainder can fail to fit near either end of the score-merit range.
        fun remaindersFit(tailScoreMerit: Long) = remainder <=
            maximumRoundingRemainder(tailScoreMerit, tailAttempts, maximumAttemptScore) +
            maximumRoundingRemainder(sharedScoreMerit - tailScoreMerit, next.attempts, maximumAttemptScore)
        var steps = 0
        while (lowest <= highest && !remaindersFit(highest) && steps++ < MAX_REMAINDER_STEPS) highest--
        steps = 0
        while (lowest <= highest && !remaindersFit(lowest) && steps++ < MAX_REMAINDER_STEPS) lowest++
        if (lowest > highest) return null
        var lowestScore = lowest * ActivityInference.SCORE_POINTS_PER_MERIT + maxOf(
            0L,
            remainder - maximumRoundingRemainder(sharedScoreMerit - lowest, next.attempts, maximumAttemptScore),
        )
        var highestScore = highest * ActivityInference.SCORE_POINTS_PER_MERIT + minOf(
            maximumRoundingRemainder(highest, tailAttempts, maximumAttemptScore),
            remainder,
        )
        if (maximumAttemptScore > 0L) {
            lowestScore = maxOf(lowestScore, scoreDelta - saturatedMultiply(maximumAttemptScore, next.attempts))
            highestScore = minOf(highestScore, saturatedMultiply(maximumAttemptScore, tailAttempts))
        }
        if (maxOf(0L, lowestScore) > minOf(scoreDelta, highestScore)) return null
        return IntervalRange(
            merit = Bounds(tailFixedMerit + lowest, tailFixedMerit + highest),
            score = Bounds(maxOf(0L, lowestScore), minOf(scoreDelta, highestScore)),
        )
    }

    /**
     * The largest total below-ten score remainder [attempts] can carry while their rounded score
     * merits sum to [scoreMerit]. Attempts forced up to the high score's quotient keep at most
     * the high score's own remainder.
     */
    private fun maximumRoundingRemainder(
        scoreMerit: Long,
        attempts: Int,
        maximumAttemptScore: Long,
    ): Long {
        if (attempts == 0) return 0L
        if (maximumAttemptScore <= 0L) return MAX_ROUNDING_REMAINDER * attempts
        val quotient = maximumAttemptScore / ActivityInference.SCORE_POINTS_PER_MERIT
        val capped = (scoreMerit - attempts * (quotient - 1L)).coerceIn(0L, attempts.toLong())
        return MAX_ROUNDING_REMAINDER * (attempts - capped) +
            maximumAttemptScore % ActivityInference.SCORE_POINTS_PER_MERIT * capped
    }

    /** Derives a monotonic weekly-attempt floor from the cumulative event score. */
    private fun weeklyAttemptsFloor(checkpoints: List<Checkpoint?>): Int? {
        if (checkpoints.firstOrNull() == null) return null
        val latest = checkpoints.lastOrNull { it != null }?.member ?: return null
        if (latest.totalScore <= 0L) return null
        val minimum = if (latest.highScore > 0L) {
            val quotient = latest.totalScore / latest.highScore
            val rounded = quotient + if (latest.totalScore % latest.highScore == 0L) 0L else 1L
            rounded
        } else {
            1L
        }
        return minimum.toInt().takeIf { minimum in 1L..ActivityInference.MAX_WEEKLY_ATTEMPTS.toLong() }
    }

    /**
     * Establishes every valid state at the first capture in a contiguous run. Uses a direct
     * boundary when available, otherwise derives Sunday from the reset score counter. Preserves all
     * attendance, patrol, and score-rounding possibilities until later counters prune them. Returns
     * every compatible captured activity state.
     */
    private fun initialCapturedStates(
        runStart: Int,
        anchor: Checkpoint?,
        checkpoint: Checkpoint,
        zoneId: ZoneId,
        periodStart: Instant,
        cell: WeeklyReportBuilder.DayCell,
    ): List<ActivityState> {
        val direct = cell.meritDelta?.let { merit ->
            cell.scoreDelta?.let { score -> Metrics(merit, score) }
        }
        if (direct != null) {
            return statesForMetrics(direct.merit, direct.score, checkpoint.member.highScore)
        }
        if (runStart != 0) return emptyList()
        if (
            anchor != null &&
            !anchor.capturedAt.isAfter(periodStart) &&
            isClosedOpeningAnchor(
                capturedAt = anchor.capturedAt,
                weeklyMerit = anchor.member.weeklyMerit,
                zoneId = zoneId,
                periodStart = periodStart,
            ) &&
            checkpoint.member.totalMerit >= anchor.member.totalMerit
        ) {
            val merit = checkpoint.member.totalMerit - anchor.member.totalMerit
            if (anchor.member.weeklyMerit + merit != checkpoint.member.weeklyMerit) {
                return emptyList()
            }
            return statesForMetrics(merit, checkpoint.member.totalScore, checkpoint.member.highScore)
        }
        return ActivityInference.candidatesForScore(
            scoreDelta = checkpoint.member.totalScore,
            maximumAttemptScore = checkpoint.member.highScore.takeIf { it > 0L },
        ).map { candidate ->
            ActivityState(
                merit = candidate.merit,
                score = checkpoint.member.totalScore,
                attempts = candidate.attempts,
                scoreMerit = candidate.scoreMerit,
                attended = candidate.attended,
                patrol = candidate.dailyPatrol,
            )
        }.distinct()
    }

    /**
     * Accepts a near-reset capture or a preceding-week counter that has already reached its cap. A
     * capped Monday-through-Saturday counter cannot gain more merit before the Gunsmoke Sunday.
     * Returns true when no unobserved pre-boundary merit can contaminate the opening interval.
     */
    internal fun isClosedOpeningAnchor(
        capturedAt: Instant,
        weeklyMerit: Long,
        zoneId: ZoneId,
        periodStart: Instant,
    ): Boolean {
        val precedingCounterStart = periodStart.atZone(zoneId).minusDays(6).toInstant()
        return !capturedAt.isAfter(periodStart) &&
            (
                Duration.between(capturedAt, periodStart) <= MAX_BOUNDARY_DISTANCE ||
                    (
                        weeklyMerit == MAX_PRE_GUNSMOKE_WEEKLY_MERIT &&
                            !capturedAt.isBefore(precedingCounterStart)
                        )
                    )
    }

    private fun statesForMetrics(
        merit: Long,
        score: Long,
        maximumAttemptScore: Long? = null,
    ): List<ActivityState> {
        if (merit < 0L || score < 0L) return emptyList()
        return ActivityInference.candidatesForScore(score, maximumAttemptScore)
            .filter { candidate -> candidate.merit == merit }
            .map { candidate ->
            ActivityState(
                merit = candidate.merit,
                score = score,
                attempts = candidate.attempts,
                scoreMerit = candidate.scoreMerit,
                attended = candidate.attended,
                patrol = candidate.dailyPatrol,
            )
        }.distinct()
    }

    /** Builds the whole-week minimum or exact value independently for each activity metric. */
    private fun summarizeTotals(
        completed: List<CompletedSolution>,
    ): WeeklyReportBuilder.ResolvedGunsmokeTotals? {
        val totals = completed.asSequence().flatMap { solution ->
            solution.finalStates.asSequence().map { final ->
                solution.partial.activityTotals + final
            }
        }.reduceOrNull(ActivityTotalBounds::merge) ?: return null
        return WeeklyReportBuilder.ResolvedGunsmokeTotals(
            merit = totals.minimumMerit,
            meritCertainty = totals.meritCertainty,
            attempts = totals.minimumAttempts,
            attemptsCertainty = totals.attemptsCertainty,
            loginDays = totals.minimumLoginDays,
            loginDaysCertainty = totals.loginDaysCertainty,
            patrolDays = totals.minimumPatrolDays,
            patrolDaysCertainty = totals.patrolDaysCertainty,
        )
    }

    /**
     * Enumerates previous-day final states through their complementary current-day states. Avoids
     * scanning every score up to the raw counter delta when only 28 remainders can exist. Refuses
     * pathological counter ranges so malformed data degrades to conservative cells. Returns valid
     * extensions, or null when the deterministic search budget would be exceeded.
     */
    private fun statesExtending(
        prefix: ActivityState,
        minimumMerit: Long,
        maximumMerit: Long,
        meritDelta: Long,
        scoreDelta: Long,
        maximumAttemptScore: Long,
        searchBudget: SearchBudget,
        tailMeritWindows: MutableMap<Pair<ActivityOption, ActivityOption>, Bounds?> = mutableMapOf(),
    ): List<ActivityState>? {
        val effectiveMinimumMerit = maxOf(prefix.merit, minimumMerit)
        if (maximumMerit < effectiveMinimumMerit || meritDelta < 0L || scoreDelta < 0L) {
            return emptyList()
        }
        val states = linkedSetOf<ActivityState>()
        for (baseline in ACTIVITY_BASELINES) {
            if (prefix.attempts > 0 && !baseline.attended) continue
            if (prefix.attended && !baseline.attended) continue
            if (prefix.patrol && !baseline.patrol) continue
            val merit = baseline.merit +
                prefix.attempts * ActivityInference.MERIT_PER_ATTEMPT +
                prefix.scoreMerit
            if (merit !in effectiveMinimumMerit..maximumMerit) continue
            val state = ActivityState(
                merit = merit,
                score = prefix.score,
                attempts = prefix.attempts,
                scoreMerit = prefix.scoreMerit,
                attended = baseline.attended,
                patrol = baseline.patrol,
            )
            val currentMerit = meritDelta - (state.merit - prefix.merit)
            val currentScore = scoreDelta - (state.score - prefix.score)
            if (
                extends(prefix, state, maximumAttemptScore) &&
                statesForMetrics(currentMerit, currentScore, maximumAttemptScore).isNotEmpty()
            ) {
                states += state
            }
        }
        val ranges = extensionRanges(
            prefix = prefix,
            minimumMerit = minimumMerit,
            maximumMerit = maximumMerit,
            meritDelta = meritDelta,
            scoreDelta = scoreDelta,
            maximumAttemptScore = maximumAttemptScore,
            tailMeritWindows = tailMeritWindows,
        )
        if (ranges.sumOf(ExtensionRange::span) > MAX_SCORE_MERIT_VALUES) return null
        ranges.forEach { range ->
            for (scoreMerit in range.minimumScoreMerit..range.maximumScoreMerit) {
                if (!searchBudget.consume()) return null
                val merit = range.fixedMerit + scoreMerit
                val currentMerit = meritDelta - (merit - prefix.merit)
                if (currentMerit < 0L) continue
                statesForMerit(currentMerit, maximumAttemptScore).forEach { current ->
                    if (!searchBudget.consume()) return null
                    val score = prefix.score + scoreDelta - current.score
                    val state = ActivityState(
                        merit = merit,
                        score = score,
                        attempts = range.attempts,
                        scoreMerit = scoreMerit,
                        attended = true,
                        patrol = range.baseline.patrol,
                    )
                    if (extends(prefix, state, maximumAttemptScore)) states += state
                    if (states.size > MAX_EXTENSION_STATES) return null
                }
            }
        }
        return states.toList()
    }

    /**
     * Lists the score-merit values a previous-day tail with additional attempts can add, per final
     * attempt count and baseline. The tail and the next prefix share the interval's score merit
     * and rounding remainders, so only the tail merits some next prefix class can complement are
     * listed; every skipped value would fail the detailed checks anyway. Shared by the detailed
     * search and its budget estimate.
     */
    private fun extensionRanges(
        prefix: ActivityState,
        minimumMerit: Long,
        maximumMerit: Long,
        meritDelta: Long,
        scoreDelta: Long,
        maximumAttemptScore: Long,
        tailMeritWindows: MutableMap<Pair<ActivityOption, ActivityOption>, Bounds?> = mutableMapOf(),
    ): List<ExtensionRange> {
        val effectiveMinimumMerit = maxOf(prefix.merit, minimumMerit)
        if (maximumMerit < effectiveMinimumMerit) return emptyList()
        return buildList {
            for (attempts in prefix.attempts + 1..ActivityInference.MAX_DAILY_ATTEMPTS) {
                for (baseline in ACTIVITY_BASELINES) {
                    if (!baseline.attended) continue
                    if (prefix.patrol && !baseline.patrol) continue
                    val final = ActivityOption(attempts, baseline.attended, baseline.patrol)
                    // The window depends only on the classes, so one transition shares it.
                    val windowKey = prefix.activityOption to final
                    val tailMerits = if (windowKey in tailMeritWindows) {
                        tailMeritWindows[windowKey]
                    } else {
                        ALL_ACTIVITY_OPTIONS.mapNotNull { next ->
                            splitInterval(
                                prefix = prefix.activityOption,
                                final = final,
                                next = next,
                                meritDelta = meritDelta,
                                scoreDelta = scoreDelta,
                                maximumAttemptScore = maximumAttemptScore,
                                requiredTailMerit = null,
                            )?.merit
                        }.reduceOrNull(Bounds::hull).also { tailMeritWindows[windowKey] = it }
                    } ?: continue
                    val fixedMerit = baseline.merit +
                        attempts * ActivityInference.MERIT_PER_ATTEMPT
                    val minimumScoreMerit = maxOf(
                        0L,
                        prefix.scoreMerit,
                        effectiveMinimumMerit - fixedMerit,
                        prefix.merit + tailMerits.minimum - fixedMerit,
                    )
                    val maximumScoreMerit = minOf(
                        if (maximumAttemptScore > 0L) {
                            saturatedMultiply(
                                maximumAttemptScore / ActivityInference.SCORE_POINTS_PER_MERIT,
                                attempts,
                            )
                        } else {
                            maximumMerit - fixedMerit
                        },
                        maximumMerit - fixedMerit,
                        prefix.merit + tailMerits.maximum - fixedMerit,
                    )
                    if (maximumScoreMerit >= minimumScoreMerit) {
                        add(ExtensionRange(attempts, baseline, fixedMerit, minimumScoreMerit, maximumScoreMerit))
                    }
                }
            }
        }
    }

    /** Enumerates the narrow score range compatible with a fixed merit state. */
    private fun statesForMerit(
        merit: Long,
        maximumAttemptScore: Long,
    ): List<ActivityState> {
        if (merit < 0L) return emptyList()
        return buildList {
            for (attempts in 0..ActivityInference.MAX_DAILY_ATTEMPTS) {
                for (baseline in ACTIVITY_BASELINES) {
                    if (attempts > 0 && !baseline.attended) continue
                    val scoreMerit = merit - baseline.merit -
                        attempts * ActivityInference.MERIT_PER_ATTEMPT
                    if (scoreMerit < 0L) continue
                    if (attempts == 0) {
                        if (scoreMerit == 0L) {
                            add(
                                ActivityState(
                                    merit = merit,
                                    score = 0L,
                                    attempts = 0,
                                    scoreMerit = 0L,
                                    attended = baseline.attended,
                                    patrol = baseline.patrol,
                                ),
                            )
                        }
                        continue
                    }
                    if (scoreMerit > Long.MAX_VALUE / ActivityInference.SCORE_POINTS_PER_MERIT) {
                        continue
                    }
                    val minimumScore = scoreMerit * ActivityInference.SCORE_POINTS_PER_MERIT
                    val maximumScore = minOf(
                        if (minimumScore > Long.MAX_VALUE - 9L * attempts) {
                            Long.MAX_VALUE
                        } else {
                            minimumScore + 9L * attempts
                        },
                        maximumDailyScore(maximumAttemptScore),
                    )
                    for (score in minimumScore..maximumScore) {
                        if (
                            ActivityInference.isCompatibleScore(
                                scoreDelta = score,
                                scoreMerit = scoreMerit,
                                attempts = attempts,
                                maximumAttemptScore = maximumAttemptScore,
                            )
                        ) {
                            add(
                                ActivityState(
                                    merit = merit,
                                    score = score,
                                    attempts = attempts,
                                    scoreMerit = scoreMerit,
                                    attended = baseline.attended || attempts > 0,
                                    patrol = baseline.patrol,
                                ),
                            )
                        }
                    }
                }
            }
        }.distinct()
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
    ): Boolean = matchesMaximumAttemptScore(state, checkpoint.member.highScore) &&
        matchesCapturedEvidence(state.activityOption, checkpoint, day, zoneId, facts)

    private fun matchesCapturedEvidence(
        option: ActivityOption,
        checkpoint: Checkpoint,
        day: LocalDate,
        zoneId: ZoneId,
        facts: List<DailyPatrolFact>,
    ): Boolean {
        val patrolBeforeCapture = facts.any { !it.occurredAt.isAfter(checkpoint.capturedAt) }
        if (patrolBeforeCapture && !option.patrol) return false
        val loginAt = checkpoint.member.lastLogin.takeIf { it > 0L }?.let(Instant::ofEpochSecond)
        if (
            loginAt != null &&
            !loginAt.isAfter(checkpoint.capturedAt) &&
            PlatoonPeriods.gameDay(loginAt, zoneId) == day &&
            !option.attended
        ) {
            return false
        }
        return true
    }

    private fun matchesMaximumAttemptScore(
        state: ActivityState,
        maximumAttemptScore: Long,
    ): Boolean = maximumAttemptScore <= 0L || ActivityInference.candidatesForScore(
        scoreDelta = state.score,
        maximumAttemptScore = maximumAttemptScore,
    ).any { candidate ->
        candidate.merit == state.merit &&
            candidate.attempts == state.attempts &&
            candidate.scoreMerit == state.scoreMerit &&
            candidate.attended == state.attended &&
            candidate.dailyPatrol == state.patrol
    }

    private fun maximumDailyScore(maximumAttemptScore: Long): Long =
        if (maximumAttemptScore > 0L) {
            if (maximumAttemptScore > Long.MAX_VALUE / ActivityInference.MAX_DAILY_ATTEMPTS) {
                Long.MAX_VALUE
            } else {
                maximumAttemptScore * ActivityInference.MAX_DAILY_ATTEMPTS
            }
        } else {
            Long.MAX_VALUE
        }

    private fun maximumDailyMerit(maximumAttemptScore: Long): Long =
        if (maximumAttemptScore > 0L) {
            saturatedAdd(
                DAILY_BASELINE_AND_ATTEMPT_MERIT,
                saturatedMultiply(
                    maximumAttemptScore / ActivityInference.SCORE_POINTS_PER_MERIT,
                    ActivityInference.MAX_DAILY_ATTEMPTS,
                ),
            )
        } else {
            Long.MAX_VALUE
        }

    private fun matchesFinalEvidence(
        state: ActivityState,
        cell: WeeklyReportBuilder.DayCell,
        facts: List<DailyPatrolFact>,
    ): Boolean = matchesFinalEvidence(
        option = state.activityOption,
        merit = Bounds.of(state.merit),
        score = Bounds.of(state.score),
        cell = cell,
        facts = facts,
    )

    /** Accepts a final class whose merit and score ranges can still hold a manual value. */
    private fun matchesFinalEvidence(
        option: ActivityOption,
        merit: Bounds,
        score: Bounds,
        cell: WeeklyReportBuilder.DayCell,
        facts: List<DailyPatrolFact>,
    ): Boolean {
        if (facts.isNotEmpty() && !option.patrol) return false
        if (cell.hasLoginFact && !option.attended) return false
        val override = cell.manualOverride ?: return true
        return (override.meritDelta == null || override.meritDelta in merit) &&
            (override.scoreDelta == null || override.scoreDelta in score) &&
            (override.attempts == null || override.attempts == option.attempts) &&
            (override.attended == null || override.attended == option.attended) &&
            (override.dailyPatrol == null || override.dailyPatrol == option.patrol)
    }

    private fun extends(
        prefix: ActivityState,
        final: ActivityState,
        maximumAttemptScore: Long? = null,
    ): Boolean {
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
        return prefix.attempts < ActivityInference.MAX_DAILY_ATTEMPTS &&
            ActivityInference.isCompatibleScore(
                scoreDelta = final.score - prefix.score,
                scoreMerit = final.scoreMerit - prefix.scoreMerit,
                attempts = final.attempts - prefix.attempts,
                maximumAttemptScore = maximumAttemptScore,
            )
    }

    private fun project(
        cell: WeeklyReportBuilder.DayCell,
        states: List<ActivityState>,
        finalized: Boolean,
        observedAt: Instant?,
    ): WeeklyReportBuilder.DayCell = project(cell, StateSummary.of(states), finalized, observedAt)

    private fun project(
        cell: WeeklyReportBuilder.DayCell,
        summary: StateSummary,
        finalized: Boolean,
        observedAt: Instant?,
    ): WeeklyReportBuilder.DayCell {
        val attendedConsensus = summary.attended.singleOrNull()
        val patrolConsensus = summary.patrol.singleOrNull()
        val activityFactMeritFloor = when {
            cell.hasDailyPatrolFact -> LOGIN_MERIT + PATROL_MERIT
            cell.hasLoginFact -> LOGIN_MERIT
            else -> 0L
        }
        val merit = cell.manualOverride?.meritDelta ?: summary.merit?.minimum?.let { inferred ->
            maxOf(inferred, activityFactMeritFloor)
        }
        val score = cell.manualOverride?.scoreDelta ?: summary.score?.minimum
        val attemptValue = cell.manualOverride?.attempts ?: summary.attempts.minOrNull()
        val exactScore = (finalized || attemptValue == ActivityInference.MAX_DAILY_ATTEMPTS || cell.hasFinalGunsmokeScore) &&
            summary.score?.isPoint == true
        val exactAttempts = (finalized || attemptValue == ActivityInference.MAX_DAILY_ATTEMPTS || cell.hasFinalGunsmokeScore) &&
            summary.attempts.size == 1
        val exactMerit = summary.merit?.isPoint == true &&
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

    /** A reset capture closes the previous day, except the week's own opening capture. */
    private fun reportDay(instant: Instant, days: List<LocalDate>, zoneId: ZoneId): LocalDate =
        PlatoonPeriods.checkpointDay(instant, zoneId).takeUnless { it < days.first() }
            ?: PlatoonPeriods.gameDay(instant, zoneId)

    private fun SnapshotMember.hasValidActivityCounters(): Boolean =
        weeklyMerit >= 0L && totalMerit >= 0L && highScore >= 0L && totalScore >= 0L

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
        val weeklyBase: Long,
        val weeklyFinalizedMerit: Long,
    )

    private data class PartialSolution(
        val captured: ActivityState,
        val weeklyBase: Long,
        val weeklyFinalizedMerit: Long,
        val activityTotals: ActivityTotalBounds,
        val finalStatesByDay: List<Set<ActivityState>>,
    ) {
        fun mergeHistory(other: PartialSolution): PartialSolution {
            require(captured == other.captured)
            require(weeklyBase == other.weeklyBase)
            require(weeklyFinalizedMerit == other.weeklyFinalizedMerit)
            require(finalStatesByDay.size == other.finalStatesByDay.size)
            return copy(
                activityTotals = activityTotals.merge(other.activityTotals),
                finalStatesByDay = finalStatesByDay.zip(other.finalStatesByDay) { left, right ->
                    left + right
                },
            )
        }
    }

    data class Resolution(
        val cells: List<WeeklyReportBuilder.DayCell>,
        val totals: WeeklyReportBuilder.ResolvedGunsmokeTotals?,
        val attemptsFloor: Int? = null,
    )

    private data class RunResolution(
        val cells: List<WeeklyReportBuilder.DayCell>,
        val totals: WeeklyReportBuilder.ResolvedGunsmokeTotals?,
    )

    private data class ActivityTotalBounds(
        val minimumMerit: Long,
        val maximumMerit: Long,
        val minimumAttempts: Int,
        val maximumAttempts: Int,
        val minimumLoginDays: Int,
        val maximumLoginDays: Int,
        val minimumPatrolDays: Int,
        val maximumPatrolDays: Int,
    ) {
        val meritCertainty: MetricCertainty
            get() = rangeCertainty(minimumMerit, maximumMerit)
        val attemptsCertainty: MetricCertainty
            get() = rangeCertainty(minimumAttempts, maximumAttempts)
        val loginDaysCertainty: MetricCertainty
            get() = rangeCertainty(minimumLoginDays, maximumLoginDays)
        val patrolDaysCertainty: MetricCertainty
            get() = rangeCertainty(minimumPatrolDays, maximumPatrolDays)

        operator fun plus(state: ActivityState): ActivityTotalBounds = ActivityTotalBounds(
            minimumMerit = minimumMerit + state.merit,
            maximumMerit = maximumMerit + state.merit,
            minimumAttempts = minimumAttempts + state.attempts,
            maximumAttempts = maximumAttempts + state.attempts,
            minimumLoginDays = minimumLoginDays + if (state.attended) 1 else 0,
            maximumLoginDays = maximumLoginDays + if (state.attended) 1 else 0,
            minimumPatrolDays = minimumPatrolDays + if (state.patrol) 1 else 0,
            maximumPatrolDays = maximumPatrolDays + if (state.patrol) 1 else 0,
        )

        fun merge(other: ActivityTotalBounds): ActivityTotalBounds = ActivityTotalBounds(
            minimumMerit = minOf(minimumMerit, other.minimumMerit),
            maximumMerit = maxOf(maximumMerit, other.maximumMerit),
            minimumAttempts = minOf(minimumAttempts, other.minimumAttempts),
            maximumAttempts = maxOf(maximumAttempts, other.maximumAttempts),
            minimumLoginDays = minOf(minimumLoginDays, other.minimumLoginDays),
            maximumLoginDays = maxOf(maximumLoginDays, other.maximumLoginDays),
            minimumPatrolDays = minOf(minimumPatrolDays, other.minimumPatrolDays),
            maximumPatrolDays = maxOf(maximumPatrolDays, other.maximumPatrolDays),
        )

        companion object {
            val ZERO = ActivityTotalBounds(
                minimumMerit = 0L,
                maximumMerit = 0L,
                minimumAttempts = 0,
                maximumAttempts = 0,
                minimumLoginDays = 0,
                maximumLoginDays = 0,
                minimumPatrolDays = 0,
                maximumPatrolDays = 0,
            )

        }
    }

    private data class ActivityTotals(
        val merit: Long,
        val attempts: Int,
        val loginDays: Int,
        val patrolDays: Int,
    )

    private data class ActivitySummary(
        val attempts: Int,
        val baselineMerit: Long,
        val loginDays: Int,
        val patrolDays: Int,
    )

    private data class ActivityOption(
        val attempts: Int,
        val attended: Boolean,
        val patrol: Boolean,
    ) {
        val baselineMerit: Long
            get() = when {
                patrol -> LOGIN_MERIT + PATROL_MERIT
                attended -> LOGIN_MERIT
                else -> 0L
            }
    }

    private data class AggregateCandidate(
        val totals: ActivityTotals,
        val dayOptions: List<Set<ActivityOption>>,
    )

    private data class DayConsensus(
        val attempts: Int?,
        val attended: Boolean?,
        val patrol: Boolean?,
    )

    private data class FinalAggregateResolution(
        val totals: WeeklyReportBuilder.ResolvedGunsmokeTotals,
        val dayConsensus: List<DayConsensus>,
    )

    private data class CompletedSolution(
        val partial: PartialSolution,
        val finalStates: List<ActivityState>,
        val lastDayFinalized: Boolean,
    )

    private val ActivityState.activityOption: ActivityOption
        get() = ActivityOption(attempts, attended, patrol)

    /** An inclusive range of one counter value. */
    private data class Bounds(val minimum: Long, val maximum: Long) {
        val isPoint: Boolean
            get() = minimum == maximum

        operator fun contains(value: Long): Boolean = value in minimum..maximum

        operator fun plus(other: Bounds): Bounds = Bounds(
            saturatedAdd(minimum, other.minimum),
            saturatedAdd(maximum, other.maximum),
        )

        fun hull(other: Bounds): Bounds =
            Bounds(minOf(minimum, other.minimum), maxOf(maximum, other.maximum))

        fun subtractedFrom(total: Long): Bounds = Bounds(total - maximum, total - minimum)

        companion object {
            fun of(value: Long): Bounds = Bounds(value, value)
        }
    }

    private data class IntervalRange(val merit: Bounds, val score: Bounds) {
        fun hull(other: IntervalRange): IntervalRange =
            IntervalRange(merit.hull(other.merit), score.hull(other.score))

        companion object {
            fun of(states: List<ActivityState>): IntervalRange = IntervalRange(
                merit = Bounds(states.minOf(ActivityState::merit), states.maxOf(ActivityState::merit)),
                score = Bounds(states.minOf(ActivityState::score), states.maxOf(ActivityState::score)),
            )
        }
    }

    /** One way to split an interval: a prefix class, its final class, and the next prefix. */
    private data class IntervalEdge(
        val from: ActivityOption,
        val final: ActivityOption,
        val to: ActivityOption,
        val tail: IntervalRange,
        val next: IntervalRange,
    )

    /** What every compatible state of one day shares, for projection into its cell. */
    private data class StateSummary(
        val merit: Bounds?,
        val score: Bounds?,
        val attempts: Set<Int>,
        val attended: Set<Boolean>,
        val patrol: Set<Boolean>,
    ) {
        fun merge(other: StateSummary): StateSummary = StateSummary(
            merit = merit?.let { other.merit?.let(it::hull) ?: it } ?: other.merit,
            score = score?.let { other.score?.let(it::hull) ?: it } ?: other.score,
            attempts = attempts + other.attempts,
            attended = attended + other.attended,
            patrol = patrol + other.patrol,
        )

        companion object {
            fun of(states: List<ActivityState>): StateSummary = StateSummary(
                merit = states.takeIf { it.isNotEmpty() }?.let { IntervalRange.of(it).merit },
                score = states.takeIf { it.isNotEmpty() }?.let { IntervalRange.of(it).score },
                attempts = states.map(ActivityState::attempts).toSet(),
                attended = states.map(ActivityState::attended).toSet(),
                patrol = states.map(ActivityState::patrol).toSet(),
            )

            fun of(option: ActivityOption, merit: Bounds, score: Bounds): StateSummary = StateSummary(
                merit = merit,
                score = score,
                attempts = setOf(option.attempts),
                attended = setOf(option.attended),
                patrol = setOf(option.patrol),
            )
        }
    }

    private data class ExtensionRange(
        val attempts: Int,
        val baseline: ActivityBaseline,
        val fixedMerit: Long,
        val minimumScoreMerit: Long,
        val maximumScoreMerit: Long,
    ) {
        val span: Long
            get() = maximumScoreMerit - minimumScoreMerit + 1L
    }

    private class SearchBudget(remaining: Int) {
        var remaining: Int = remaining
            private set

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
    /** Builds bounded aggregate states while retaining safe per-day marginal possibilities. */
    private fun buildConstrainedActivitySummaries(
        cells: List<WeeklyReportBuilder.DayCell>,
    ): Map<ActivitySummary, List<Set<ActivityOption>>> {
        var states = mapOf(ActivitySummary(0, 0L, 0, 0) to emptyList<Set<ActivityOption>>())
        cells.forEach { cell ->
            val options = activityOptions(cell)
            if (options.isEmpty()) return emptyMap()
            val next = linkedMapOf<ActivitySummary, List<Set<ActivityOption>>>()
            states.forEach { (summary, marginals) ->
                options.forEach { option ->
                    val key = ActivitySummary(
                        attempts = summary.attempts + option.attempts,
                        baselineMerit = summary.baselineMerit + option.baselineMerit,
                        loginDays = summary.loginDays + if (option.attended) 1 else 0,
                        patrolDays = summary.patrolDays + if (option.patrol) 1 else 0,
                    )
                    val candidate = marginals + listOf(setOf(option))
                    val existing = next[key]
                    next[key] = if (existing == null) {
                        candidate
                    } else {
                        existing.indices.map { index -> existing[index] + candidate[index] }
                    }
                }
            }
            states = next
        }
        return states
    }

    private fun activityOptions(cell: WeeklyReportBuilder.DayCell): List<ActivityOption> =
        buildList {
            for (attempts in 0..ActivityInference.MAX_DAILY_ATTEMPTS) {
                ACTIVITY_BASELINES.forEach { baseline ->
                    if (attempts == 0 || baseline.attended) {
                        add(ActivityOption(attempts, baseline.attended, baseline.patrol))
                    }
                }
            }
        }.filter { option ->
            val override = cell.manualOverride
            (override?.attempts == null || override.attempts == option.attempts) &&
                (override?.attended == null || override.attended == option.attended) &&
                (override?.dailyPatrol == null || override.dailyPatrol == option.patrol) &&
                (!cell.hasLoginFact || option.attended) &&
                (!cell.hasDailyPatrolFact || option.patrol) &&
                (
                    cell.solvedAttempts == null || when (cell.solvedAttemptsCertainty) {
                        MetricCertainty.EXACT -> cell.solvedAttempts == option.attempts
                        MetricCertainty.LOWER_BOUND -> option.attempts >= cell.solvedAttempts
                        MetricCertainty.UNKNOWN, null -> true
                    }
                ) &&
                (cell.solvedAttended == null || cell.solvedAttended == option.attended) &&
                (cell.solvedDailyPatrol == null || cell.solvedDailyPatrol == option.patrol)
        }

    private val ALL_ACTIVITY_OPTIONS: List<ActivityOption> =
        (0..ActivityInference.MAX_DAILY_ATTEMPTS).flatMap { attempts ->
            ACTIVITY_BASELINES.filter { attempts == 0 || it.attended }
                .map { ActivityOption(attempts, it.attended, it.patrol) }
        }

    private val MAX_BOUNDARY_DISTANCE: Duration = Duration.ofMinutes(15)
    private const val MAX_ROUNDING_REMAINDER = ActivityInference.SCORE_POINTS_PER_MERIT - 1L
    private const val MAX_REMAINDER_STEPS = 64
    private const val MAX_SCORE_MERIT_VALUES = 250_000L
    private const val MAX_EXTENSION_STATES = 100_000
    private const val MAX_PARTIAL_SOLUTIONS = 256
    private const val MAX_SEARCH_OPERATIONS = 100_000
    private const val MAX_AGGREGATE_SEARCH_OPERATIONS = 100_000
    private const val LOGIN_MERIT = ActivityInference.LOGIN_MERIT
    private const val PATROL_MERIT = ActivityInference.DAILY_PATROL_MERIT
    private const val MAX_PRE_GUNSMOKE_WEEKLY_MERIT = 6L * (LOGIN_MERIT + PATROL_MERIT)
    private const val DAILY_BASELINE_AND_ATTEMPT_MERIT =
        LOGIN_MERIT + PATROL_MERIT +
            ActivityInference.MAX_DAILY_ATTEMPTS * ActivityInference.MERIT_PER_ATTEMPT
}
