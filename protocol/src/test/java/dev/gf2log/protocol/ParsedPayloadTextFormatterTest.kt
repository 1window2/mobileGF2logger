package dev.gf2log.protocol

import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
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
