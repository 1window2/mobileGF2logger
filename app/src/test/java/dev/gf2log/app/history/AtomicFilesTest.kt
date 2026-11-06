package dev.gf2log.app.history

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun contentIsSyncedBeforeTheDestinationNameAppears() {
        val directory = temporaryFolder.newFolder("atomic")
        val destination = directory.resolve("entry.txt")
        val content = "complete entry".toByteArray(Charsets.UTF_8)
        var syncs = 0

        AtomicFiles.write(destination, content, "entry_") { stream ->
            syncs += 1
            assertFalse("The destination must not exist before its data is synced", destination.exists())
            assertEquals(content.size.toLong(), directory.listFiles().orEmpty().single().length())
            stream.fd.sync()
        }

        assertEquals(1, syncs)
        assertArrayEquals(content, destination.readBytes())
        assertEquals(listOf("entry.txt"), directory.list().orEmpty().toList())
    }
}
