package dev.gf2log.app.management

import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMember
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

    @Test
    fun prepareValidatesWithoutPublishingUntilConfirmation() {
        val directory = temporary.newFolder("preview")
        val store = PlatoonCsvImportStore(directory)

        val prepared = store.prepare(ByteArrayInputStream(VALID_CSV.toByteArray()))

        assertEquals(VALID_CSV.toByteArray().size, prepared.byteCount)
        assertEquals(Instant.parse("2026-07-19T19:29:33Z"), prepared.capturedAt)
        assertEquals(emptyList<String>(), directory.listFiles().orEmpty().map { it.name })
        val retained = store.retain(prepared)
        assertTrue(retained.file.isFile)
        assertFalse(retained.duplicate)
    }


    @Test
    fun spreadsheetExportImportsWithItsNamesAndIsRetainedLossless() {
        val snapshot = GuildMembersCsv.Snapshot(
            logTime = "2026-07-19T19:29:33Z",
            members = listOf(
                GuildMember(1u, "-Kai-", 60u, 1u, 2u, 3u, 4u, 5u),
                GuildMember(2u, "'=x", 60u, 1u, 2u, 3u, 4u, 5u),
                GuildMember(3u, "Plain", 60u, 1u, 2u, 3u, 4u, 5u),
            ),
        )
        val store = PlatoonCsvImportStore(temporary.newFolder("spreadsheet"))
        val exported = GuildMembersCsv.formatForSpreadsheet(snapshot).toByteArray()

        val prepared = store.prepare(ByteArrayInputStream(exported))
        val retained = store.retain(prepared)

        assertEquals(snapshot, prepared.snapshot)
        assertEquals(GuildMembersCsv.format(snapshot), retained.file.readText())
        assertEquals(snapshot, GuildMembersCsv.parse(retained.file.readText()))
    }

    @Test
    fun aCsvSavedWithAByteOrderMarkImportsAndKeepsItsBytes() {
        val store = PlatoonCsvImportStore(temporary.newFolder("bom"))
        val bytes = ("﻿" + VALID_CSV).toByteArray()

        val retained = store.retain(ByteArrayInputStream(bytes))

        assertTrue(retained.file.readBytes().contentEquals(bytes))
        assertEquals(
            GuildMembersCsv.parse(VALID_CSV),
            GuildMembersCsv.parse(retained.file.readText()),
        )
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
    fun futureDatedCsvIsRejectedBeforeAnythingIsRetained() {
        val directory = temporary.newFolder("future")
        val store = PlatoonCsvImportStore(directory) { Instant.parse("2026-07-19T17:00:00Z") }

        val failure = runCatching { store.retain(ByteArrayInputStream(VALID_CSV.toByteArray())) }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<String>(), directory.listFiles().orEmpty().map { it.name })
        // Within the allowed clock skew the same file is accepted.
        PlatoonCsvImportStore(directory) { Instant.parse("2026-07-19T18:40:00Z") }
            .prepare(ByteArrayInputStream(VALID_CSV.toByteArray()))
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

    @Test
    fun latestRetainedFileBreaksEqualCaptureTimesBySourceFilename() {
        val directory = temporary.newFolder("capture-time-tie")
        val lower = java.io.File(directory, "import-same-a.csv").apply { writeText("lower") }
        val higher = java.io.File(directory, "import-same-b.csv").apply { writeText("higher") }
        val capturedAt = Instant.parse("2026-07-19T19:29:33Z").toEpochMilli()
        assertTrue(lower.setLastModified(capturedAt))
        assertTrue(higher.setLastModified(capturedAt))

        assertEquals(higher, PlatoonCsvImportStore.latestRetainedFile(directory))
    }

    private companion object {
        val VALID_CSV = """
            uid,name,level,weeklyMerit,totalMerit,highScore,totalScore,lastLogin,logTime
            1,Member,60,90,1000,100,200,1784489372,2026-07-19T19:29:33Z
        """.trimIndent()
    }
}
