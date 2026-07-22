package dev.gf2log.app.history

import dev.gf2log.protocol.model.CommonKey
import dev.gf2log.protocol.model.CommonKeysData
import dev.gf2log.protocol.model.ParsedPayload
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun payload(index: Int): ParsedPayload = ParsedPayload(
        messageId = index,
        payloadType = 11138,
        data = CommonKeysData(
            keys = listOf(CommonKey(uid = index.toULong(), keyId = index.toUInt())),
        ),
        isEndOfMessage = true,
    )
}
