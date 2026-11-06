package dev.gf2log.app.discord

import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.ParsedPacketTableParser
import dev.gf2log.protocol.ParsedPayloadTextFormatter
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalCsvPayloadTest {
    @Test
    fun theLargestAcceptedRosterWithTheWidestNamesStillUploads() {
        // Three UTF-8 bytes per name unit is the most a name within the UTF-16 limit can use.
        val name = "가".repeat(GuildMembersCsv.MAX_MEMBER_NAME_CHARS)
        val members = (1..GuildMembersCsv.MAX_ROSTER_MEMBERS).map { index ->
            GuildMember(
                uid = UInt.MAX_VALUE - index.toUInt(),
                name = name,
                level = UInt.MAX_VALUE,
                weeklyMerit = UInt.MAX_VALUE,
                totalMerit = UInt.MAX_VALUE,
                highScore = UInt.MAX_VALUE,
                totalScore = UInt.MAX_VALUE,
                lastLogin = UInt.MAX_VALUE,
            )
        }
        val history = ParsedPayloadTextFormatter.format(
            ParsedPayload(7, Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, true, GuildMembersData(members)),
            "2026-08-08T00:00:00Z",
        )

        val body = OriginalCsvPayload.extract(history)

        assertEquals(GuildMembersCsv.MAX_ROSTER_MEMBERS, ParsedPacketTableParser.parse(history)?.rows?.size)
        assertEquals(GuildMembersCsv.MAX_ROSTER_MEMBERS + 1, body.lines().size)
    }
}
