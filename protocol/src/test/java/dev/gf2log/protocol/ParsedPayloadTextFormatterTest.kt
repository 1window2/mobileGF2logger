package dev.gf2log.protocol

import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonActivityEntry
import dev.gf2log.protocol.model.PlatoonActivitySummary
import dev.gf2log.protocol.model.PlatoonUpdateEntry
import dev.gf2log.protocol.model.PlatoonUpdateMember
import dev.gf2log.protocol.model.PlatoonUpdatesData
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
    fun guildPacketHistoryNeutralizesFormulaLikeNamesWithoutChangingRetainedRows() {
        val formulaMember = member(1u, "=HYPERLINK(\"https://invalid\")")
        val payload = ParsedPayload(
            messageId = 7,
            payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            isEndOfMessage = true,
            data = GuildMembersData(listOf(formulaMember)),
        )

        val history = ParsedPayloadTextFormatter.format(payload, "2026-08-08T00:00:00Z")

        assertTrue(history.contains("1,\"'=HYPERLINK(\"\"https://invalid\"\")\""))
        assertTrue(GuildMembersCsv.row(formulaMember, "2026-08-08T00:00:00Z").contains(",\"=HYPERLINK"))
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

    @Test
    fun platoonUpdatesPacketFormatsExactMemberEvidence() {
        val payload = ParsedPayload(
            messageId = 9,
            payloadType = Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
            isEndOfMessage = true,
            data = PlatoonUpdatesData(
                listOf(
                    PlatoonUpdateEntry(
                        kind = 3u,
                        members = listOf(PlatoonUpdateMember(1u, 3_333_333u, "Name,WithComma")),
                        occurredAt = 1_700_300_000u,
                    ),
                ),
            ),
        )

        val text = ParsedPayloadTextFormatter.format(payload, "2026-07-29T00:00:00Z")

        assertTrue(text.contains("kind,occurredAt,memberIndex,role,uid,memberName"))
        assertTrue(text.contains("3,1700300000,0,1,3333333,\"Name,WithComma\""))
    }

    @Test
    fun packetHistoryNeutralizesFormulaLikeActivityAndUpdateNames() {
        val activity = ParsedPayload(
            messageId = 10,
            payloadType = Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
            isEndOfMessage = true,
            data = PlatoonActivityData(
                summaries = emptyList(),
                entries = listOf(PlatoonActivityEntry(1u, 123u, 802001u, "=1+1")),
            ),
        )
        val updates = ParsedPayload(
            messageId = 11,
            payloadType = Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
            isEndOfMessage = true,
            data = PlatoonUpdatesData(
                listOf(
                    PlatoonUpdateEntry(
                        kind = 3u,
                        members = listOf(PlatoonUpdateMember(1u, 42u, "\t@SUM(A1)")),
                        occurredAt = 123u,
                    ),
                ),
            ),
        )

        assertTrue(
            ParsedPayloadTextFormatter.format(activity, "2026-08-08T00:00:00Z")
                .contains("entry,,1,123,802001,,'=1+1"),
        )
        assertTrue(
            ParsedPayloadTextFormatter.format(updates, "2026-08-08T00:00:00Z")
                .contains("3,123,0,1,42,'\t@SUM(A1)"),
        )
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
