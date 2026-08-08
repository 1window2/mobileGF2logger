package dev.gf2log.protocol

import dev.gf2log.protocol.model.GuildMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuildMembersCsvTest {
    @Test
    fun guildMemberMatchesReferenceColumnOrder() {
        val member = GuildMember(
            uid = 123456u,
            name = "Test Leader",
            level = 60u,
            weeklyMerit = 120u,
            totalMerit = 4560u,
            highScore = 789u,
            totalScore = 1234u,
            lastLogin = 1700000000u,
        )

        assertEquals(
            "123456,Test Leader,60,120,4560,789,1234,1700000000,2026-01-01T00:00:00Z",
            GuildMembersCsv.row(member, "2026-01-01T00:00:00Z"),
        )
    }

    @Test
    fun namesAreCsvEscaped() {
        val member = GuildMember(1u, "A, \"B\"", 2u, 3u, 4u, 5u, 6u, 7u)

        assertEquals(
            "1,\"A, \"\"B\"\"\",2,3,4,5,6,7,2026-01-01T00:00:00Z",
            GuildMembersCsv.row(member, "2026-01-01T00:00:00Z"),
        )
    }

    @Test
    fun explicitSpreadsheetExportNeutralizesAFormulaNameWithoutChangingRetainedRows() {
        val member = GuildMember(1u, "=HYPERLINK(\"https://invalid\")", 2u, 3u, 4u, 5u, 6u, 7u)
        val retained = GuildMembersCsv.row(member, "2026-01-01T00:00:00Z")
        val snapshot = GuildMembersCsv.Snapshot(
            logTime = "2026-01-01T00:00:00Z",
            members = listOf(member),
        )

        assertEquals(
            "1,\"=HYPERLINK(\"\"https://invalid\"\")\",2,3,4,5,6,7,2026-01-01T00:00:00Z",
            retained,
        )
        assertEquals(
            "1,\"'=HYPERLINK(\"\"https://invalid\"\")\",2,3,4,5,6,7,2026-01-01T00:00:00Z",
            GuildMembersCsv.formatForSpreadsheet(snapshot).lineSequence().drop(1).first(),
        )
    }

    @Test
    fun parsesACompleteSnapshot() {
        val content = listOf(
            GuildMembersCsv.HEADER,
            "123456,\"Test, \"\"Leader\"\"\",60,120,4560,789,1234,1700000000,2026-01-01T00:00:00Z",
            "654321,Test Member,59,90,9000,500,2500,1700003600,2026-01-01T00:00:00Z",
        ).joinToString("\n")

        val parsed = GuildMembersCsv.parse(content)

        assertEquals("2026-01-01T00:00:00Z", parsed!!.logTime)
        assertEquals(2, parsed.members.size)
        assertEquals("Test, \"Leader\"", parsed.members.first().name)
        assertEquals(654321u, parsed.members.last().uid)
    }

    @Test
    fun rejectsMixedCaptureTimes() {
        val content = """
            ${GuildMembersCsv.HEADER}
            1,One,60,1,1,1,1,1,2026-07-21T19:11:09Z
            2,Two,60,1,1,1,1,1,2026-07-21T19:12:09Z
        """.trimIndent()

        assertNull(GuildMembersCsv.parse(content))
    }

    @Test
    fun trimsSpreadsheetWhitespaceFromScalarFieldsWithoutChangingTheName() {
        val content = listOf(
            GuildMembersCsv.HEADER,
            " 42 , Member Name , 60 , 90 , 120 , 300 , 400 , 500 , " +
                "2026-01-01T00:00:00Z ",
        ).joinToString("\n")

        val snapshot = GuildMembersCsv.parse(content)

        assertEquals("2026-01-01T00:00:00Z", snapshot?.logTime)
        assertEquals(" Member Name ", snapshot?.members?.single()?.name)
        assertEquals(42u, snapshot?.members?.single()?.uid)
    }

    @Test
    fun blankOptionalCountersMatchMissingProtobufScalarDefaults() {
        val content = """
            ${GuildMembersCsv.HEADER}
            42,New Member,60,,,,,500,2026-01-01T00:00:00Z
        """.trimIndent()

        val member = GuildMembersCsv.parse(content)?.members?.single()

        assertEquals(0u, member?.weeklyMerit)
        assertEquals(0u, member?.totalMerit)
        assertEquals(0u, member?.highScore)
        assertEquals(0u, member?.totalScore)
    }

    @Test
    fun malformedOptionalCounterIsRejected() {
        val content = """
            ${GuildMembersCsv.HEADER}
            42,Member,60,not-a-number,1,2,3,500,2026-01-01T00:00:00Z
        """.trimIndent()

        assertNull(GuildMembersCsv.parse(content))
    }
}
