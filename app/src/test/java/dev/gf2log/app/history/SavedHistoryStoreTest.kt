package dev.gf2log.app.history

import dev.gf2log.protocol.model.CommonKey
import dev.gf2log.protocol.model.CommonKeysData
import dev.gf2log.protocol.model.ParsedPayload
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedHistoryStoreTest {
    @Test
    fun keepsFiftyExplicitlySavedEntriesUntilTheyAreDeleted() {
        val root = Files.createTempDirectory("gf2log-saved-history-test").toFile()
        try {
            val recent = CaptureHistoryStore(root.resolve("recent"))
            repeat(51) { index ->
                val clock = Clock.fixed(
                    Instant.parse("2026-07-22T08:00:00Z").plusSeconds(index.toLong()),
                    ZoneOffset.UTC,
                )
                CaptureHistoryStore(root.resolve("recent"), clock).save(payload(index))
            }
            val saved = SavedHistoryStore(root.resolve("saved"))
            val recentIds = recent.list().map { it.id }

            val initial = saved.saveFrom(recent, recentIds)

            assertEquals(SavedHistoryStore.MAX_ENTRIES, initial.saved)
            assertTrue(initial.limitReached)
            assertEquals(SavedHistoryStore.MAX_ENTRIES, saved.list().size)
            val unsavedId = (recentIds - saved.list().map { it.id }.toSet()).single()

            val duplicate = saved.saveFrom(recent, listOf(saved.list().first().id))
            assertEquals(0, duplicate.saved)
            assertEquals(1, duplicate.alreadySaved)

            val deletedId = saved.list().first().id
            assertEquals(1, saved.delete(listOf(deletedId)))
            assertFalse(saved.list().any { it.id == deletedId })

            val replacement = saved.saveFrom(recent, listOf(unsavedId))
            assertEquals(1, replacement.saved)
            assertEquals(SavedHistoryStore.MAX_ENTRIES, saved.list().size)
            assertTrue(saved.read(unsavedId)?.contains("messageId=") == true)
        } finally {
            root.deleteRecursively()
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
