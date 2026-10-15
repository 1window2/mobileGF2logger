package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonMemberCsvTest {
    @Test
    fun exportsSelectedMemberStatusAndLatestMembershipPeriod() {
        val status = MemberStatus(
            uid = 7,
            name = "Leader, One",
            level = 60,
            isActive = false,
            firstSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
            lastSeenAt = Instant.parse("2026-07-21T00:00:00Z"),
            note = "former leader",
            membershipPeriods = listOf(
                MembershipPeriod(
                    id = 1,
                    uid = 7,
                    joinedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    leftAt = Instant.parse("2026-07-21T00:00:00Z"),
                    joinedDate = LocalDate.parse("2026-01-01"),
                    leftDate = LocalDate.parse("2026-07-21"),
                    joinedTimeKnown = true,
                    leftTimeKnown = true,
                    joinedPrecision = EvidencePrecision.MANUAL,
                    leftPrecision = EvidencePrecision.MANUAL,
                    joinedSource = EvidenceSource.MANUAL,
                    leftSource = EvidenceSource.MANUAL,
                    note = "",
                ),
            ),
        )

        val csv = PlatoonMemberCsv.format(
            listOf(status),
            latestMembers = emptyMap(),
            zoneId = ZoneId.of("Asia/Seoul"),
        )

        assertTrue(csv.contains("\"Leader, One\",WITHDRAWN"))
        assertTrue(csv.contains("2026-01-01 09:00:00,2026-07-21 09:00:00"))
    }

    @Test
    fun exportsTheCurrentPeriodOfARejoinedMemberAndTheLatestPeriodOfAWithdrawnMember() {
        val first = period(id = 1, joined = "2026-01-01T00:00:00Z", left = "2026-02-01T00:00:00Z")
        val second = period(id = 2, joined = "2026-03-01T00:00:00Z", left = null)
        val unknownStart = period(id = 3, joined = null, left = "2025-12-01T00:00:00Z")
        val rejoined = status(uid = 10, active = true, periods = listOf(first, second, unknownStart))
        val withdrawn = status(
            uid = 11,
            active = false,
            periods = listOf(
                first,
                period(id = 4, joined = "2026-03-01T00:00:00Z", left = "2026-04-01T00:00:00Z"),
                unknownStart,
            ),
        )

        val rows = PlatoonMemberCsv.format(
            listOf(rejoined, withdrawn),
            latestMembers = emptyMap(),
            zoneId = ZoneId.of("UTC"),
        ).lines()

        assertTrue(rows[1].endsWith(",2026-03-01 00:00:00,,"))
        assertTrue(rows[2].endsWith(",2026-03-01 00:00:00,2026-04-01 00:00:00,"))
        assertEquals(second, rejoined.latestMembershipPeriod())
    }

    @Test
    fun exportsDateOnlyManualBoundariesWithoutInventingMidnightTimes() {
        val status = MemberStatus(
            uid = 8,
            name = "Date only",
            level = 60,
            isActive = false,
            firstSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
            lastSeenAt = Instant.parse("2026-07-21T00:00:00Z"),
            note = "",
            membershipPeriods = listOf(
                MembershipPeriod(
                    id = 2,
                    uid = 8,
                    joinedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    leftAt = Instant.parse("2026-07-21T00:00:00Z"),
                    joinedDate = LocalDate.parse("2026-01-01"),
                    leftDate = LocalDate.parse("2026-07-21"),
                    joinedTimeKnown = false,
                    leftTimeKnown = false,
                    joinedPrecision = EvidencePrecision.MANUAL,
                    leftPrecision = EvidencePrecision.MANUAL,
                    joinedSource = EvidenceSource.MANUAL,
                    leftSource = EvidenceSource.MANUAL,
                    note = "",
                ),
            ),
        )

        val csv = PlatoonMemberCsv.format(
            listOf(status),
            latestMembers = emptyMap(),
            zoneId = ZoneId.of("Asia/Seoul"),
        )

        assertTrue(csv.contains("2026-01-01,2026-07-21"))
        assertTrue(!csv.contains("2026-01-01 09:00:00"))
    }

    @Test
    fun neutralizesFormulaLikeNamesAndNotesForSpreadsheetExport() {
        val status = MemberStatus(
            uid = 9,
            name = "=HYPERLINK(\"https://invalid\")",
            level = 60,
            isActive = true,
            firstSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
            lastSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
            note = "+cmd",
            membershipPeriods = emptyList(),
        )

        val csv = PlatoonMemberCsv.format(
            listOf(status),
            latestMembers = emptyMap(),
            zoneId = ZoneId.of("Asia/Seoul"),
        )

        assertTrue(csv.contains("\"'=HYPERLINK(\"\"https://invalid\"\")\""))
        assertTrue(csv.contains(",'+cmd"))
    }

    private fun status(uid: Long, active: Boolean, periods: List<MembershipPeriod>) = MemberStatus(
        uid = uid,
        name = "Member $uid",
        level = 60,
        isActive = active,
        firstSeenAt = Instant.parse("2025-12-01T00:00:00Z"),
        lastSeenAt = Instant.parse("2026-04-01T00:00:00Z"),
        note = "",
        membershipPeriods = periods,
    )

    private fun period(id: Long, joined: String?, left: String?) = MembershipPeriod(
        id = id,
        uid = 0,
        joinedAt = joined?.let(Instant::parse),
        leftAt = left?.let(Instant::parse),
        joinedDate = null,
        leftDate = null,
        joinedTimeKnown = true,
        leftTimeKnown = left?.let { true },
        joinedPrecision = EvidencePrecision.INFERRED,
        leftPrecision = left?.let { EvidencePrecision.INFERRED },
        joinedSource = EvidenceSource.SNAPSHOT,
        leftSource = left?.let { EvidenceSource.SNAPSHOT },
        note = "",
    )
}
