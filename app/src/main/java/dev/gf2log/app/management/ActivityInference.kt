package dev.gf2log.app.management

object ActivityInference {
    private val dailyBaselines = listOf(
        Baseline(merit = 0, attended = false, dailyPatrol = false),
        Baseline(merit = 50, attended = true, dailyPatrol = false),
        Baseline(merit = 90, attended = true, dailyPatrol = true),
    )

    fun infer(meritDelta: Long, scoreDelta: Long, gunsmokeActive: Boolean): Result {
        if (meritDelta < 0 || scoreDelta < 0) return Result.invalid()
        if (!gunsmokeActive) {
            val baseline = dailyBaselines.singleOrNull { it.merit == meritDelta }
                ?: return Result(
                    precision = EvidencePrecision.AMBIGUOUS,
                    candidates = emptyList(),
                )
            return Result(
                precision = EvidencePrecision.INFERRED,
                candidates = listOf(
                    Candidate(
                        merit = baseline.merit,
                        attempts = 0,
                        attended = baseline.attended,
                        dailyPatrol = baseline.dailyPatrol,
                        scoreMerit = 0,
                    ),
                ),
            )
        }

        val candidates = candidatesForScore(scoreDelta)
            .filter { candidate -> candidate.merit == meritDelta }

        return Result(
            precision = when (candidates.size) {
                1 -> EvidencePrecision.INFERRED
                else -> EvidencePrecision.AMBIGUOUS
            },
            candidates = candidates,
        )
    }

    // Function Name: candidatesForScore
    // Description:
    // - Enumerates every valid Gunsmoke activity state for a captured score.
    // - Supports a missing merit boundary without inventing one specific attendance or patrol state.
    // - Uses the captured single-attempt high score as an optional aggregate-score ceiling.
    // Parameters:
    // - scoreDelta: Gunsmoke score accumulated in the current game day.
    // - maximumAttemptScore: Largest possible score for one observed attempt, or null if unavailable.
    // Returns:
    // - Returns every compatible merit, attempt, attendance, patrol, and score-merit combination.
    fun candidatesForScore(
        scoreDelta: Long,
        maximumAttemptScore: Long? = null,
    ): List<Candidate> {
        if (scoreDelta < 0L) return emptyList()
        return buildList {
            for (attempts in 0..MAX_DAILY_ATTEMPTS) {
                if (attempts == 0 && scoreDelta != 0L) continue
                if (
                    attempts > 0 &&
                    maximumAttemptScore?.takeIf { it > 0L }?.let { maximum ->
                        scoreExceedsAttemptMaximum(scoreDelta, attempts, maximum)
                    } == true
                ) {
                    continue
                }
                val aggregateFloor = scoreDelta / SCORE_POINTS_PER_MERIT
                val roundingLoss = (attempts - 1).coerceAtLeast(0)
                val minimumScoreMerit = (aggregateFloor - roundingLoss).coerceAtLeast(0)
                for (scoreMerit in minimumScoreMerit..aggregateFloor) {
                    if (!isCompatibleScore(scoreDelta, scoreMerit, attempts, maximumAttemptScore)) continue
                    for (baseline in dailyBaselines) {
                        if ((attempts > 0 || scoreDelta > 0L) && !baseline.attended) continue
                        val expected = baseline.merit +
                            attempts * MERIT_PER_ATTEMPT +
                            scoreMerit
                        add(
                            Candidate(
                                merit = expected,
                                attempts = attempts,
                                attended = baseline.attended || attempts > 0 || scoreDelta > 0,
                                dailyPatrol = baseline.dailyPatrol,
                                scoreMerit = scoreMerit,
                            ),
                        )
                    }
                }
            }
        }.distinct()
    }

