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
    ): List<WeeklyReportBuilder.DayCell> = resolve(
        uid,
        days,
        zoneId,
        snapshots,
        cells,
        dailyPatrolFacts,
    ).cells

    // Function Name: resolve
    // Description:
    // - Solves daily Gunsmoke states and preserves whole-week totals shared by every valid path.
    // - Keeps daily exactness independent from aggregate exactness when only the day placement varies.
    // Parameters:
    // - uid: Member whose counter history is being reconciled.
    // - days: Seven game days in report order.
    // - zoneId: Game-day timezone used for 05:00 and final-event boundaries.
    // - snapshots: Available structured roster counter observations.
    // - cells: Conservative daily projections built from direct boundaries.
    // - dailyPatrolFacts: UID-safe timestamped Daily Patrol evidence.
    // Returns:
    // - Returns resolved daily cells and an optional final-week aggregate consensus.
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
        var detailedSearchExhausted = false
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
            detailedSearchExhausted = detailedSearchExhausted || run.searchExhausted
            index++
        }
        if (
            resolvedTotals == null &&
            detailedSearchExhausted &&
            checkpoints.all { it != null } &&
            resolved.last().hasFinalGunsmokeScore
        ) {
            resolvedTotals = resolveFinalEventTotals(
                days = days,
                zoneId = zoneId,
                anchor = anchor,
                periodStart = periodStart,
                checkpoints = checkpoints,
                cells = resolved,
                factsByDay = factsByDay,
            )
        }
        return Resolution(
            cells = resolved,
            totals = resolvedTotals,
            attemptsFloor = weeklyAttemptsFloor(checkpoints),
        )
    }

    // Function Name: resolveFinalEventTotals
    // Description:
    // - Computes conservative whole-week activity totals when detailed path expansion reaches its budget.
    // - Uses the Monday reset to establish Sunday and the final event counters to solve six-day aggregates.
    // - Never assigns an aggregate possibility to a specific day.
    // Parameters:
    // - days: Seven report days from Sunday through Saturday.
    // - zoneId: Game-day timezone for timestamp evidence.
    // - anchor: Optional capture immediately before the Sunday boundary.
    // - periodStart: Sunday report boundary instant.
    // - checkpoints: One capture for every report day.
    // - cells: Current conservative daily projections.
    // - factsByDay: UID-safe Daily Patrol evidence grouped by game day.
    // Returns:
    // - Minimum or exact aggregate values compatible with all end-event arithmetic.
    private fun resolveFinalEventTotals(
        days: List<LocalDate>,
        zoneId: ZoneId,
        anchor: Checkpoint?,
        periodStart: Instant,
        checkpoints: List<Checkpoint?>,
        cells: List<WeeklyReportBuilder.DayCell>,
        factsByDay: Map<LocalDate, List<DailyPatrolFact>>,
    ): WeeklyReportBuilder.ResolvedGunsmokeTotals? {
        if (days.size != 7 || checkpoints.size != days.size) return null
        val sunday = requireNotNull(checkpoints[0])
        val monday = requireNotNull(checkpoints[1])
        val final = requireNotNull(checkpoints.last())
        val ordered = checkpoints.map(::requireNotNull)
        if (
            monday.member.totalMerit < sunday.member.totalMerit ||
            monday.member.totalScore < sunday.member.totalScore ||
            ordered.zipWithNext().any { (earlier, later) ->
                later.member.totalMerit < earlier.member.totalMerit ||
                    later.member.totalScore < earlier.member.totalScore
            } ||
            ordered.drop(1).zipWithNext().any { (earlier, later) ->
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

        val candidates = sundayFinals.flatMap { sundayFinal ->
            val remainingScore = final.member.totalScore - sundayFinal.score
            if (remainingScore < 0L) return@flatMap emptyList()
            SIX_DAY_ACTIVITY_SUMMARIES.mapNotNull { summary ->
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
                ActivityTotals(
                    merit = sundayFinal.merit + final.member.weeklyMerit,
                    attempts = sundayFinal.attempts + summary.attempts,
                    loginDays = (if (sundayFinal.attended) 1 else 0) + summary.loginDays,
                    patrolDays = (if (sundayFinal.patrol) 1 else 0) + summary.patrolDays,
                )
            }
        }.distinct()
        if (candidates.isEmpty()) return null
        val minimumMerit = candidates.minOf(ActivityTotals::merit)
        val maximumMerit = candidates.maxOf(ActivityTotals::merit)
        val minimumAttempts = candidates.minOf(ActivityTotals::attempts)
        val maximumAttempts = candidates.maxOf(ActivityTotals::attempts)
        val minimumLoginDays = candidates.minOf(ActivityTotals::loginDays)
        val maximumLoginDays = candidates.maxOf(ActivityTotals::loginDays)
        val minimumPatrolDays = candidates.minOf(ActivityTotals::patrolDays)
        val maximumPatrolDays = candidates.maxOf(ActivityTotals::patrolDays)
        return WeeklyReportBuilder.ResolvedGunsmokeTotals(
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
                            maximumMerit = minOf(
                                requiredPreviousFinalMerit ?: maximumPreviousMerit,
                                maximumDailyMerit(currentCheckpoint.member.highScore),
                            ),
                            meritDelta = meritDelta,
                            scoreDelta = scoreDelta,
                            maximumAttemptScore = currentCheckpoint.member.highScore,
                            searchBudget = searchBudget,
                        ) ?: return RunResolution(bestEffortCells, null, searchExhausted = true)
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
                                        return RunResolution(
                                            bestEffortCells,
                                            null,
                                            searchExhausted = true,
                                        )
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

    // Function Name: initialCapturedStates
    // Description:
    // - Establishes every valid state at the first capture in a contiguous run.
    // - Uses a direct boundary when available, otherwise derives Sunday from the reset score counter.
    // - Preserves all attendance, patrol, and score-rounding possibilities until later counters prune them.
    // Parameters:
    // - runStart: First observed day index in the contiguous run.
    // - anchor: Latest snapshot before the report period, if one exists.
    // - checkpoint: First member observation in the run.
    // - periodStart: Sunday 05:00 report boundary.
    // - cell: Conservatively derived first-day cell.
    // Returns:
    // - Returns every compatible captured activity state.
    private fun initialCapturedStates(
        runStart: Int,
        anchor: Checkpoint?,
        checkpoint: Checkpoint,
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
            isClosedOpeningAnchor(anchor, periodStart) &&
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

    // Function Name: isClosedOpeningAnchor
    // Description:
    // - Accepts a near-reset capture or a standard-week counter that has already reached its cap.
    // - A capped Monday-through-Saturday counter cannot gain more merit before the Gunsmoke Sunday.
    // Parameters:
    // - anchor: Latest counter observation before the Gunsmoke report boundary.
    // - periodStart: Sunday 05:00 Gunsmoke report boundary.
    // Returns:
    // - True when no unobserved pre-boundary merit can contaminate the opening interval.
    private fun isClosedOpeningAnchor(anchor: Checkpoint, periodStart: Instant): Boolean =
        Duration.between(anchor.capturedAt, periodStart) <= MAX_BOUNDARY_DISTANCE ||
            anchor.member.weeklyMerit == MAX_PRE_GUNSMOKE_WEEKLY_MERIT

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

    // Function Name: statesExtending
    // Description:
    // - Enumerates previous-day final states through their complementary current-day states.
    // - Avoids scanning every score up to the raw counter delta when only 28 remainders can exist.
    // - Refuses pathological counter ranges so malformed data degrades to conservative cells.
    // Parameters:
    // - prefix: State already captured during the previous game day.
    // - maximumMerit: Largest previous-day final merit allowed by the counter transition.
    // - meritDelta: Total-merit counter change between the two captures.
    // - scoreDelta: Total-score counter change between the two captures.
    // - maximumAttemptScore: Latest captured high score used as a safe per-attempt ceiling.
    // Returns:
    // - Valid extensions, or null when the deterministic search budget would be exceeded.
    private fun statesExtending(
        prefix: ActivityState,
        minimumMerit: Long,
        maximumMerit: Long,
        meritDelta: Long,
        scoreDelta: Long,
        maximumAttemptScore: Long,
        searchBudget: SearchBudget,
    ): List<ActivityState>? {
        val effectiveMinimumMerit = maxOf(prefix.merit, minimumMerit)
        if (maximumMerit < effectiveMinimumMerit || meritDelta < 0L || scoreDelta < 0L) {
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
                    val currentMerit = meritDelta - (state.merit - prefix.merit)
                    val currentScore = scoreDelta - (state.score - prefix.score)
                    if (
                        extends(prefix, state, maximumAttemptScore) &&
                        statesForMetrics(currentMerit, currentScore, maximumAttemptScore).isNotEmpty()
                    ) {
                        states += state
                    }
                    continue
                }
                val minimumScoreMerit = maxOf(
                    0L,
                    prefix.scoreMerit,
                    effectiveMinimumMerit - fixedMerit,
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
                )
                if (maximumScoreMerit < minimumScoreMerit) continue
                val span = maximumScoreMerit - minimumScoreMerit + 1L
                examinedScoreMerits += span
                if (examinedScoreMerits > MAX_SCORE_MERIT_VALUES) return null

                for (scoreMerit in minimumScoreMerit..maximumScoreMerit) {
                    if (!searchBudget.consume()) return null
                    val merit = fixedMerit + scoreMerit
                    val currentMerit = meritDelta - (merit - prefix.merit)
                    if (currentMerit < 0L) continue
                    statesForMerit(currentMerit, maximumAttemptScore).forEach { current ->
                        if (!searchBudget.consume()) return null
                        val score = prefix.score + scoreDelta - current.score
                        val state = ActivityState(
                            merit = merit,
                            score = score,
                            attempts = attempts,
                            scoreMerit = scoreMerit,
                            attended = true,
                            patrol = baseline.patrol,
                        )
                        if (extends(prefix, state, maximumAttemptScore)) states += state
                        if (states.size > MAX_EXTENSION_STATES) return null
                    }
                }
            }
        }
        return states.toList()
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
    ): Boolean {
        if (!matchesMaximumAttemptScore(state, checkpoint.member.highScore)) return false
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

    private fun saturatedMultiply(value: Long, factor: Int): Long = when {
        factor == 0 -> 0L
        value > Long.MAX_VALUE / factor -> Long.MAX_VALUE
        else -> value * factor
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

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
    ): WeeklyReportBuilder.DayCell {
        val merits = states.map(ActivityState::merit).distinct()
        val scores = states.map(ActivityState::score).distinct()
        val attempts = states.map(ActivityState::attempts).distinct()
        val attendedConsensus = states.map(ActivityState::attended).distinct().singleOrNull()
        val patrolConsensus = states.map(ActivityState::patrol).distinct().singleOrNull()
        val activityFactMeritFloor = when {
            cell.hasDailyPatrolFact -> LOGIN_MERIT + PATROL_MERIT
            cell.hasLoginFact -> LOGIN_MERIT
            else -> 0L
        }
        val merit = cell.manualOverride?.meritDelta ?: merits.minOrNull()?.let { inferred ->
            maxOf(inferred, activityFactMeritFloor)
        }
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
        val searchExhausted: Boolean = false,
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
            get() = certainty(minimumMerit, maximumMerit)
        val attemptsCertainty: MetricCertainty
            get() = certainty(minimumAttempts, maximumAttempts)
        val loginDaysCertainty: MetricCertainty
            get() = certainty(minimumLoginDays, maximumLoginDays)
        val patrolDaysCertainty: MetricCertainty
            get() = certainty(minimumPatrolDays, maximumPatrolDays)

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

            private fun certainty(minimum: Number, maximum: Number): MetricCertainty =
                if (minimum.toLong() == maximum.toLong()) {
                    MetricCertainty.EXACT
                } else {
                    MetricCertainty.LOWER_BOUND
                }
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
    private val SIX_DAY_ACTIVITY_SUMMARIES: Set<ActivitySummary> = buildActivitySummaries(dayCount = 6)

    /** Builds every aggregate attempts/baseline combination for a fixed number of game days. */
    private fun buildActivitySummaries(dayCount: Int): Set<ActivitySummary> {
        var summaries = setOf(ActivitySummary(0, 0L, 0, 0))
        repeat(dayCount) {
            summaries = buildSet {
                summaries.forEach { summary ->
                    for (attempts in 0..ActivityInference.MAX_DAILY_ATTEMPTS) {
                        ACTIVITY_BASELINES.forEach { baseline ->
                            if (attempts == 0 || baseline.attended) {
                                add(
                                    ActivitySummary(
                                        attempts = summary.attempts + attempts,
                                        baselineMerit = summary.baselineMerit + baseline.merit,
                                        loginDays = summary.loginDays + if (baseline.attended) 1 else 0,
                                        patrolDays = summary.patrolDays + if (baseline.patrol) 1 else 0,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        return summaries
    }

    private val MAX_BOUNDARY_DISTANCE: Duration = Duration.ofMinutes(15)
    private const val MAX_SCORE_MERIT_VALUES = 250_000L
    private const val MAX_EXTENSION_STATES = 100_000
    private const val MAX_PARTIAL_SOLUTIONS = 2_000
    private const val MAX_SEARCH_OPERATIONS = 100_000
    private const val MAX_AGGREGATE_SEARCH_OPERATIONS = 100_000
    private const val LOGIN_MERIT = 50L
    private const val PATROL_MERIT = 40L
    private const val MAX_PRE_GUNSMOKE_WEEKLY_MERIT = 6L * (LOGIN_MERIT + PATROL_MERIT)
    private const val DAILY_BASELINE_AND_ATTEMPT_MERIT =
        LOGIN_MERIT + PATROL_MERIT +
            ActivityInference.MAX_DAILY_ATTEMPTS * ActivityInference.MERIT_PER_ATTEMPT
}
