package dev.gf2log.protocol

import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonActivityEntry
import dev.gf2log.protocol.model.PlatoonActivitySummary
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsedPayloadTextFormatterTest {
    @Test
    fun guildPacketPreservesMemberOrder() {
        val payload = ParsedPayload(
            messageId = 7,
            payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            isEndOfMessage = true,
            data = GuildMembersData(
                listOf(
                    member(1u, "First"),
                    member(2u, "Second"),
                ),
            ),
        )

        val text = ParsedPayloadTextFormatter.format(payload, "2026-07-22T08:52:37Z")

        assertTrue(text.indexOf("1,First") < text.indexOf("2,Second"))
        assertTrue(text.contains(GuildMembersCsv.HEADER))
    }

    @Test
    fun platoonActivityPacketFormatsRawActionEvidence() {
        val payload = ParsedPayload(
            messageId = 8,
            payloadType = Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
            isEndOfMessage = true,
            data = PlatoonActivityData(
                summaries = listOf(PlatoonActivitySummary(7uL, 802001u, 123u, 1u)),
                entries = listOf(PlatoonActivityEntry(2u, 123u, 802001u, "Name,WithComma")),
            ),
        )

        val text = ParsedPayloadTextFormatter.format(payload, "2026-07-29T00:00:00Z")

        assertTrue(text.contains("summary,7,,123,802001,1,"))
        assertTrue(text.contains("entry,,2,123,802001,,\"Name,WithComma\""))
    }

    private fun member(uid: UInt, name: String) = GuildMember(
        uid = uid,
        name = name,
        level = 60u,
        weeklyMerit = 1u,
        totalMerit = 2u,
        highScore = 3u,
        totalScore = 4u,
        lastLogin = 5u,
    )
}