    // Function Name: isCompatibleScore
    // Description:
    // - Verifies the exact aggregate score-to-merit relationship for a known attempt count.
    // - Accounts for per-attempt integer rounding and an optional captured high-score ceiling.
    // Parameters:
    // - scoreDelta: Aggregate score earned by the attempts.
    // - scoreMerit: Sum of the per-attempt score merit after integer division by ten.
    // - attempts: Number of attempts represented by the aggregate.
    // - maximumAttemptScore: Captured single-attempt upper bound, or null when unavailable.
    // Returns:
    // - True only when at least one per-attempt score distribution satisfies every constraint.
    internal fun isCompatibleScore(
        scoreDelta: Long,
        scoreMerit: Long,
        attempts: Int,
        maximumAttemptScore: Long? = null,
    ): Boolean {
        if (scoreDelta < 0L || scoreMerit < 0L || attempts !in 0..MAX_WEEKLY_ATTEMPTS) return false
        if (attempts == 0) return scoreDelta == 0L && scoreMerit == 0L
        val maximum = maximumAttemptScore?.takeIf { it > 0L }
        if (maximum != null && scoreExceedsAttemptMaximum(scoreDelta, attempts, maximum)) return false
        val aggregateFloor = scoreDelta / SCORE_POINTS_PER_MERIT
        val roundingLoss = aggregateFloor - scoreMerit
        if (roundingLoss !in 0L until attempts.toLong()) return false
        if (
            scoreDelta % SCORE_POINTS_PER_MERIT +
            roundingLoss * SCORE_POINTS_PER_MERIT > 9L * attempts
        ) {
            return false
        }
        return maximum == null || scoreMeritFitsMaximum(
            score = scoreDelta,
            scoreMerit = scoreMerit,
            attempts = attempts,
            maximumAttemptScore = maximum,
        )
    }

    /**
     * Checks whether an aggregate score and its merit can be distributed among
     * the observed attempts without any attempt exceeding the captured high score.
     */
    private fun scoreMeritFitsMaximum(
        score: Long,
        scoreMerit: Long,
        attempts: Int,
        maximumAttemptScore: Long,
    ): Boolean {
        if (attempts == 0) return score == 0L && scoreMerit == 0L
        val quotientMaximum = maximumAttemptScore / SCORE_POINTS_PER_MERIT
        val remainderMaximum = maximumAttemptScore % SCORE_POINTS_PER_MERIT
        val remainderTotal = score - scoreMerit * SCORE_POINTS_PER_MERIT
        if (remainderTotal < 0L) return false

        return (0..attempts).any { maximumQuotientCount ->
            if (quotientMaximum == 0L && maximumQuotientCount != attempts) {
                return@any false
            }
            val otherCount = attempts - maximumQuotientCount
            val minimumQuotientSum = saturatedMultiply(quotientMaximum, maximumQuotientCount)
            val maximumOtherQuotient = (quotientMaximum - 1L).coerceAtLeast(0L)
            val maximumQuotientSum = saturatedAdd(
                minimumQuotientSum,
                saturatedMultiply(maximumOtherQuotient, otherCount),
            )
            val maximumRemainderSum =
                maximumQuotientCount * remainderMaximum + otherCount * 9L
            scoreMerit in minimumQuotientSum..maximumQuotientSum &&
                remainderTotal <= maximumRemainderSum
        }
    }

    private fun scoreExceedsAttemptMaximum(
        score: Long,
        attempts: Int,
        maximumAttemptScore: Long,
    ): Boolean = score / attempts > maximumAttemptScore ||
        (score / attempts == maximumAttemptScore && score % attempts != 0L)

    private fun saturatedMultiply(value: Long, factor: Int): Long = when {
        factor == 0 -> 0L
        value > Long.MAX_VALUE / factor -> Long.MAX_VALUE
        else -> value * factor
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    fun counterDelta(previous: Long, current: Long): Long =
        if (current >= previous) current - previous else current

    data class Result(
        val precision: EvidencePrecision,
        val candidates: List<Candidate>,
    ) {
        val selected: Candidate?
            get() = candidates.singleOrNull()

        val attemptsLowerBound: Int?
            get() = candidates.minOfOrNull(Candidate::attempts)

        val exactAttempts: Int?
            get() = candidates.map(Candidate::attempts).distinct().singleOrNull()

        val attended: Boolean?
            get() = candidates.map(Candidate::attended).distinct().singleOrNull()

        val dailyPatrol: Boolean?
            get() = candidates.map(Candidate::dailyPatrol).distinct().singleOrNull()

        companion object {
            fun invalid(): Result = Result(EvidencePrecision.AMBIGUOUS, emptyList())
        }
    }

    data class Candidate(
        val merit: Long,
        val attempts: Int,
        val attended: Boolean,
        val dailyPatrol: Boolean,
        val scoreMerit: Long,
    )

    private data class Baseline(
        val merit: Long,
        val attended: Boolean,
        val dailyPatrol: Boolean,
    )

    const val MAX_DAILY_ATTEMPTS = 3
    const val MAX_WEEKLY_ATTEMPTS = MAX_DAILY_ATTEMPTS * 7
    const val MERIT_PER_ATTEMPT = 30L
    const val SCORE_POINTS_PER_MERIT = 10L
}
