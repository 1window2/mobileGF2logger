package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityInferenceTest {
    @Test
    fun identifiesLoginAndDailyPatrolOutsideGunsmoke() {
        val result = ActivityInference.infer(meritDelta = 90, scoreDelta = 0, gunsmokeActive = false)

        assertEquals(EvidencePrecision.INFERRED, result.precision)
        assertTrue(result.selected!!.attended)
        assertTrue(result.selected!!.dailyPatrol)
        assertEquals(0, result.selected!!.attempts)
    }

    @Test
    fun infersThreeMaxScoreAttemptsFromWorkbookRow() {
        val result = ActivityInference.infer(
            meritDelta = 3_342,
            scoreDelta = 31_635,
            gunsmokeActive = true,
        )

        assertEquals(3, result.selected!!.attempts)
        assertEquals(3_162, result.selected!!.scoreMerit)
        assertTrue(result.selected!!.attended)
        assertTrue(result.selected!!.dailyPatrol)
    }

    @Test
    fun capturedHighScoreFixesAggregateAttemptRounding() {
        val candidates = ActivityInference.candidatesForScore(
            scoreDelta = 31_635,
            maximumAttemptScore = 10_545,
        )

        assertEquals(setOf(3), candidates.map { it.attempts }.toSet())
        assertEquals(setOf(3_162L), candidates.map { it.scoreMerit }.toSet())
    }

    @Test
    fun highScoreCompatibilityMatchesEverySmallPerAttemptDistribution() {
        for (maximum in 1L..25L) {
            var possible = setOf(0L to 0L)
            for (attempts in 0..ActivityInference.MAX_WEEKLY_ATTEMPTS) {
                for (score in 0L..maximum * attempts) {
                    for (scoreMerit in 0L..score / ActivityInference.SCORE_POINTS_PER_MERIT) {
                        assertEquals(
                            "$maximum/$attempts/$score/$scoreMerit",
                            score to scoreMerit in possible,
                            ActivityInference.isCompatibleScore(
                                scoreDelta = score,
                                scoreMerit = scoreMerit,
                                attempts = attempts,
                                maximumAttemptScore = maximum,
                            ),
                        )
                    }
                }
                possible = buildSet {
                    possible.forEach { (score, scoreMerit) ->
                        for (attemptScore in 0L..maximum) {
                            add(
                                score + attemptScore to
                                    scoreMerit +
                                    attemptScore / ActivityInference.SCORE_POINTS_PER_MERIT,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun infersTwoMaxScoreAttemptsFromWorkbookRow() {
        val result = ActivityInference.infer(
            meritDelta = 2_258,
            scoreDelta = 21_090,
            gunsmokeActive = true,
        )

        assertEquals(2, result.selected!!.attempts)
        assertEquals(2_108, result.selected!!.scoreMerit)
    }

    @Test
    fun accountsForPerAttemptRemainderLoss() {
        val result = ActivityInference.infer(
            meritDelta = 3_091,
            scoreDelta = 29_127,
            gunsmokeActive = true,
        )

        assertNotNull(result.selected)
        assertEquals(3, result.selected!!.attempts)
        assertEquals(2_911, result.selected!!.scoreMerit)
    }

    @Test
    fun zeroScoreGunsmokeKeepsAttemptArithmetic() {
        val loginOnly = ActivityInference.infer(50, 0, gunsmokeActive = true)
        val oneAttempt = ActivityInference.infer(80, 0, gunsmokeActive = true)
        val twoAttempts = ActivityInference.infer(110, 0, gunsmokeActive = true)
        val patrolOrThreeAttempts = ActivityInference.infer(90, 0, gunsmokeActive = true)

        assertEquals(0, loginOnly.exactAttempts)
        assertEquals(1, oneAttempt.exactAttempts)
        assertEquals(2, twoAttempts.exactAttempts)
        assertEquals(setOf(0), patrolOrThreeAttempts.candidates.map { it.attempts }.toSet())
        assertEquals(0, patrolOrThreeAttempts.exactAttempts)
        assertEquals(true, patrolOrThreeAttempts.attended)
        assertEquals(true, patrolOrThreeAttempts.dailyPatrol)
    }

    @Test
    fun handlesCounterReset() {
        assertEquals(140, ActivityInference.counterDelta(previous = 220_000, current = 140))
        assertEquals(90, ActivityInference.counterDelta(previous = 220_000, current = 220_090))
    }

    @Test
    fun leavesUnsupportedNonGunsmokeDeltaAmbiguous() {
        listOf(40L, 75L).forEach { merit ->
            val result = ActivityInference.infer(
                meritDelta = merit,
                scoreDelta = 0,
                gunsmokeActive = false,
            )

            assertEquals(EvidencePrecision.AMBIGUOUS, result.precision)
            assertFalse(result.candidates.isNotEmpty())
        }
    }
}
