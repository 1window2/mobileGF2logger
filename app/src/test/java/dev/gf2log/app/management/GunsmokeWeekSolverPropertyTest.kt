package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GunsmokeWeekSolverPropertyTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val periodStart = LocalDate.of(2026, 7, 19)

    @Test(timeout = 60_000)
    fun sparseValidCaptureRoutinesRemainConservativeAndOrderIndependent() {
        val random = Random(0x2_3_3)
        var publishedMeritChecks = 0
        var publishedScoreChecks = 0
        repeat(128) { caseIndex ->
            val truth = List(7) {
                val attempts = random.nextInt(0, 4)
                val attemptScores = List(attempts) { random.nextInt(0, 10_001).toLong() }
                val score = attemptScores.sum()
                val attended = attempts > 0 || random.nextBoolean()
                val patrol = attended && random.nextBoolean()
                val merit = attempts * ActivityInference.MERIT_PER_ATTEMPT +
                    attemptScores.sumOf { attemptScore -> attemptScore / 10L } +
                    (if (attended) 50L else 0L) + (if (patrol) 40L else 0L)
                Truth(merit, score, attended, patrol)
            }
            val complete = snapshots(caseIndex, truth)
            val selected = complete.filterIndexed { index, _ ->
                index == 0 || index == complete.lastIndex || random.nextBoolean()
            }

            fun build(input: List<PlatoonSnapshot>) = WeeklyReportBuilder.build(
                referenceDay = periodStart.plusDays(3),
                zoneId = zone,
                snapshots = input,
                asOf = periodStart.plusDays(8).atTime(6, 0).atZone(zone).toInstant(),
            ).members.single().days

            fun assertConservative(label: String, cells: List<WeeklyReportBuilder.DayCell>) {
                cells.zip(truth).forEach { (cell, actual) ->
                    cell.meritDelta?.let { published ->
                        publishedMeritChecks += 1
                        assertTrue("$label merit floor case $caseIndex on ${cell.gameDay}", published <= actual.merit)
                    }
                    cell.scoreDelta?.let { published ->
                        publishedScoreChecks += 1
                        assertTrue("$label score floor case $caseIndex on ${cell.gameDay}", published <= actual.score)
                    }
                    if (cell.meritCertainty == MetricCertainty.EXACT) {
                        assertEquals("$label exact merit case $caseIndex on ${cell.gameDay}", actual.merit, cell.meritDelta)
                    }
                    if (cell.scoreCertainty == MetricCertainty.EXACT) {
                        assertEquals("$label exact score case $caseIndex on ${cell.gameDay}", actual.score, cell.scoreDelta)
                    }
                    cell.attended?.let { attended ->
                        assertEquals("$label attendance case $caseIndex on ${cell.gameDay}", actual.attended, attended)
                    }
                    cell.dailyPatrol?.let { patrol ->
                        assertEquals("$label patrol case $caseIndex on ${cell.gameDay}", actual.patrol, patrol)
                    }
                }
            }
            assertConservative("complete", build(complete))
            val sparseCells = build(selected)
            assertConservative("sparse", sparseCells)
            assertEquals(
                "order independence case $caseIndex",
                sparseCells.map { Triple(it.meritDelta, it.scoreDelta, it.meritCertainty to it.scoreCertainty) },
                build(selected.reversed()).map {
                    Triple(it.meritDelta, it.scoreDelta, it.meritCertainty to it.scoreCertainty)
                },
            )
        }
        assertTrue("generated cases must exercise merit publication", publishedMeritChecks > 0)
        assertTrue("generated cases must exercise score publication", publishedScoreChecks > 0)
    }

    /**
     * Automatic recording captures every member once a day at 04:56, just before the reset, while
     * fifty members play one or two attempts a day. Each interval can still split between the
     * previous day's last minutes and the new day, so the days stay lower bounds. The detailed
     * search must hand over to interval bounds instead of dropping the rest of the week: every
     * day keeps its Daily Patrol and a score floor, and the weekly totals stay exact.
     */
    @Test(timeout = 10_000)
    fun dailyPreResetRecordingKeepsEveryDayAndExactWeeklyTotals() {
        val random = Random(7)
        val truths = List(50) {
            List(7) {
                val attempts = random.nextInt(1, 3)
                RecordedDay(attemptScores = List(attempts) { random.nextLong(6_000, 10_001) }, patrol = true)
            }
        }
        val snapshots = (0..7).map { completedDays ->
            val capturedAt = periodStart.plusDays(completedDays.toLong()).atTime(4, 56).atZone(zone).toInstant()
            PlatoonSnapshot(
                id = completedDays.toLong(),
                capturedAt = capturedAt,
                members = truths.mapIndexed { index, days ->
                    recordedMember(index + 1L, days, completedDays, 10_000L, capturedAt.minusSeconds(3_600))
                },
            )
        }

        val report = WeeklyReportBuilder.build(
            referenceDay = periodStart,
            zoneId = zone,
            snapshots = snapshots,
            asOf = periodStart.plusDays(8).atTime(6, 0).atZone(zone).toInstant(),
        )

        assertEquals(50, report.members.size)
        report.members.forEach { row ->
            val days = truths[(row.uid - 1).toInt()]
            val label = "member ${row.uid}"
            assertConservative(label, row.days, days)
            row.days.forEach { cell ->
                assertEquals("$label login on ${cell.gameDay}", true, cell.attended)
                assertEquals("$label patrol on ${cell.gameDay}", true, cell.dailyPatrol)
                assertTrue("$label merit floor on ${cell.gameDay}", requireNotNull(cell.meritDelta) >= 90L)
                assertTrue("$label score floor on ${cell.gameDay}", cell.scoreDelta != null)
            }
            // The Monday weekly reset still closes Sunday exactly.
            assertEquals(MetricCertainty.EXACT, row.days.first().meritCertainty)
            assertEquals(MetricCertainty.EXACT, row.days.first().attemptsCertainty)
            assertEquals(label, days.sumOf(RecordedDay::merit), row.totalMerit)
            assertEquals(label, MetricCertainty.EXACT, row.totalMeritCertainty)
            assertEquals(label, days.sumOf(RecordedDay::attempts), row.totalAttempts)
            assertEquals(label, MetricCertainty.EXACT, row.totalAttemptsCertainty)
            assertEquals(label, 7, row.loginDays)
            assertEquals(label, MetricCertainty.EXACT, row.loginDaysCertainty)
            assertEquals(label, 7, row.patrolDays)
            assertEquals(label, MetricCertainty.EXACT, row.patrolDaysCertainty)
        }
    }

    /**
     * Daily, twice-daily, sparse, and exact-reset recording routines with any attempt count and
     * high score never publish a value the true week contradicts.
     */
    @Test(timeout = 60_000)
    fun recordingRoutinesAroundTheResetRemainConservative() {
        repeat(160) { caseIndex ->
            val random = Random(caseIndex * 7_919 + 13)
            val highScore = listOf(10_000L, 10_545L, 7_985L)[random.nextInt(3)]
            val days = List(7) {
                val attempts = random.nextInt(0, 4)
                val attended = attempts > 0 || random.nextBoolean()
                RecordedDay(
                    attemptScores = List(attempts) { random.nextLong(highScore / 2, highScore + 1) },
                    attended = attended,
                    patrol = attended && random.nextInt(4) != 0,
                )
            }
            val routine = caseIndex % 4
            val captures = buildList {
                (0..7).forEach { completedDays ->
                    val time = if (routine == 3) 5 to 0 else 4 to 56
                    if (routine != 2 || completedDays == 0 || completedDays == 7 || random.nextBoolean()) {
                        add(periodStart.plusDays(completedDays.toLong()).atTime(time.first, time.second) to completedDays)
                    }
                    if (routine == 1 && completedDays < 7) {
                        add(periodStart.plusDays(completedDays.toLong()).atTime(12, 0) to completedDays)
                    }
                }
            }
            val snapshots = captures.map { (localTime, completedDays) ->
                val capturedAt = localTime.atZone(zone).toInstant()
                // Every attended day logs in at 09:00.
                val lastLogin = (0..6).asSequence()
                    .filter { index -> days[index].attended }
                    .map { index -> periodStart.plusDays(index.toLong()).atTime(9, 0).atZone(zone).toInstant() }
                    .lastOrNull { it.isBefore(capturedAt) }
                PlatoonSnapshot(
                    id = 0,
                    capturedAt = capturedAt,
                    members = listOf(
                        recordedMember(
                            uid = 1L,
                            days = days,
                            completedDays = completedDays,
                            highScore = highScore,
                            lastLogin = lastLogin,
                            // A noon capture sees the morning half of that day's attempts.
                            partialDay = days.getOrNull(completedDays).takeIf { localTime.hour == 12 },
                        ),
                    ),
                )
            }
            val facts = if (caseIndex % 5 == 0) {
                days.indices.filter { days[it].patrol }.map { index ->
                    DailyPatrolFact(1L, periodStart.plusDays(index.toLong()).atTime(10, 0).atZone(zone).toInstant())
                }
            } else {
                emptyList()
            }

            val cells = WeeklyReportBuilder.build(
                referenceDay = periodStart,
                zoneId = zone,
                snapshots = snapshots,
                dailyPatrolFacts = facts,
                asOf = periodStart.plusDays(8).atTime(6, 0).atZone(zone).toInstant(),
            ).members.single().days

            assertConservative("routine $routine case $caseIndex", cells, days)
        }
    }

    private fun assertConservative(
        label: String,
        cells: List<WeeklyReportBuilder.DayCell>,
        days: List<RecordedDay>,
    ) {
        cells.zip(days).forEach { (cell, actual) ->
            val at = "$label on ${cell.gameDay}"
            cell.meritDelta?.let { assertTrue("$at merit floor", it <= actual.merit) }
            cell.scoreDelta?.let { assertTrue("$at score floor", it <= actual.score) }
            cell.attempts?.let { assertTrue("$at attempts floor", it <= actual.attempts) }
            if (cell.meritCertainty == MetricCertainty.EXACT) assertEquals("$at merit", actual.merit, cell.meritDelta)
            if (cell.scoreCertainty == MetricCertainty.EXACT) assertEquals("$at score", actual.score, cell.scoreDelta)
            if (cell.attemptsCertainty == MetricCertainty.EXACT) assertEquals("$at attempts", actual.attempts, cell.attempts)
            cell.attended?.let { assertEquals("$at login", actual.attended, it) }
            cell.dailyPatrol?.let { assertEquals("$at patrol", actual.patrol, it) }
        }
    }

    /**
     * One member's counters after [completedDays] whole days, plus the morning half of
     * [partialDay] when the capture falls inside it. Merit This Week restarts on Monday.
     */
    private fun recordedMember(
        uid: Long,
        days: List<RecordedDay>,
        completedDays: Int,
        highScore: Long,
        lastLogin: Instant?,
        partialDay: RecordedDay? = null,
    ): SnapshotMember {
        val morning = partialDay?.let { day ->
            RecordedDay(day.attemptScores.take(day.attempts / 2), attended = day.attended, patrol = false)
        }
        val done = days.take(completedDays)
        val partialMerit = morning?.merit ?: 0L
        return SnapshotMember(
            uid = uid,
            name = "Member $uid",
            level = 60,
            weeklyMerit = when {
                completedDays == 0 -> 540L + partialMerit
                completedDays == 1 && partialDay == null -> 540L + done.first().merit
                else -> done.drop(1).sumOf(RecordedDay::merit) + partialMerit
            },
            totalMerit = 100_000L + done.sumOf(RecordedDay::merit) + partialMerit,
            highScore = highScore,
            totalScore = done.sumOf(RecordedDay::score) + (morning?.score ?: 0L),
            lastLogin = lastLogin?.epochSecond ?: 0L,
        )
    }

    private data class RecordedDay(
        val attemptScores: List<Long>,
        val attended: Boolean = true,
        val patrol: Boolean,
    ) {
        val attempts: Int
            get() = attemptScores.size
        val score: Long
            get() = attemptScores.sum()
        val merit: Long
            get() = attempts * ActivityInference.MERIT_PER_ATTEMPT +
                attemptScores.sumOf { it / ActivityInference.SCORE_POINTS_PER_MERIT } +
                (if (attended) ActivityInference.LOGIN_MERIT else 0L) +
                (if (patrol) ActivityInference.DAILY_PATROL_MERIT else 0L)
    }

    private fun snapshots(caseIndex: Int, truth: List<Truth>): List<PlatoonSnapshot> {
        var totalMerit = 100_000L
        var totalScore = 0L
        var weeklyMerit = 6L * 90L
        val snapshots = mutableListOf(
            snapshot(
                caseIndex,
                0,
                periodStart.atTime(4, 59).atZone(zone).toInstant(),
                weeklyMerit,
                totalMerit,
                totalScore,
            ),
        )
        truth.forEachIndexed { index, day ->
            totalMerit += day.merit
            totalScore += day.score
            weeklyMerit = if (index == 1) day.merit else weeklyMerit + day.merit
            snapshots += snapshot(
                caseIndex,
                index + 1,
                periodStart.plusDays(index + 1L).atTime(4, 30).atZone(zone).toInstant(),
                weeklyMerit,
                totalMerit,
                totalScore,
            )
            if (index == 0) {
                weeklyMerit = 0L
                snapshots += snapshot(
                    caseIndex,
                    20,
                    periodStart.plusDays(1).atTime(5, 1).atZone(zone).toInstant(),
                    weeklyMerit,
                    totalMerit,
                    totalScore,
                )
            }
        }
        return snapshots
    }

    private fun snapshot(
        caseIndex: Int,
        index: Int,
        capturedAt: java.time.Instant,
        weeklyMerit: Long,
        totalMerit: Long,
        totalScore: Long,
    ) = PlatoonSnapshot(
        id = index.toLong(),
        capturedAt = capturedAt,
        sourceFile = "gunsmoke-property-$caseIndex-$index.csv",
        members = listOf(
            SnapshotMember(77L, "Property member", 60L, weeklyMerit, totalMerit, 10_000L, totalScore, 0L),
        ),
    )

    private data class Truth(
        val merit: Long,
        val score: Long,
        val attended: Boolean,
        val patrol: Boolean,
    )
}
