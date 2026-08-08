package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportBuilderTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun gunsmokeReportUsesSundayAndPerDayDeltas() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 22),
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-19T20:00:00Z", weekly = 100, score = 1000),
                snapshot("2026-07-20T19:59:00Z", weekly = 190, score = 1000),
                snapshot("2026-07-21T19:59:00Z", weekly = 304, score = 1025),
            ),
        )

        assertTrue(report.isGunsmokeWeek)
        assertEquals(LocalDate.of(2026, 7, 19), report.periodStart)
        assertEquals(90L, report.members.single().days[1].meritDelta)
        assertNull(report.members.single().days[2].meritDelta)
        assertNull(report.members.single().days[2].scoreDelta)
    }

    @Test
    fun everySelectedStandardDayUsesItsContainingSundayToSaturdayPeriod() {
        listOf(
            LocalDate.of(2026, 7, 26),
            LocalDate.of(2026, 7, 27),
            LocalDate.of(2026, 8, 1),
        ).forEach { selectedDay ->
            val report = WeeklyReportBuilder.build(
                referenceDay = selectedDay,
                zoneId = zone,
                snapshots = emptyList(),
            )

            assertTrue(!report.isGunsmokeWeek)
            assertEquals(LocalDate.of(2026, 7, 26), report.periodStart)
            assertEquals(LocalDate.of(2026, 8, 1), report.periodEnd)
        }
    }

    @Test
    fun everySelectedGunsmokeDayUsesTheSameGunsmokePeriod() {
        listOf(
            LocalDate.of(2026, 7, 19),
            LocalDate.of(2026, 7, 22),
            LocalDate.of(2026, 7, 25),
        ).forEach { selectedDay ->
            val report = WeeklyReportBuilder.build(
                referenceDay = selectedDay,
                zoneId = zone,
                snapshots = emptyList(),
            )

            assertTrue(report.isGunsmokeWeek)
            assertEquals(LocalDate.of(2026, 7, 19), report.periodStart)
            assertEquals(LocalDate.of(2026, 7, 25), report.periodEnd)
        }
    }

    @Test
    fun newcomerHasUnobservedCellsBeforeJoiningInsteadOfMissedActivity() {
        val incumbent = member(uid = 1, name = "Incumbent", weekly = 100, score = 1_000)
        val newcomer = member(uid = 2, name = "Newcomer", weekly = 90, score = 0)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-25T20:00:00Z", incumbent),
                snapshotWithMembers("2026-07-26T19:59:00Z", incumbent),
                snapshotWithMembers("2026-07-27T03:00:00Z", incumbent, newcomer),
            ),
        )

        val newcomerRow = report.members.single { it.uid == newcomer.uid }
        assertEquals(LocalDate.of(2026, 7, 26), newcomerRow.days.first().gameDay)
        assertNull(newcomerRow.days.first().meritDelta)
        assertNull(newcomerRow.days.first().inference)
        assertEquals(90L, newcomerRow.days[1].meritDelta)
        assertEquals(true, newcomerRow.days[1].inference?.selected?.attended)
        assertEquals(true, newcomerRow.days[1].inference?.selected?.dailyPatrol)
        assertEquals(DailyEvidence.ATTRIBUTED, newcomerRow.days[1].evidence)
    }

    @Test
    fun firstDatabaseSnapshotUsesTheCapturedWeeklyCounter() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-27T03:00:00Z",
                    member(uid = 2, name = "Initial", weekly = 90, score = 0),
                ),
            ),
        )

        val initial = report.members.single()
        assertEquals(90L, initial.days[1].meritDelta)
        assertEquals(true, initial.days[1].inference?.selected?.attended)
        assertEquals(DailyEvidence.ATTRIBUTED, initial.days[1].evidence)
    }

    @Test
    fun sparseMultiDayDeltaKeepsUnanchoredSundayUnknownAndDistributesRemainingDays() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 28),
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-26T10:00:00Z", weekly = 0, score = 0),
                snapshot("2026-07-28T03:00:00Z", weekly = 180, score = 0),
            ),
        )

        val cells = report.members.single().days
        assertEquals(listOf(null, 90L, 90L), cells.take(3).map { it.meritDelta })
        assertEquals(DailyEvidence.INCOMPLETE_BOUNDARY, cells[0].evidence)
        assertEquals(MetricCertainty.UNKNOWN, cells[0].meritCertainty)
        assertTrue(cells.drop(1).take(2).all { it.evidence == DailyEvidence.ATTRIBUTED })
        assertTrue(report.hasIncompleteDailyEvidence)
    }

    @Test
    fun sparseStandardAggregateKeepsAmbiguousDaysUnknown() {
        val earlier = member(uid = 1, name = "Sparse", weekly = 0, score = 0)
            .copy(totalMerit = 10_000)
        val later = earlier.copy(weeklyMerit = 230, totalMerit = 10_230)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 28),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-27T20:00:00Z", earlier),
                snapshotWithMembers("2026-07-30T20:00:00Z", later),
            ),
        )

        assertEquals(
            listOf(null, null, null),
            report.members.single().days
                .filter { it.gameDay in LocalDate.of(2026, 7, 28)..LocalDate.of(2026, 7, 30) }
                .map { it.meritDelta },
        )
        assertTrue(
            report.members.single().days.any {
                it.evidence == DailyEvidence.SPARSE_INFERRED
            },
        )
        assertTrue(report.hasIncompleteDailyEvidence)
    }

    @Test
    fun mondayResetDoesNotInventMeritBeforeTheFirstSundayCapture() {
        val sunday = member(uid = 1, name = "Reset anchor", weekly = 4_000, score = 0)
            .copy(totalMerit = 20_000)
        val monday = sunday.copy(weeklyMerit = 50, totalMerit = 20_140)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 26),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-26T03:00:00Z", sunday),
                snapshotWithMembers("2026-07-27T03:00:00Z", monday),
            ),
        )

        assertNull(report.members.single().days.first().meritDelta)
        assertEquals(DailyEvidence.INCOMPLETE_BOUNDARY, report.members.single().days.first().evidence)
        assertEquals(MetricCertainty.UNKNOWN, report.members.single().days.first().meritCertainty)
    }

    @Test
    fun latestTuesdayPacketReconcilesMondayAndTuesdayWithoutQuestionMarks() {
        val sundayBoundary = member(uid = 1, name = "Current", weekly = 4_000, score = 0)
            .copy(totalMerit = 20_000)
        val monday = sundayBoundary.copy(weeklyMerit = 90, totalMerit = 20_090)
        val tuesday = monday.copy(weeklyMerit = 180, totalMerit = 20_180)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 28),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-26T19:59:00Z", sundayBoundary),
                snapshotWithMembers("2026-07-27T05:06:15Z", monday),
                snapshotWithMembers("2026-07-28T12:58:01Z", tuesday),
            ),
        )

        val cells = report.members.single().days
        assertEquals(90L, cells[1].meritDelta)
        assertEquals(90L, cells[2].meritDelta)
        assertEquals(DailyEvidence.ATTRIBUTED, cells[1].evidence)
        assertEquals(DailyEvidence.ATTRIBUTED, cells[2].evidence)
    }

    @Test
    fun ambiguousWeeklyCounterDoesNotExposeASelectedAllocation() {
        val monday = member(uid = 1, name = "Ambiguous", weekly = 0, score = 0)
            .copy(totalMerit = 20_000)
        val tuesday = monday.copy(weeklyMerit = 90, totalMerit = 20_090)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 28),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-27T05:06:15Z", monday),
                snapshotWithMembers("2026-07-28T12:58:01Z", tuesday),
            ),
        )

        val cells = report.members.single().days
        assertEquals(listOf(null, null), cells.drop(1).take(2).map { it.meritDelta })
        assertTrue(
            cells.drop(1).take(2).all { it.evidence == DailyEvidence.SPARSE_INFERRED },
        )
    }

    @Test
    fun gunsmokeKeepsSparseDaysUnknownButUsesCapturedTotals() {
        val earlier = member(uid = 1, name = "Gunsmoke", weekly = 0, score = 0)
            .copy(totalMerit = 10_000)
        val later = earlier.copy(
            weeklyMerit = 270,
            totalMerit = 10_270,
            totalScore = 5_000,
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 22),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-20T03:00:00Z", earlier),
                snapshotWithMembers("2026-07-23T03:00:00Z", later),
            ),
        )

        assertTrue(report.members.single().days.any { it.meritDelta == null })
        assertTrue(report.members.single().days.none { it.evidence == DailyEvidence.SPARSE_INFERRED })
        assertEquals(270L, report.members.single().totalMerit)
        assertEquals(5_000L, report.members.single().totalScore)
    }

    @Test
    fun withdrawingMemberIsRemovedFromActiveWeeklyRoster() {
        val member = member(uid = 1, name = "Withdrawing", weekly = 100, score = 1_000)
            .copy(totalMerit = 10_000)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-26T19:59:00Z", member),
                snapshotWithMembers(
                    "2026-07-27T03:00:00Z",
                    member.copy(weeklyMerit = 150, totalMerit = 10_050),
                ),
                snapshotWithMembers("2026-07-27T19:59:00Z"),
            ),
        )

        assertTrue(report.members.isEmpty())
    }

    @Test
    fun newlyJoinedMemberAppearsInLatestWeeklyRoster() {
        val existing = member(uid = 1, name = "Existing", weekly = 90, score = 1_000)
            .copy(totalMerit = 10_000)
        val newcomer = member(uid = 2, name = "New", weekly = 50, score = 0)
            .copy(totalMerit = 50)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-26T19:59:00Z", existing),
                snapshotWithMembers(
                    "2026-07-27T03:00:00Z",
                    existing.copy(weeklyMerit = 180, totalMerit = 10_090),
                    newcomer,
                ),
            ),
        )

        assertEquals(setOf(1L, 2L), report.members.map { it.uid }.toSet())
    }

    @Test
    fun exactUpdatesAfterLatestSnapshotProjectTheEffectiveWeeklyRoster() {
        val existing = member(uid = 1, name = "Existing", weekly = 90, score = 1_000)
            .copy(totalMerit = 10_000)
        val leaving = member(uid = 2, name = "Leaving", weekly = 50, score = 500)
            .copy(totalMerit = 5_000)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-27T03:00:00Z", existing, leaving),
            ),
            membershipEvents = listOf(
                exactEvent(
                    id = 1,
                    uid = 2,
                    type = MemberEventType.LEFT,
                    occurredAt = "2026-07-27T04:00:00Z",
                    name = "Leaving",
                ),
                exactEvent(
                    id = 2,
                    uid = 3,
                    type = MemberEventType.JOINED,
                    occurredAt = "2026-07-27T05:00:00Z",
                    name = "Real newcomer",
                ),
            ),
            asOf = Instant.parse("2026-07-27T06:00:00Z"),
        )

        assertEquals(setOf(1L, 3L), report.members.map { it.uid }.toSet())
        assertEquals("Real newcomer", report.members.single { it.uid == 3L }.name)
    }

    @Test
    fun exactJoinWithoutAnySnapshotCreatesARealIdentityWeeklyRow() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = emptyList(),
            membershipEvents = listOf(
                exactEvent(
                    id = 1,
                    uid = 3,
                    type = MemberEventType.JOINED,
                    occurredAt = "2026-07-27T05:00:00Z",
                    name = "Packet newcomer",
                ),
            ),
            asOf = Instant.parse("2026-07-27T06:00:00Z"),
        )

        assertEquals(listOf(3L), report.members.map { it.uid })
        assertEquals("Packet newcomer", report.members.single().name)
        assertTrue(report.members.single().days.all { it.evidence == DailyEvidence.NO_OBSERVATION })
        assertTrue(report.hasIncompleteDailyEvidence)
    }

    @Test
    fun exactUpdateBeforeLatestSnapshotDoesNotOverrideAuthoritativeRoster() {
        val member = member(uid = 1, name = "Present", weekly = 90, score = 1_000)
            .copy(totalMerit = 10_000)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(snapshotWithMembers("2026-07-27T05:00:00Z", member)),
            membershipEvents = listOf(
                exactEvent(
                    id = 1,
                    uid = 1,
                    type = MemberEventType.LEFT,
                    occurredAt = "2026-07-27T04:00:00Z",
                    name = "Present",
                ),
            ),
            asOf = Instant.parse("2026-07-27T06:00:00Z"),
        )

        assertEquals(listOf(1L), report.members.map { it.uid })
    }

    @Test
    fun mondayWeeklyResetDoesNotCorruptDailyMeritDelta() {
        val sunday = member(uid = 1, name = "Reset", weekly = 4_000, score = 1_000)
            .copy(totalMerit = 20_000)
        val monday = sunday.copy(weeklyMerit = 90, totalMerit = 20_090)
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 20),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-19T19:59:00Z", sunday),
                snapshotWithMembers("2026-07-20T19:59:00Z", monday),
            ),
        )

        assertEquals(90L, report.members.single().days[1].meritDelta)
    }

    @Test
    fun gunsmokeRowsRankByScoreBeforeMerit() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 22),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-19T20:00:00Z",
                    member(uid = 1, name = "Merit", weekly = 100, score = 1_000),
                    member(uid = 2, name = "Score", weekly = 100, score = 1_000),
                ),
                snapshotWithMembers(
                    "2026-07-20T19:59:00Z",
                    member(uid = 1, name = "Merit", weekly = 300, score = 1_050),
                    member(uid = 2, name = "Score", weekly = 200, score = 1_500),
                ),
            ),
        )

        assertEquals(listOf(2L, 1L), report.members.map { it.uid })
    }

    @Test
    fun manualCellOverrideReplacesDerivedValuesWithoutChangingSnapshots() {
        val periodStart = LocalDate.of(2026, 7, 26)
        val report = WeeklyReportBuilder.build(
            referenceDay = periodStart,
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-25T20:00:00Z", weekly = 100, score = 1_000),
                snapshot("2026-07-26T19:59:00Z", weekly = 190, score = 1_000),
            ),
            overrides = listOf(
                WeeklyCellOverride(
                    uid = 1,
                    periodStart = periodStart,
                    gameDay = periodStart,
                    meritDelta = 42,
                    scoreDelta = null,
                    attempts = null,
                    attended = false,
                    dailyPatrol = true,
                ),
            ),
        )

        val cell = report.members.single().days.first()
        assertEquals(42L, cell.meritDelta)
        assertEquals(true, cell.attended)
        assertEquals(true, cell.dailyPatrol)
        assertEquals(EvidencePrecision.MANUAL, cell.precision)
        assertEquals(42L, report.members.single().totalMerit)
    }

    @Test
    fun partialManualPatrolOverridePreservesDerivedMetricCertainty() {
        val periodStart = LocalDate.of(2026, 7, 19)
        val baseline = member(uid = 1, name = "Partial", weekly = 0, score = 0)
            .copy(totalMerit = 10_000, totalScore = 20_000)
        val complete = baseline.copy(totalMerit = 13_342, totalScore = 51_635)
        val report = WeeklyReportBuilder.build(
            referenceDay = periodStart,
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-18T20:00:00Z", baseline),
                snapshotWithMembers("2026-07-19T19:59:00Z", complete),
            ),
            overrides = listOf(
                WeeklyCellOverride(
                    uid = 1,
                    periodStart = periodStart,
                    gameDay = periodStart,
                    meritDelta = null,
                    scoreDelta = null,
                    attempts = null,
                    attended = null,
                    dailyPatrol = true,
                ),
            ),
            asOf = Instant.parse("2026-07-19T20:01:00Z"),
        )

        val cell = report.members.single().days.first()
        assertEquals(3_342L, cell.meritDelta)
        assertEquals(31_635L, cell.scoreDelta)
        assertEquals(3, cell.attempts)
        assertEquals(true, cell.attended)
        assertEquals(true, cell.dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cell.meritCertainty)
        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(MetricCertainty.EXACT, cell.attemptsCertainty)
    }

    @Test
    fun patrolAfterMetricSnapshotDoesNotDoubleCountAnIncludedReward() {
        val periodStart = LocalDate.of(2026, 7, 19)
        val baseline = member(uid = 1, name = "Patrol after snapshot", weekly = 0, score = 0)
            .copy(totalMerit = 10_000, totalScore = 20_000)
        val complete = baseline.copy(totalMerit = 13_342, totalScore = 51_635)
        val report = WeeklyReportBuilder.build(
            referenceDay = periodStart,
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-18T20:00:00Z", baseline),
                snapshotWithMembers("2026-07-19T19:58:00Z", complete),
            ),
            dailyPatrolFacts = listOf(
                DailyPatrolFact(1, Instant.parse("2026-07-19T19:59:00Z")),
            ),
            asOf = Instant.parse("2026-07-19T20:01:00Z"),
        )

        val cell = report.members.single().days.first()
        assertEquals(3_342L, cell.meritDelta)
        assertEquals(31_635L, cell.scoreDelta)
        assertEquals(3, cell.attempts)
        assertEquals(true, cell.attended)
        assertEquals(true, cell.dailyPatrol)
        assertEquals(MetricCertainty.EXACT, cell.meritCertainty)
        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(MetricCertainty.EXACT, cell.attemptsCertainty)
    }

    @Test
    fun patrolAfterMetricSnapshotKeepsMeritLowerBoundBeforeDailyAttemptCap() {
        val periodStart = LocalDate.of(2026, 7, 19)
        val baseline = member(uid = 1, name = "Open day", weekly = 0, score = 0)
            .copy(totalMerit = 10_000, totalScore = 20_000)
        val partial = baseline.copy(totalMerit = 12_258, totalScore = 41_090)
        val report = WeeklyReportBuilder.build(
            referenceDay = periodStart,
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-18T20:00:00Z", baseline),
                snapshotWithMembers("2026-07-19T19:58:00Z", partial),
            ),
            dailyPatrolFacts = listOf(
                DailyPatrolFact(1, Instant.parse("2026-07-19T19:59:00Z")),
            ),
            asOf = Instant.parse("2026-07-19T19:59:30Z"),
        )

        val cell = report.members.single().days.first()
        assertEquals(2_258L, cell.meritDelta)
        assertEquals(21_090L, cell.scoreDelta)
        assertEquals(2, cell.attempts)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.meritCertainty)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.scoreCertainty)
        assertEquals(MetricCertainty.LOWER_BOUND, cell.attemptsCertainty)
    }

    @Test
    fun fiveAmBoundaryClosesThePrecedingGunsmokeDay() {
        val day = LocalDate.of(2026, 7, 19)
        val baseline = member(uid = 1, name = "Boundary", weekly = 0, score = 0)
            .copy(totalMerit = 10_000, totalScore = 20_000)
        val closing = baseline.copy(totalMerit = 12_258, totalScore = 41_090)
        val report = WeeklyReportBuilder.build(
            referenceDay = day,
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-18T20:00:00Z", baseline),
                snapshotWithMembers("2026-07-19T20:00:00Z", closing),
            ),
            asOf = Instant.parse("2026-07-19T20:00:01Z"),
        )

        val cell = report.members.single().days.first()
        assertTrue(cell.hasClosingBoundary)
        assertEquals(2_258L, cell.meritDelta)
        assertEquals(21_090L, cell.scoreDelta)
        assertEquals(2, cell.attempts)
        assertEquals(MetricCertainty.EXACT, cell.meritCertainty)
        assertEquals(MetricCertainty.EXACT, cell.scoreCertainty)
        assertEquals(MetricCertainty.EXACT, cell.attemptsCertainty)
    }

    @Test
    fun patrolRewardEvidenceCompletesStandardDayWithoutAClosingSnapshot() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 28),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-28T03:00:00Z",
                    member(uid = 1, name = "Patrol", weekly = 90, score = 0),
                ),
            ),
            dailyPatrolFacts = listOf(
                DailyPatrolFact(1, Instant.parse("2026-07-28T00:00:00Z")),
            ),
        )

        val tuesday = report.members.single().days[2]
        assertEquals(90L, tuesday.meritDelta)
        assertEquals(true, tuesday.attended)
        assertEquals(true, tuesday.dailyPatrol)
        assertEquals(DailyEvidence.ATTRIBUTED, tuesday.evidence)
    }

    @Test
    fun patrolActivityDoesNotFabricateGunsmokeScoreOrAttempts() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 22),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-22T03:00:00Z",
                    member(uid = 1, name = "Patrol", weekly = 90, score = 0),
                ),
            ),
            dailyPatrolFacts = listOf(
                DailyPatrolFact(1, Instant.parse("2026-07-22T00:00:00Z")),
            ),
        )

        val wednesday = report.members.single().days[3]
        assertEquals(90L, wednesday.meritDelta)
        assertNull(wednesday.scoreDelta)
        assertNull(wednesday.attempts)
        assertEquals(true, wednesday.dailyPatrol)
        assertEquals(DailyEvidence.PARTIAL_DAY, wednesday.evidence)
    }

    @Test
    fun exactUpdatesPatrolFactFinalizesOtherwiseExactGunsmokeMerit() {
        val baseline = member(uid = 1, name = "Patrol", weekly = 0, score = 0)
            .copy(totalMerit = 10_000, totalScore = 20_000)
        val complete = baseline.copy(
            weeklyMerit = 3_342,
            totalMerit = 13_342,
            totalScore = 51_635,
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 19),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-18T20:00:00Z", baseline),
                snapshotWithMembers("2026-07-19T19:59:00Z", complete),
            ),
            dailyPatrolFacts = listOf(
                DailyPatrolFact(1, Instant.parse("2026-07-19T00:00:00Z")),
            ),
            asOf = Instant.parse("2026-07-19T20:01:00Z"),
        )

        val sunday = report.members.single().days.first()
        assertEquals(3_342L, sunday.meritDelta)
        assertEquals(31_635L, sunday.scoreDelta)
        assertEquals(3, sunday.attempts)
        assertEquals(true, sunday.attended)
        assertEquals(true, sunday.dailyPatrol)
        assertEquals(MetricCertainty.EXACT, sunday.meritCertainty)
        assertEquals(MetricCertainty.EXACT, sunday.scoreCertainty)
        assertEquals(MetricCertainty.EXACT, sunday.attemptsCertainty)
    }

    @Test
    fun exactCounterArithmeticProvesPatrolWithoutAnUpdatesPacket() {
        val baseline = member(uid = 1, name = "No update", weekly = 0, score = 0)
            .copy(totalMerit = 10_000, totalScore = 20_000)
        val complete = baseline.copy(
            weeklyMerit = 3_342,
            totalMerit = 13_342,
            totalScore = 51_635,
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 19),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-18T20:00:00Z", baseline),
                snapshotWithMembers("2026-07-19T19:59:00Z", complete),
            ),
            asOf = Instant.parse("2026-07-19T20:01:00Z"),
        )

        val sunday = report.members.single().days.first()
        assertEquals(3, sunday.attempts)
        assertEquals(true, sunday.attended)
        assertEquals(true, sunday.dailyPatrol)
        assertEquals(MetricCertainty.EXACT, sunday.meritCertainty)
    }

    @Test
    fun julyNineteenthNeedsThePreResetAnchorToProvePatrol() {
        val julyEighteenth = SnapshotMember(
            uid = 1,
            name = "Boundary member",
            level = 60,
            weeklyMerit = 540,
            totalMerit = 430_307,
            highScore = 10_425,
            totalScore = 218_925,
            lastLogin = Instant.parse("2026-07-18T19:11:22Z").epochSecond,
        )
        val julyNineteenth = julyEighteenth.copy(
            weeklyMerit = 3_631,
            totalMerit = 433_398,
            highScore = 10_545,
            totalScore = 29_127,
            lastLogin = Instant.parse("2026-07-19T19:29:33Z").epochSecond,
        )

        val withoutAnchor = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 19),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-19T19:29:33Z", julyNineteenth),
            ),
            asOf = Instant.parse("2026-07-19T19:30:00Z"),
        ).members.single().days.first()
        val withAnchor = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 19),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-18T19:11:22Z", julyEighteenth),
                snapshotWithMembers("2026-07-19T19:29:33Z", julyNineteenth),
            ),
            asOf = Instant.parse("2026-07-19T19:30:00Z"),
        ).members.single().days.first()
        val withSubCapAnchor = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 19),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-18T19:11:22Z",
                    julyEighteenth.copy(weeklyMerit = 450),
                ),
                snapshotWithMembers(
                    "2026-07-19T19:29:33Z",
                    julyNineteenth.copy(weeklyMerit = 3_541),
                ),
            ),
            asOf = Instant.parse("2026-07-19T19:30:00Z"),
        ).members.single().days.first()

        assertNull(withoutAnchor.dailyPatrol)
        assertEquals(MetricCertainty.LOWER_BOUND, withoutAnchor.meritCertainty)
        assertNull(withSubCapAnchor.dailyPatrol)
        assertEquals(MetricCertainty.LOWER_BOUND, withSubCapAnchor.meritCertainty)
        assertEquals(3_091L, withAnchor.meritDelta)
        assertEquals(29_127L, withAnchor.scoreDelta)
        assertEquals(3, withAnchor.attempts)
        assertEquals(true, withAnchor.attended)
        assertEquals(true, withAnchor.dailyPatrol)
        assertEquals(MetricCertainty.EXACT, withAnchor.meritCertainty)
        assertEquals(MetricCertainty.EXACT, withAnchor.scoreCertainty)
        assertEquals(MetricCertainty.EXACT, withAnchor.attemptsCertainty)
    }

    @Test
    fun completeSevenDaySequencesResolveEverySharedActivityState() {
        fun sheetMember(
            uid: Long,
            name: String,
            weeklyMerit: Long,
            totalMerit: Long,
            totalScore: Long,
            time: String,
        ) = SnapshotMember(
            uid = uid,
            name = name,
            level = 60,
            weeklyMerit = weeklyMerit,
            totalMerit = totalMerit,
            highScore = 10_545,
            totalScore = totalScore,
            lastLogin = Instant.parse(time).epochSecond,
        )
        fun sheetSnapshot(
            time: String,
            weeklyMerit: Long,
            firstTotalMerit: Long,
            firstTotalScore: Long,
        ) = snapshotWithMembers(
            time,
            sheetMember(40_630, "Kim Duhan", weeklyMerit, firstTotalMerit, firstTotalScore, time),
            sheetMember(
                uid = 99_999,
                name = "Independent member",
                weeklyMerit = weeklyMerit,
                totalMerit = firstTotalMerit + 50_000,
                totalScore = firstTotalScore,
                time = time,
            ),
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 25),
            zoneId = zone,
            snapshots = listOf(
                sheetSnapshot("2026-07-19T19:29:33Z", 3_882, 436_467, 31_635),
                sheetSnapshot("2026-07-20T19:53:20Z", 3_342, 439_809, 63_270),
                sheetSnapshot("2026-07-21T19:11:09Z", 6_684, 443_151, 94_905),
                sheetSnapshot("2026-07-22T15:06:39Z", 10_026, 446_493, 126_540),
                sheetSnapshot("2026-07-23T15:46:30Z", 13_368, 449_835, 158_175),
                sheetSnapshot("2026-07-24T15:59:38Z", 16_710, 453_177, 189_810),
                sheetSnapshot("2026-07-25T17:02:17Z", 20_052, 456_519, 221_445),
            ),
            dailyPatrolFacts = listOf(
                DailyPatrolFact(40_630, Instant.parse("2026-07-19T00:38:37Z")),
                DailyPatrolFact(99_999, Instant.parse("2026-07-19T00:38:37Z")),
            ),
            asOf = Instant.parse("2026-07-25T17:03:00Z"),
        )

        assertEquals(setOf(40_630L, 99_999L), report.members.map { it.uid }.toSet())
        report.members.forEach { row ->
            assertEquals(List(7) { 3_342L }, row.days.map { it.meritDelta })
            assertEquals(List(7) { 31_635L }, row.days.map { it.scoreDelta })
            assertEquals(List(7) { 3 }, row.days.map { it.attempts })
            assertTrue(row.days.all { it.meritCertainty == MetricCertainty.EXACT })
            assertTrue(row.days.all { it.scoreCertainty == MetricCertainty.EXACT })
            assertTrue(row.days.all { it.attemptsCertainty == MetricCertainty.EXACT })
            assertTrue(row.days.all { it.attended == true })
            assertTrue(row.days.all { it.dailyPatrol == true })
            assertTrue(row.days.all { day ->
                !WeeklyMetricPresentation.format(day.meritDelta, day.meritCertainty)
                    .startsWith("\u2265")
            })
            assertEquals(23_394L, row.totalMerit)
            assertEquals(MetricCertainty.EXACT, row.totalMeritCertainty)
            assertEquals(221_445L, row.totalScore)
            assertEquals(MetricCertainty.EXACT, row.totalScoreCertainty)
            assertEquals(21, row.totalAttempts)
            assertEquals(MetricCertainty.EXACT, row.totalAttemptsCertainty)
            assertEquals(7, row.loginDays)
            assertEquals(MetricCertainty.EXACT, row.loginDaysCertainty)
            assertEquals(7, row.patrolDays)
            assertEquals(MetricCertainty.EXACT, row.patrolDaysCertainty)
        }
    }

    @Test
    fun skippedGunsmokeDayKeepsWeeklyLoginAndPatrolTotalsUnknown() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 22),
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-19T20:00:00Z", weekly = 90, score = 100),
                snapshot("2026-07-21T19:59:00Z", weekly = 270, score = 300),
            ),
        )

        val member = report.members.single()
        assertEquals(1, member.loginDays)
        assertEquals(MetricCertainty.LOWER_BOUND, member.loginDaysCertainty)
        assertNull(member.patrolDays)
        assertTrue(member.hasUnknownGunsmokeActivityTotals)
    }

    @Test
    fun lastLoginUsesTheFiveAmGameDayBoundary() {
        val beforeReset = member(
            uid = 1,
            name = "Before",
            weekly = 0,
            score = 0,
            lastLogin = "2026-07-26T19:59:59Z",
        )
        val atReset = member(
            uid = 2,
            name = "At",
            weekly = 50,
            score = 0,
            lastLogin = "2026-07-26T20:00:00Z",
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-27T00:00:00Z", beforeReset, atReset),
            ),
            asOf = Instant.parse("2026-07-27T01:00:00Z"),
        )

        val sunday = report.members.single { it.uid == 1L }.days[0]
        val mondayBefore = report.members.single { it.uid == 1L }.days[1]
        val mondayAt = report.members.single { it.uid == 2L }.days[1]
        assertTrue(sunday.hasLoginFact)
        assertTrue(!mondayBefore.hasLoginFact)
        assertTrue(mondayAt.hasLoginFact)
        assertEquals(true, mondayAt.attended)
        assertEquals(50L, mondayAt.meritDelta)
        assertEquals(DailyEvidence.PARTIAL_DAY, mondayAt.evidence)
    }

    @Test
    fun aCurrentDayLoginFinalizesThePreviousCounterPrefix() {
        val monday = member(
            uid = 1,
            name = "Constraint",
            weekly = 0,
            score = 0,
            lastLogin = "2026-07-26T19:00:00Z",
        )
        val tuesday = monday.copy(
            weeklyMerit = 90,
            totalMerit = 90,
            lastLogin = Instant.parse("2026-07-27T20:10:00Z").epochSecond,
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 28),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-26T20:10:00Z", monday),
                snapshotWithMembers("2026-07-27T21:00:00Z", tuesday),
            ),
            asOf = Instant.parse("2026-07-28T10:00:00Z"),
        )

        val cells = report.members.single().days
        assertEquals(0L, cells[1].meritDelta)
        assertEquals(90L, cells[2].meritDelta)
        assertEquals(DailyEvidence.ATTRIBUTED, cells[1].evidence)
        assertEquals(DailyEvidence.ATTRIBUTED, cells[2].evidence)
    }

    @Test
    fun lastLoginNarrowsButDoesNotInventAClueForAmbiguousDays() {
        val tuesday = member(
            uid = 1,
            name = "Ambiguous",
            weekly = 140,
            score = 0,
            lastLogin = "2026-07-27T20:10:00Z",
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 28),
            zoneId = zone,
            snapshots = listOf(snapshotWithMembers("2026-07-28T00:00:00Z", tuesday)),
            asOf = Instant.parse("2026-07-28T20:00:00Z"),
        )

        val cells = report.members.single().days
        assertEquals(listOf(null, null), cells.drop(1).take(2).map { it.meritDelta })
        assertTrue(cells.drop(1).take(2).all { it.evidence == DailyEvidence.SPARSE_INFERRED })
        assertEquals(true, cells[2].attended)
        assertNull(cells[2].dailyPatrol)
    }

    @Test
    fun openStandardDayShowsFiftyAsALowerBoundButNinetyAsTheCap() {
        val fifty = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-27T00:00:00Z",
                    member(
                        uid = 1,
                        name = "Fifty",
                        weekly = 50,
                        score = 0,
                        lastLogin = "2026-07-26T21:00:00Z",
                    ),
                ),
            ),
            asOf = Instant.parse("2026-07-27T01:00:00Z"),
        ).members.single().days[1]
        val ninety = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 27),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-27T00:00:00Z",
                    member(
                        uid = 1,
                        name = "Ninety",
                        weekly = 90,
                        score = 0,
                        lastLogin = "2026-07-26T21:00:00Z",
                    ),
                ),
            ),
            asOf = Instant.parse("2026-07-27T01:00:00Z"),
        ).members.single().days[1]

        assertEquals(50L, fifty.meritDelta)
        assertEquals(DailyEvidence.PARTIAL_DAY, fifty.evidence)
        assertEquals(90L, ninety.meritDelta)
        assertEquals(DailyEvidence.ATTRIBUTED, ninety.evidence)
    }

    @Test
    fun reportedThreeDayPatternStaysAmbiguousWithoutPatrolEvidence() {
        val monday = member(
            uid = 1,
            name = "Three days",
            weekly = 50,
            score = 0,
            lastLogin = "2026-07-26T22:00:00Z",
        )
        val tuesday = monday.copy(
            weeklyMerit = 140,
            totalMerit = 140,
            lastLogin = Instant.parse("2026-07-27T22:00:00Z").epochSecond,
        )
        val wednesday = tuesday.copy(
            weeklyMerit = 230,
            totalMerit = 230,
            lastLogin = Instant.parse("2026-07-28T22:00:00Z").epochSecond,
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 29),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-27T00:00:00Z", monday),
                snapshotWithMembers("2026-07-28T00:00:00Z", tuesday),
                snapshotWithMembers("2026-07-29T00:00:00Z", wednesday),
            ),
            asOf = Instant.parse("2026-07-29T01:00:00Z"),
        )

        val cells = report.members.single().days
        assertEquals(listOf(null, null, 50L), cells.drop(1).take(3).map { it.meritDelta })
        assertEquals(DailyEvidence.SPARSE_INFERRED, cells[1].evidence)
        assertEquals(DailyEvidence.SPARSE_INFERRED, cells[2].evidence)
        assertEquals(DailyEvidence.PARTIAL_DAY, cells[3].evidence)
        assertEquals(true, cells[3].attended)
        assertNull(cells[3].dailyPatrol)
    }

    @Test
    fun kindEightMakesOnlyTheStandardDayCapExact() {
        val monday = member(
            uid = 1,
            name = "Patrol proof",
            weekly = 50,
            score = 0,
            lastLogin = "2026-07-26T22:00:00Z",
        )
        val tuesday = monday.copy(
            weeklyMerit = 140,
            totalMerit = 140,
            lastLogin = Instant.parse("2026-07-27T22:00:00Z").epochSecond,
        )
        val wednesday = tuesday.copy(
            weeklyMerit = 230,
            totalMerit = 230,
            lastLogin = Instant.parse("2026-07-28T22:00:00Z").epochSecond,
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 29),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-27T00:00:00Z", monday),
                snapshotWithMembers("2026-07-28T00:00:00Z", tuesday),
                snapshotWithMembers("2026-07-29T00:00:00Z", wednesday),
            ),
            dailyPatrolFacts = listOf(
                DailyPatrolFact(1, Instant.parse("2026-07-28T23:00:00Z")),
            ),
            asOf = Instant.parse("2026-07-29T01:00:00Z"),
        )

        val cells = report.members.single().days
        assertEquals(listOf(null, null, 90L), cells.drop(1).take(3).map { it.meritDelta })
        assertTrue(cells.drop(1).take(2).all { it.evidence == DailyEvidence.SPARSE_INFERRED })
        assertEquals(DailyEvidence.ATTRIBUTED, cells[3].evidence)
        assertEquals(true, cells[3].dailyPatrol)
    }

    @Test
    fun gunsmokeLoginAndPatrolFactsStayConservative() {
        val loginOnly = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 22),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-22T03:00:00Z",
                    member(
                        uid = 1,
                        name = "Login",
                        weekly = 50,
                        score = 0,
                        lastLogin = "2026-07-21T22:00:00Z",
                    ),
                ),
            ),
        ).members.single().days[3]
        val patrol = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 22),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers(
                    "2026-07-22T03:00:00Z",
                    member(
                        uid = 1,
                        name = "Patrol",
                        weekly = 90,
                        score = 0,
                        lastLogin = "2026-07-21T22:00:00Z",
                    ),
                ),
            ),
            dailyPatrolFacts = listOf(
                DailyPatrolFact(1, Instant.parse("2026-07-22T00:00:00Z")),
            ),
        ).members.single().days[3]

        assertEquals(50L, loginOnly.meritDelta)
        assertEquals(true, loginOnly.attended)
        assertNull(loginOnly.dailyPatrol)
        assertEquals(DailyEvidence.PARTIAL_DAY, loginOnly.evidence)
        assertEquals(90L, patrol.meritDelta)
        assertEquals(true, patrol.attended)
        assertEquals(true, patrol.dailyPatrol)
        assertNull(patrol.scoreDelta)
        assertNull(patrol.attempts)
        assertEquals(DailyEvidence.PARTIAL_DAY, patrol.evidence)
    }

    @Test
    fun postTwoAmFinalSaturdayCaptureFinalizesScoreButNotTheGameDay() {
        val baseline = member(uid = 1, name = "Final score", weekly = 1_000, score = 10_000)
            .copy(totalMerit = 20_000)
        val final = baseline.copy(
            weeklyMerit = 1_120,
            totalMerit = 20_120,
            totalScore = 10_300,
        )
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 25),
            zoneId = zone,
            snapshots = listOf(
                snapshotWithMembers("2026-07-24T19:59:00Z", baseline),
                snapshotWithMembers("2026-07-25T17:02:00Z", final),
            ),
        )

        val member = report.members.single()
        val saturday = member.days.last()
        assertTrue(member.hasFinalGunsmokeScore)
        assertEquals(10_300L, member.totalScore)
        assertTrue(saturday.hasFinalGunsmokeScore)
        assertNull(saturday.scoreDelta)
        assertEquals(DailyEvidence.INCOMPLETE_BOUNDARY, saturday.evidence)
    }

    @Test
    fun preTwoAmFinalSaturdayCaptureLeavesScoreOpen() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 25),
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-24T19:59:00Z", weekly = 1_000, score = 10_000),
                snapshot("2026-07-25T16:59:59Z", weekly = 1_120, score = 10_300),
            ),
        )

        val member = report.members.single()
        assertTrue(!member.hasFinalGunsmokeScore)
        assertTrue(!member.days.last().hasFinalGunsmokeScore)
    }

    @Test
    fun finalSaturdayTotalIsConfirmedWithoutInventingADailyDelta() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 25),
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-25T17:02:17Z", weekly = 20_142, score = 221_445),
            ),
        )

        val member = report.members.single()
        val saturday = member.days.last()
        assertTrue(member.hasFinalGunsmokeScore)
        assertEquals(221_445L, member.totalScore)
        assertTrue(saturday.hasFinalGunsmokeScore)
        assertNull(saturday.scoreDelta)
        assertNull(saturday.attempts)
        assertEquals(DailyEvidence.INCOMPLETE_BOUNDARY, saturday.evidence)
    }

    @Test
    fun nearFiveAmCaptureDoesNotBecomeTheNextDaysGunsmokeOpeningBoundary() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 21),
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-20T19:53:20Z", weekly = 2_518, score = 46_903),
                snapshot("2026-07-21T19:11:09Z", weekly = 5_019, score = 70_129),
            ),
        )

        val tuesday = report.members.single().days[2]
        assertNull(tuesday.meritDelta)
        assertNull(tuesday.scoreDelta)
        assertNull(tuesday.attempts)
        assertEquals(MetricCertainty.UNKNOWN, tuesday.meritCertainty)
        assertEquals(MetricCertainty.UNKNOWN, tuesday.scoreCertainty)
        assertEquals(MetricCertainty.UNKNOWN, tuesday.attemptsCertainty)
    }

    private fun snapshot(time: String, weekly: Long, score: Long) =
        PlatoonSnapshot(
            id = 0,
            capturedAt = Instant.parse(time),
            members = listOf(SnapshotMember(1, "One", 60, weekly, weekly, 0, score, 0)),
        )

    private fun snapshotWithMembers(time: String, vararg members: SnapshotMember) =
        PlatoonSnapshot(0, Instant.parse(time), members.toList())

    private fun exactEvent(
        id: Long,
        uid: Long,
        type: MemberEventType,
        occurredAt: String,
        name: String,
    ) = MemberEvent(
        id = id,
        uid = uid,
        type = type,
        occurredAt = Instant.parse(occurredAt),
        eventDate = null,
        timeKnown = true,
        observedAt = Instant.parse(occurredAt),
        precision = EvidencePrecision.EXACT,
        source = EvidenceSource.GAME_UPDATES,
        note = name,
    )

    private fun member(
        uid: Long,
        name: String,
        weekly: Long,
        score: Long,
        lastLogin: String? = null,
    ) = SnapshotMember(
        uid,
        name,
        60,
        weekly,
        weekly,
        0,
        score,
        lastLogin?.let(Instant::parse)?.epochSecond ?: 0,
    )
}
