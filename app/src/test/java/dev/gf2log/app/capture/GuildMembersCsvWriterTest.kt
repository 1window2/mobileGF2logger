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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        writer.accept(payload(messageId = 0, uid = 123456u, name = "First Member", end = true))
        writer.accept(payload(messageId = 42, uid = 654321u, name = "Second Member", end = true))
        writer.close()

        val file = output.listFiles().orEmpty().single()
        assertEquals("gf2log_platoonmembers_20260721T191109Z.csv", file.name)
        assertEquals(
            listOf(
                GuildMembersCsv.HEADER,
                "123456,First Member,60,120,4560,789,1234,1700000000,2026-07-21T19:11:09Z",
                "654321,Second Member,60,120,4560,789,1234,1700000000,2026-07-21T19:11:09Z",
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
                "gf2log_platoonmembers_20260721T191109Z.csv",
                "gf2log_platoonmembers_20260721T191109Z_2.csv",
            ),
            output.listFiles().orEmpty().map { it.name }.sorted(),
        )
    }

    @Test
    fun flowCloseDiscardsMessageZeroContinuationWithoutProtocolCompletion() {
        val output = temporaryFolder.newFolder("flow-ended-batches")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val completed = mutableListOf<GuildMembersCsvWriter.CompletedBatch>()
        val writer = GuildMembersCsvWriter(output, clock, onBatchClosed = completed::add)

        writer.accept(
            payload(messageId = 0, uid = 1u, name = "First", end = true),
            flowEnded = true,
        )
        writer.accept(payload(messageId = 42, uid = 2u, name = "Second", end = true))
        writer.close()

        val files = output.listFiles().orEmpty().sortedBy { it.name }
        assertEquals(1, files.size)
        assertEquals(1, completed.size)
        assertEquals(
            listOf(
                GuildMembersCsv.HEADER,
                "2,Second,60,120,4560,789,1234,1700000000,2026-07-21T19:11:09Z",
            ),
            files.single().readLines(Charsets.UTF_8),
        )
    }

    @Test
    fun completionCallbackReceivesTheWholeDeduplicatedBatch() {
        val output = temporaryFolder.newFolder("completed-batch")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val completed = mutableListOf<GuildMembersCsvWriter.CompletedBatch>()
        val writer = GuildMembersCsvWriter(output, clock, onBatchClosed = completed::add)

        writer.accept(payload(messageId = 0, uid = 1u, name = "Old", end = true))
        writer.accept(payload(messageId = 44, uid = 1u, name = "Current", end = true))

        assertEquals(1, completed.size)
        assertEquals("2026-07-21T19:11:09Z", completed.single().logTime)
        assertEquals("Current", completed.single().members.single().name)
        assertTrue(completed.single().file.exists())
        val parsed = requireNotNull(
            GuildMembersCsv.parse(completed.single().file.readText(Charsets.UTF_8)),
        )
        assertEquals(1, parsed.members.size)
        assertEquals("Current", parsed.members.single().name)
    }

    @Test
    fun incompleteBatchIsNotPublishedAsCsvUntilProtocolCompletion() {
        val output = temporaryFolder.newFolder("publish-on-completion")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val writer = GuildMembersCsvWriter(output, clock)

        val partial = writer.accept(payload(messageId = 0, uid = 1u, name = "First", end = false))

        assertEquals(null, partial)
        assertTrue(output.listFiles().orEmpty().none { it.extension == "csv" })
        assertEquals(1, output.listFiles().orEmpty().count { it.extension == "partial" })

        val completed = writer.accept(
            payload(messageId = 42, uid = 2u, name = "Second", end = true),
        )

        assertEquals("gf2log_platoonmembers_20260721T191109Z.csv", completed?.file?.name)
        assertEquals(2, completed?.rowCount)
        assertTrue(output.listFiles().orEmpty().none { it.extension == "partial" })
        assertEquals(1, output.listFiles().orEmpty().count { it.extension == "csv" })
    }

    @Test
    fun transientPublicationFailureRetriesImmediately() {
        val output = temporaryFolder.newFolder("publication-failure")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        var attempts = 0
        val writer = GuildMembersCsvWriter(
            outputDirectory = output,
            clock = clock,
            publisher = { working, final ->
                attempts += 1
                if (attempts == 1) error("simulated publication failure")
                assertTrue(working.renameTo(final))
            },
        )

        val completed = writer.accept(
            payload(messageId = 42, uid = 2u, name = "Second", end = true),
        )

        assertEquals(2, attempts)
        assertEquals(1, completed?.rowCount)
        assertTrue(output.listFiles().orEmpty().none { it.extension == "partial" })
        assertEquals(1, output.listFiles().orEmpty().count { it.extension == "csv" })
    }

    @Test
    fun permanentPublicationFailurePreservesStructuredIngestionOpportunity() {
        val output = temporaryFolder.newFolder("permanent-publication-failure")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val completed = mutableListOf<GuildMembersCsvWriter.CompletedBatch>()
        val writer = GuildMembersCsvWriter(
            outputDirectory = output,
            clock = clock,
            publisher = { _, _ -> error("simulated permanent publication failure") },
            onBatchClosed = completed::add,
        )

        assertTrue(
            runCatching {
                writer.accept(payload(messageId = 42, uid = 1u, name = "First", end = true))
            }.isFailure,
        )

        assertEquals(1, completed.size)
        assertEquals(1, output.listFiles().orEmpty().count { it.extension == "partial" })
        assertFalse(output.listFiles().orEmpty().any { it.extension == "csv" })
    }

    @Test
    fun callbackFailureLeavesPublishedCsvForImmediateRecovery() {
        val output = temporaryFolder.newFolder("callback-failure")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val writer = GuildMembersCsvWriter(
            outputDirectory = output,
            clock = clock,
            onBatchClosed = { error("simulated ingestion failure") },
        )

        assertTrue(
            runCatching {
                writer.accept(payload(messageId = 42, uid = 1u, name = "First", end = true))
            }.isFailure,
        )

        assertTrue(output.listFiles().orEmpty().none { it.extension == "partial" })
        assertEquals(1, output.listFiles().orEmpty().count { it.extension == "csv" })
    }

    @Test
    fun shutdownDiscardsAnUnterminatedContinuation() {
        val output = temporaryFolder.newFolder("incomplete-batch")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val completed = mutableListOf<GuildMembersCsvWriter.CompletedBatch>()
        val writer = GuildMembersCsvWriter(output, clock, onBatchClosed = completed::add)

        writer.accept(payload(messageId = 0, uid = 1u, name = "Partial", end = false))
        writer.close()

        assertTrue(completed.isEmpty())
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun completedEmptyPayloadDeletesHeaderOnlyCsv() {
        val output = temporaryFolder.newFolder("empty-completed-batch")
        val clock = Clock.fixed(Instant.parse("2026-07-21T19:11:09Z"), ZoneOffset.UTC)
        val completed = mutableListOf<GuildMembersCsvWriter.CompletedBatch>()
        val writer = GuildMembersCsvWriter(output, clock, onBatchClosed = completed::add)

        writer.accept(
            ParsedPayload(
                messageId = 42,
                payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
                isEndOfMessage = true,
                data = GuildMembersData(emptyList()),
            ),
        )

        assertTrue(completed.isEmpty())
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun oversizedLiveRosterIsRejectedBeforePublishingEvidence() {
        val output = temporaryFolder.newFolder("oversized-live-roster")
        val writer = GuildMembersCsvWriter(output)
        val members = (1..(GuildMembersCsv.MAX_ROSTER_MEMBERS + 1)).map { index ->
            member(index.toUInt(), "Member $index")
        }

        val failure = runCatching {
            writer.accept(
                ParsedPayload(
                    messageId = 42,
                    payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
                    isEndOfMessage = true,
                    data = GuildMembersData(members),
                ),
            )
        }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun oversizedLiveMemberNameIsRejectedBeforePublishingEvidence() {
        val output = temporaryFolder.newFolder("oversized-live-name")
        val writer = GuildMembersCsvWriter(output)

        val failure = runCatching {
            writer.accept(
                ParsedPayload(
                    messageId = 42,
                    payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
                    isEndOfMessage = true,
                    data = GuildMembersData(
                        listOf(
                            member(
                                1u,
                                "x".repeat(GuildMembersCsv.MAX_MEMBER_NAME_CHARS + 1),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    private fun payload(messageId: Int, uid: UInt, name: String, end: Boolean): ParsedPayload =
        ParsedPayload(
            messageId = messageId,
            payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
            isEndOfMessage = end,
            data = GuildMembersData(listOf(member(uid, name))),
        )

    private fun member(uid: UInt, name: String) = GuildMember(
        uid = uid,
        name = name,
        level = 60u,
        weeklyMerit = 120u,
        totalMerit = 4560u,
        highScore = 789u,
        totalScore = 1234u,
        lastLogin = 1700000000u,
    )
}
