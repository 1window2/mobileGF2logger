package dev.gf2log.app.management

import dev.gf2log.protocol.GuildMembersCsv
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RosterCsvExportTest {
    @Test
    fun theStoredRosterExportsWithItsCaptureTimeAndEveryCounter() {
        val snapshot = PlatoonSnapshot(
            id = 3,
            capturedAt = Instant.parse("2026-08-03T12:34:56Z"),
            members = listOf(
                SnapshotMember(7, "Seven", 60, 90, 1_000, 100, 200, 1_784_489_372),
            ),
            sourceFile = "gf2log_platoonmembers_20260803T123456Z.csv",
        )

        val csv = RosterCsvExport.csvSnapshot(snapshot)

        assertEquals("2026-08-03T12:34:56Z", csv.logTime)
        val parsed = requireNotNull(GuildMembersCsv.parse(GuildMembersCsv.formatForSpreadsheet(csv)))
        assertEquals(csv, parsed)
        assertEquals(7u, parsed.members.single().uid)
        assertEquals(1_784_489_372u, parsed.members.single().lastLogin)
    }

    @Test
    fun theDefaultNameIsTheRetainedNameOrTheCaptureNamingScheme() {
        val capturedAt = Instant.parse("2026-08-03T12:34:56Z")
        val retained = PlatoonSnapshot(1, capturedAt, emptyList(), "import-20260803T123456Z-0123456789abcdef0123.csv")

        assertEquals(retained.sourceFile, RosterCsvExport.fileName(retained))
        assertEquals(
            "gf2log_platoonmembers_20260803T123456Z.csv",
            RosterCsvExport.fileName(retained.copy(sourceFile = null)),
        )
        assertEquals(
            "gf2log_platoonmembers_20260803T123456Z.csv",
            RosterCsvExport.fileName(retained.copy(sourceFile = "../escape.csv")),
        )
    }
}
