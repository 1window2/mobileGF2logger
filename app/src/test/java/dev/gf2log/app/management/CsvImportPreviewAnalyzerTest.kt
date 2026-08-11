package dev.gf2log.app.management

import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMember
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvImportPreviewAnalyzerTest {
    @Test
    fun separatesHistoricalEvidenceFromProjectedCurrentRosterChanges() {
        val baselineTime = Instant.parse("2026-08-10T00:00:00Z")
        val existing = listOf(
            status(1, "One", active = true),
            status(2, "Old Name", active = true),
        )
        val latest = PlatoonSnapshot(
            id = 1,
            capturedAt = baselineTime,
            members = listOf(snapshotMember(1, "One"), snapshotMember(2, "Old Name")),
            sourceFile = "baseline.csv",
        )
        val prepared = listOf(
            prepared(
                "import-20260809T000000Z-00000000000000000001.csv",
                "2026-08-09T00:00:00Z",
                member(99, "Historical"),
            ),
            prepared(
                "import-20260811T000000Z-00000000000000000002.csv",
                "2026-08-11T00:00:00Z",
                member(2, "New Name"),
                member(3, "Three"),
            ),
        )

        val preview = CsvImportPreviewAnalyzer.analyze(
            prepared = prepared,
            duplicateFileNames = emptySet(),
            existingMembers = existing,
            latestSnapshot = latest,
        )

        assertEquals(2, preview.validatedFiles)
        assertEquals(1, preview.historicalFiles)
        assertEquals(3, preview.uniqueMembers)
        assertEquals(2, preview.newMembers)
        assertEquals(1, preview.nameDifferences)
        assertEquals(1, preview.potentialJoins)
        assertEquals(1, preview.potentialWithdrawals)
    }

    @Test
    fun alreadyRetainedFilesHaveNoProjectedImpact() {
        val item = prepared(
            "import-20260811T000000Z-00000000000000000003.csv",
            "2026-08-11T00:00:00Z",
            member(3, "Three"),
        )
        val preview = CsvImportPreviewAnalyzer.analyze(
            prepared = listOf(item),
            duplicateFileNames = setOf(item.fileName),
            existingMembers = listOf(status(1, "One", active = true)),
            latestSnapshot = null,
        )

        assertEquals(1, preview.duplicateFiles)
        assertEquals(0, preview.uniqueMembers)
        assertEquals(0, preview.potentialJoins)
        assertEquals(0, preview.potentialWithdrawals)
    }

    @Test
    fun databaseRepresentedSourcesRemainDuplicatesWithoutRetainedFiles() {
        val item = prepared(
            "import-20260811T000000Z-00000000000000000004.csv",
            "2026-08-11T00:00:00Z",
            member(4, "Four"),
        )

        val duplicates = CsvImportPreviewAnalyzer.duplicateFileNames(
            prepared = listOf(item),
            representedSourceFiles = setOf(item.fileName),
        )

        assertEquals(setOf(item.fileName), duplicates)
    }

    @Test
    fun retainedOnlySourcesRemainActionableUntilRecoveryRepresentsThem() {
        val item = prepared(
            "import-20260811T000000Z-00000000000000000005.csv",
            "2026-08-11T00:00:00Z",
            member(5, "Five"),
        )

        val duplicates = CsvImportPreviewAnalyzer.duplicateFileNames(
            prepared = listOf(item),
            representedSourceFiles = emptySet(),
        )

        assertEquals(emptySet<String>(), duplicates)
    }

    @Test
    fun equalCaptureTimesUseSourceFileAsCurrentRosterTieBreak() {
        val capturedAt = "2026-08-11T00:00:00Z"
        val latest = PlatoonSnapshot(
            id = 1,
            capturedAt = Instant.parse(capturedAt),
            members = listOf(snapshotMember(1, "One"), snapshotMember(2, "Two")),
            sourceFile = "import-20260811T000000Z-00000000000000000002.csv",
        )
        val prepared = listOf(
            prepared(
                "import-20260811T000000Z-00000000000000000001.csv",
                capturedAt,
                member(99, "Historical"),
            ),
            prepared(
                "import-20260811T000000Z-00000000000000000003.csv",
                capturedAt,
                member(1, "One"),
                member(3, "Three"),
            ),
        )

        val preview = CsvImportPreviewAnalyzer.analyze(
            prepared = prepared,
            duplicateFileNames = emptySet(),
            existingMembers = listOf(
                status(1, "One", active = true),
                status(2, "Two", active = true),
            ),
            latestSnapshot = latest,
        )

        assertEquals(1, preview.historicalFiles)
        assertEquals(1, preview.potentialJoins)
        assertEquals(1, preview.potentialWithdrawals)
    }

    private fun prepared(
        fileName: String,
        capturedAt: String,
        vararg members: GuildMember,
    ) = PlatoonCsvImportStore.PreparedImport(
        encoded = fileName.toByteArray(),
        snapshot = GuildMembersCsv.Snapshot(capturedAt, members.toList()),
        capturedAt = Instant.parse(capturedAt),
        fileName = fileName,
    )

    private fun member(uid: Int, name: String) = GuildMember(
        uid = uid.toUInt(),
        name = name,
        level = 60u,
        weeklyMerit = 0u,
        totalMerit = 0u,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private fun snapshotMember(uid: Long, name: String) = SnapshotMember(
        uid = uid,
        name = name,
        level = 60,
        weeklyMerit = 0,
        totalMerit = 0,
        highScore = 0,
        totalScore = 0,
        lastLogin = 0,
    )

    private fun status(uid: Long, name: String, active: Boolean) = MemberStatus(
        uid = uid,
        name = name,
        level = 60,
        isActive = active,
        firstSeenAt = Instant.EPOCH,
        lastSeenAt = Instant.EPOCH,
        note = "",
        membershipPeriods = emptyList(),
    )
}
