package dev.gf2log.app.management

object ActivityInference {
    private val dailyBaselines = listOf(
        Baseline(merit = 0, attended = false, dailyPatrol = false),
        Baseline(merit = 40, attended = false, dailyPatrol = true),
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
                        attempts = 0,
                        attended = baseline.attended,
                        dailyPatrol = baseline.dailyPatrol,
                        scoreMerit = 0,
                    ),
                ),
            )
        }

        val candidates = buildList {
            for (attempts in 0..MAX_DAILY_ATTEMPTS) {
                if (attempts == 0 && scoreDelta != 0L) continue
                val aggregateFloor = scoreDelta / SCORE_POINTS_PER_MERIT
                val roundingLoss = (attempts - 1).coerceAtLeast(0)
                val minimumScoreMerit = (aggregateFloor - roundingLoss).coerceAtLeast(0)
                for (scoreMerit in minimumScoreMerit..aggregateFloor) {
                    for (baseline in dailyBaselines) {
                        val expected = baseline.merit +
                            attempts * MERIT_PER_ATTEMPT +
                            scoreMerit
                        if (expected == meritDelta) {
                            add(
                                Candidate(
                                    attempts = attempts,
                                    attended = baseline.attended,
                                    dailyPatrol = baseline.dailyPatrol,
                                    scoreMerit = scoreMerit,
                                ),
                            )
                        }
                    }
                }
            }
        }.distinct()

        return Result(
            precision = when (candidates.size) {
                1 -> EvidencePrecision.INFERRED
                else -> EvidencePrecision.AMBIGUOUS
            },
            candidates = candidates,
        )
    }

    fun counterDelta(previous: Long, current: Long): Long =
        if (current >= previous) current - previous else current

    data class Result(
        val precision: EvidencePrecision,
        val candidates: List<Candidate>,
    ) {
        val selected: Candidate?
            get() = candidates.singleOrNull()

        companion object {
            fun invalid(): Result = Result(EvidencePrecision.AMBIGUOUS, emptyList())
        }
    }

    data class Candidate(
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
    const val MERIT_PER_ATTEMPT = 30L
    const val SCORE_POINTS_PER_MERIT = 10L
}

