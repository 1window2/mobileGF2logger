package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonMemberCsvTest {
    @Test
    fun exportsSelectedMemberStatusAndLatestTenure() {
        val status = MemberStatus(
            uid = 7,
            name = "Leader, One",
            level = 60,
            isActive = false,
            firstSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
            lastSeenAt = Instant.parse("2026-07-21T00:00:00Z"),
            note = "former leader",
            tenures = listOf(
                MembershipTenure(
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
    fun exportsDateOnlyManualBoundariesWithoutInventingMidnightTimes() {
        val status = MemberStatus(
            uid = 8,
            name = "Date only",
            level = 60,
            isActive = false,
            firstSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
            lastSeenAt = Instant.parse("2026-07-21T00:00:00Z"),
            note = "",
            tenures = listOf(
                MembershipTenure(
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
}
