package dev.gf2log.app.management

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportHistoryCodecTest {
    @Test
    fun roundTripPreservesDisplayedWeeklyFactsAndIsDeterministic() {
        val start = LocalDate.of(2026, 7, 19)
        val days = List(7) { start.plusDays(it.toLong()) }
        val report = WeeklyReportBuilder.Report(
            periodStart = start,
            periodEnd = start.plusDays(6),
            isGunsmokeWeek = true,
            days = days,
            members = listOf(
                WeeklyReportBuilder.MemberRow(
                    uid = 42L,
                    name = "History member",
                    days = days.mapIndexed { index, day ->
                        WeeklyReportBuilder.DayCell(
                            gameDay = day,
                            meritDelta = if (index == 2) null else 90L + index,
                            scoreDelta = if (index == 2) null else index * 100L,
                            inference = null,
                            evidence = if (index == 2) {
                                DailyEvidence.SPARSE_INFERRED
                            } else {
                                DailyEvidence.ATTRIBUTED
                            },
                            hasDailyPatrolFact = index == 0,
                            hasLoginFact = index == 1,
                            hasFinalGunsmokeScore = index == 6,
                            isGunsmokeWeek = true,
                            metricObservedAt = Instant.parse("2026-07-20T00:00:00Z")
                                .plusSeconds(index.toLong()),
                            hasClosingBoundary = index < 6,
                            solvedAttempts = index.coerceAtMost(3),
                            solvedMeritCertainty = if (index == 2) {
                                MetricCertainty.UNKNOWN
                            } else {
                                MetricCertainty.EXACT
                            },
                            solvedScoreCertainty = MetricCertainty.LOWER_BOUND,
                            solvedAttemptsCertainty = MetricCertainty.EXACT,
                            solvedAttended = index != 2,
                            solvedDailyPatrol = index == 0,
                        )
                    },
                    totalMerit = 555L,
                    totalScore = 2_100L,
                    isGunsmokeWeek = true,
                    hasFinalGunsmokeScore = true,
                    resolvedGunsmokeTotals = WeeklyReportBuilder.ResolvedGunsmokeTotals(
                        merit = 555L,
                        meritCertainty = MetricCertainty.EXACT,
                        attempts = 15,
                        attemptsCertainty = MetricCertainty.EXACT,
                        loginDays = 6,
                        loginDaysCertainty = MetricCertainty.EXACT,
                        patrolDays = 5,
                        patrolDaysCertainty = MetricCertainty.LOWER_BOUND,
                    ),
                    gunsmokeAttemptsFloor = 14,
                ),
            ),
        )

        val revision = WeeklyTableRevision(
            report = report,
            membershipEvents = listOf(
                MemberEvent(
                    id = 7L,
                    uid = 42L,
                    type = MemberEventType.REJOINED,
                    occurredAt = Instant.parse("2026-07-20T04:00:00Z"),
                    eventDate = LocalDate.of(2026, 7, 20),
                    timeKnown = true,
                    observedAt = Instant.parse("2026-07-20T04:01:00Z"),
                    precision = EvidencePrecision.EXACT,
                    source = EvidenceSource.GAME_UPDATES,
                    note = "History member",
                ),
            ),
            notes = listOf(
                WeeklyNote(3L, start, start.plusDays(1), "Remember this", null, false),
            ),
            memberNamesByUid = mapOf(42L to "Custom history name"),
            memberPrivateNotesByUid = mapOf(42L to "Private at capture time"),
        )
        val first = WeeklyReportHistoryCodec.encode(revision)
        val second = WeeklyReportHistoryCodec.encode(revision)
        val decodedRevision = WeeklyReportHistoryCodec.decodeRevision(first.payload)
        val decoded = decodedRevision.report

        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(first.payload.contentEquals(second.payload))
        assertEquals(report.periodStart, decoded.periodStart)
        assertEquals(report.members.single().name, decoded.members.single().name)
        assertEquals(report.members.single().totalAttempts, decoded.members.single().totalAttempts)
        assertEquals(
            report.members.single().days.map { it.meritDelta },
            decoded.members.single().days.map { it.meritDelta },
        )
        assertEquals(
            report.members.single().days.map { it.attended },
            decoded.members.single().days.map { it.attended },
        )
        assertEquals(revision.membershipEvents, decodedRevision.membershipEvents)
        assertEquals(revision.notes, decodedRevision.notes)
        assertEquals(revision.memberNamesByUid, decodedRevision.memberNamesByUid)
        assertEquals(revision.memberPrivateNotesByUid, decodedRevision.memberPrivateNotesByUid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPayloadBeforeDecompression() {
        WeeklyReportHistoryCodec.decode(
            ByteArray(WeeklyReportHistoryCodec.MAX_PAYLOAD_BYTES + 1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCompressedPayloadThatExpandsPastTheBound() {
        val payload = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip ->
                gzip.write(ByteArray(4 * 1024 * 1024 + 1))
            }
            output.toByteArray()
        }

        WeeklyReportHistoryCodec.decode(payload)
    }

    @Test
    fun roundTripKeepsManualOverridesOnTheirCells() {
        val start = LocalDate.of(2026, 7, 26)
        val merit = WeeklyCellOverride(42L, start, start.plusDays(1), 90L, null, null, true, null)
        val patrol = WeeklyCellOverride(42L, start, start.plusDays(3), null, null, null, null, false)
        val report = standardReport(start) { day ->
            when (day) {
                merit.gameDay -> merit
                patrol.gameDay -> patrol
                else -> null
            }
        }

        val decoded = WeeklyReportHistoryCodec.decode(WeeklyReportHistoryCodec.encode(report).payload)

        val cells = decoded.members.single().days
        assertEquals(report.members.single().days.map { it.manualOverride }, cells.map { it.manualOverride })
        assertEquals(EvidencePrecision.MANUAL, cells[1].precision)
        assertEquals(report.members.single().days.map { it.observed }, cells.map { it.observed })
    }

    @Test
    fun aRevisionWithoutOverridesKeepsItsEarlierFormatAndFingerprint() {
        val start = LocalDate.of(2026, 7, 26)
        val encoded = WeeklyReportHistoryCodec.encode(standardReport(start) { null })

        val header = DataInputStream(GZIPInputStream(ByteArrayInputStream(encoded.payload))).use {
            it.readInt()
            it.readInt()
        }

        assertEquals(2, header)
        assertNull(
            WeeklyReportHistoryCodec.decode(encoded.payload).members.single().days
                .firstNotNullOfOrNull { it.manualOverride },
        )
    }

    private fun standardReport(
        start: LocalDate,
        overrideOn: (LocalDate) -> WeeklyCellOverride?,
    ): WeeklyReportBuilder.Report {
        val days = List(7) { start.plusDays(it.toLong()) }
        return WeeklyReportBuilder.Report(
            periodStart = start,
            periodEnd = start.plusDays(6),
            isGunsmokeWeek = false,
            days = days,
            members = listOf(
                WeeklyReportBuilder.MemberRow(
                    uid = 42L,
                    name = "Edited member",
                    days = days.map { day ->
                        val override = overrideOn(day)
                        WeeklyReportBuilder.DayCell(
                            gameDay = day,
                            meritDelta = override?.meritDelta,
                            scoreDelta = null,
                            inference = null,
                            evidence = DailyEvidence.NO_OBSERVATION,
                            manualOverride = override,
                        )
                    },
                    totalMerit = 90L,
                    totalScore = 0L,
                ),
            ),
        )
    }
}
