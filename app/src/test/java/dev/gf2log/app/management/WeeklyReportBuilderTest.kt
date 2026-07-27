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
        val newcomer = member(uid = 2, name = "Newcomer", weekly = 50, score = 0)
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
