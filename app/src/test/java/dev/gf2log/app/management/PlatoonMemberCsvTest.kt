package dev.gf2log.app.management

import java.time.Instant
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
}
