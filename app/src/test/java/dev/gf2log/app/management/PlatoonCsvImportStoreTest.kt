package dev.gf2log.app.management

import java.io.ByteArrayInputStream
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

    private companion object {
        val VALID_CSV = """
            uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
            1,Member,60,90,1000,100,200,1784489372,2026-07-19T19:29:33Z
        """.trimIndent()
    }
}
