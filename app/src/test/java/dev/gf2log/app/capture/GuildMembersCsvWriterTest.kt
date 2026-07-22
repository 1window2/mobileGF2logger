package dev.gf2log.app.capture

import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuildMembersCsvWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun continuationPayloadsShareOneCsvAndLogTime() {
        val output = temporaryFolder.newFolder("guild-members")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09.987Z"), ZoneOffset.UTC)
        val writer = GuildMembersCsvWriter(output, clock)

        writer.accept(payload(messageId = 0, uid = 258857u, name = "카논", end = true))
        writer.accept(payload(messageId = 42, uid = 1025106u, name = "Crios", end = true))
        writer.close()

        val file = output.listFiles().orEmpty().single()
        assertEquals("gf2log_guildmembers_20260721T191109Z.csv", file.name)
        assertEquals(
            listOf(
                GuildMembersCsv.HEADER,
                "258857,카논,60,3750,313832,10398,51661,1784639347,2026-07-21T19:11:09Z",
                "1025106,Crios,60,3750,313832,10398,51661,1784639347,2026-07-21T19:11:09Z",
            ),
            file.readLines(Charsets.UTF_8),
        )
    }

    @Test
    fun completedBatchesWithinOneSecondUseDistinctFiles() {
        val output = temporaryFolder.newFolder("same-second-batches")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val writer = GuildMembersCsvWriter(output, clock)

        writer.accept(payload(messageId = 41, uid = 1u, name = "First", end = true))
        writer.accept(payload(messageId = 42, uid = 2u, name = "Second", end = true))
        writer.close()

        assertEquals(
            listOf(
                "gf2log_guildmembers_20260721T191109Z.csv",
                "gf2log_guildmembers_20260721T191109Z_2.csv",
            ),
            output.listFiles().orEmpty().map { it.name }.sorted(),
        )
    }

    @Test
    fun messageZeroBatchClosesWhenItsFlowEnds() {
        val output = temporaryFolder.newFolder("flow-ended-batches")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val writer = GuildMembersCsvWriter(output, clock)

        writer.accept(
            payload(messageId = 0, uid = 1u, name = "First", end = true),
            flowEnded = true,
        )
        writer.accept(payload(messageId = 42, uid = 2u, name = "Second", end = true))
        writer.close()

        val files = output.listFiles().orEmpty().sortedBy { it.name }
        assertEquals(2, files.size)
        assertEquals(
            listOf(
                GuildMembersCsv.HEADER,
                "1,First,60,3750,313832,10398,51661,1784639347,2026-07-21T19:11:09Z",
            ),
            files[0].readLines(Charsets.UTF_8),
        )
        assertEquals(
            listOf(
                GuildMembersCsv.HEADER,
                "2,Second,60,3750,313832,10398,51661,1784639347,2026-07-21T19:11:09Z",
            ),
            files[1].readLines(Charsets.UTF_8),
        )
    }

    private fun payload(messageId: Int, uid: UInt, name: String, end: Boolean): ParsedPayload =
        ParsedPayload(
            messageId = messageId,
            payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            isEndOfMessage = end,
            data = GuildMembersData(
                listOf(
                    GuildMember(
                        uid = uid,
                        name = name,
                        level = 60u,
                        weeklyMerit = 3750u,
                        totalMerit = 313832u,
                        highScore = 10398u,
                        totalScore = 51661u,
                        lastLogin = 1784639347u,
                    ),
                ),
            ),
        )
}
