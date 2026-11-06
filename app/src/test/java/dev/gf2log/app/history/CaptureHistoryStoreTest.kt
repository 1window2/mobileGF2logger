package dev.gf2log.app.history

import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.PublicSkillItem
import dev.gf2log.protocol.model.PublicSkillItemsData
import dev.gf2log.protocol.model.ParsedPayload
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureHistoryStoreTest {
    @Test
    fun keepsNewestOneHundredEntriesInNewestFirstOrder() {
        val directory = Files.createTempDirectory("gf2log-history-test").toFile()
        try {
            repeat(101) { index ->
                val clock = Clock.fixed(
                    Instant.parse("2026-07-22T08:00:00Z").plusSeconds(index.toLong()),
                    ZoneOffset.UTC,
                )
                CaptureHistoryStore(directory, clock, ZoneOffset.UTC).save(payload(index))
            }

            val store = CaptureHistoryStore(directory, displayZone = ZoneOffset.UTC)
            val entries = store.list()
            assertEquals(CaptureHistoryStore.MAX_ENTRIES, entries.size)
            assertEquals("26/07/22 08:01:40", entries.first().title)
            assertEquals("26/07/22 08:00:01", entries.last().title)
            assertFalse(entries.any { it.title == "26/07/22 08:00:00" })
            assertTrue(store.read("../outside.txt") == null)
            assertNotNull(store.read(entries.first().id))

            val deleted = store.delete(listOf(entries.first().id, "../outside.txt"))
            assertEquals(1, deleted)
            assertEquals(99, store.list().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun formatsHistoryTitlesInTheConfiguredDeviceZone() {
        val directory = Files.createTempDirectory("gf2log-history-zone-test").toFile()
        try {
            val clock = Clock.fixed(Instant.parse("2026-07-22T08:00:00Z"), ZoneOffset.UTC)
            val store = CaptureHistoryStore(directory, clock, ZoneId.of("Asia/Seoul"))

            val entry = store.save(payload(1))

            assertEquals("26/07/22 17:00:00", entry.title)
            assertTrue(store.read(entry.id)?.contains("capturedAt=2026-07-22T08:00:00Z") == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesToReadAnOversizedLegacyHistoryEntry() {
        val directory = Files.createTempDirectory("gf2log-history-bound-test").toFile()
        try {
            val file = directory.resolve("1_21917_1.txt")
            file.writeBytes(ByteArray(CaptureHistoryStore.MAX_ENTRY_BYTES + 1))
            val store = CaptureHistoryStore(directory)

            assertEquals(1, store.list().size)
            assertTrue(store.read(file.name) == null)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun listingNeverDeletesAndOnlySavingRotates() {
        val directory = Files.createTempDirectory("gf2log-history-list-test").toFile()
        try {
            repeat(CaptureHistoryStore.MAX_ENTRIES + 5) { index ->
                directory.resolve("${index + 1}_11138_$index.txt").apply {
                    writeText("entry $index")
                    setLastModified((index + 1) * 1_000L)
                }
            }
            val store = CaptureHistoryStore(directory)

            assertEquals(CaptureHistoryStore.MAX_ENTRIES + 5, store.list().size)
            assertEquals(CaptureHistoryStore.MAX_ENTRIES + 5, store.list().size)

            store.save(payload(1))
            assertEquals(CaptureHistoryStore.MAX_ENTRIES, store.list().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun storesForTheSameDirectoryShareOneLock() {
        val directory = Files.createTempDirectory("gf2log-history-lock-test").toFile()
        try {
            assertTrue(
                HistoryDirectory(directory).lock ===
                    HistoryDirectory(directory.resolve("nested").resolve("..")).lock,
            )
            assertFalse(
                HistoryDirectory(directory).lock === HistoryDirectory(directory.resolve("other")).lock,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oversizedPayloadWritesNothingAndKeepsEarlierEntries() {
        val directory = Files.createTempDirectory("gf2log-history-oversized-test").toFile()
        try {
            val store = CaptureHistoryStore(directory)
            val kept = store.save(payload(1))
            val name = "x".repeat(GuildMembersCsv.MAX_MEMBER_NAME_CHARS)
            val oversized = ParsedPayload(
                messageId = 7,
                payloadType = Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
                isEndOfMessage = true,
                data = GuildMembersData(
                    List(CaptureHistoryStore.MAX_ENTRY_BYTES / name.length) { index ->
                        GuildMember((index + 1).toUInt(), name, 60u, 1u, 2u, 3u, 4u, 5u)
                    },
                ),
            )

            assertThrows(IllegalArgumentException::class.java) { store.save(oversized) }

            assertEquals(listOf(kept.id), store.list().map { it.id })
            assertEquals(listOf(kept.id), directory.list().orEmpty().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun payload(index: Int): ParsedPayload = ParsedPayload(
        messageId = index,
        payloadType = 11138,
        data = PublicSkillItemsData(
            items = listOf(
                PublicSkillItem(
                    id = index.toULong(),
                    stcId = index.toUInt(),
                    gunId = 0u,
                    lockedFlags = 0uL,
                    isNew = false,
                ),
            ),
        ),
        isEndOfMessage = true,
    )
}
