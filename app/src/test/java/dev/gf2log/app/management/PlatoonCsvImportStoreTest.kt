package dev.gf2log.app.management

import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlatoonCsvImportStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun validCsvIsRetainedOnceUnderAStableContentIdentity() {
        val store = PlatoonCsvImportStore(temporary.newFolder("rosters"))

        val first = store.retain(ByteArrayInputStream(VALID_CSV.toByteArray()))
        val duplicate = store.retain(ByteArrayInputStream(VALID_CSV.toByteArray()))

        assertTrue(first.file.isFile)
        assertTrue(first.file.name.matches(Regex("import-20260719T192933Z-[0-9a-f]{20}\\.csv")))
        assertFalse(first.duplicate)
        assertTrue(duplicate.duplicate)
        assertTrue(first.file.readText() == VALID_CSV)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedCsvIsRejectedBeforeAnythingIsRetained() {
        val store = PlatoonCsvImportStore(temporary.newFolder("invalid"))
        store.retain(ByteArrayInputStream("not,csv".toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedCsvIsRejectedBeforeParsing() {
        val store = PlatoonCsvImportStore(temporary.newFolder("oversized"))
        store.retain(ByteArrayInputStream(ByteArray(2 * 1024 * 1024 + 1)))
    }

    @Test
    fun duplicateUidCsvIsRejectedBeforeAnythingIsRetained() {
        val directory = temporary.newFolder("duplicate-uids")
        val store = PlatoonCsvImportStore(directory)
        val duplicateUidCsv = VALID_CSV +
            "\n1,Duplicate,59,1,2,3,4,5,2026-07-19T19:29:33Z"

        val failure = runCatching {
            store.retain(ByteArrayInputStream(duplicateUidCsv.toByteArray()))
        }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<String>(), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun retainedImportUsesItsParsedCaptureTimeForLatestCsvOrdering() {
        val directory = temporary.newFolder("capture-time")
        val retained = PlatoonCsvImportStore(directory).retain(
            ByteArrayInputStream(VALID_CSV.toByteArray()),
        )

        assertEquals(
            Instant.parse("2026-07-19T19:29:33Z").toEpochMilli(),
            retained.file.lastModified(),
        )
    }

    private companion object {
        val VALID_CSV = """
            uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
            1,Member,60,90,1000,100,200,1784489372,2026-07-19T19:29:33Z
        """.trimIndent()
    }
}
