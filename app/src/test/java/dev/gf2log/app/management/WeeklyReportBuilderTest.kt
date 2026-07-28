package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
        assertEquals(114L, report.members.single().days[2].meritDelta)
        assertEquals(25L, report.members.single().days[2].scoreDelta)
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
    fun sparseMultiDayDeltaIsDistributedInsteadOfAssignedOnlyToLatestDay() {
        val report = WeeklyReportBuilder.build(
            referenceDay = LocalDate.of(2026, 7, 28),
            zoneId = zone,
            snapshots = listOf(
                snapshot("2026-07-26T10:00:00Z", weekly = 0, score = 0),
                snapshot("2026-07-28T03:00:00Z", weekly = 180, score = 0),
            ),
        )

        val cells = report.members.single().days
        assertEquals(listOf(0L, 90L, 90L), cells.take(3).map { it.meritDelta })
        assertEquals(DailyEvidence.SPARSE_INFERRED, cells[0].evidence)
        assertTrue(cells.drop(1).take(2).all { it.evidence == DailyEvidence.ATTRIBUTED })
        assertTrue(report.hasIncompleteDailyEvidence)
    }

    @Test
    fun sparseStandardAggregateUsesMarkedMondayFirstAllocation() {
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
            listOf(90L, 50L, 0L),
            report.members.single().days
                .filter { it.gameDay in LocalDate.of(2026, 7, 28)..LocalDate.of(2026, 7, 30) }
                .map { it.meritDelta },
        )
        assertTrue(
            report.members.single().days
                .filter { it.meritDelta != null }
                .all { it.evidence == DailyEvidence.SPARSE_INFERRED },
        )
        assertTrue(report.hasIncompleteDailyEvidence)
    }

    @Test
    fun mondayResetSeparatesMondayMeritFromPreviousSunday() {
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

        assertEquals(90L, report.members.single().days.first().meritDelta)
        assertEquals(DailyEvidence.SPARSE_INFERRED, report.members.single().days.first().evidence)
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
    fun ambiguousWeeklyCounterUsesMarkedMondayFirstValues() {
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
        assertEquals(listOf(90L, 0L), cells.drop(1).take(2).map { it.meritDelta })
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
        assertEquals(false, cell.attended)
        assertEquals(true, cell.dailyPatrol)
        assertEquals(EvidencePrecision.MANUAL, cell.precision)
        assertEquals(42L, report.members.single().totalMerit)
    }

    private fun snapshot(time: String, weekly: Long, score: Long) =
        PlatoonSnapshot(
            id = 0,
            capturedAt = Instant.parse(time),
            members = listOf(SnapshotMember(1, "One", 60, weekly, weekly, 0, score, 0)),
        )

    private fun snapshotWithMembers(time: String, vararg members: SnapshotMember) =
        PlatoonSnapshot(0, Instant.parse(time), members.toList())

    private fun member(uid: Long, name: String, weekly: Long, score: Long) =
        SnapshotMember(uid, name, 60, weekly, weekly, 0, score, 0)
}
