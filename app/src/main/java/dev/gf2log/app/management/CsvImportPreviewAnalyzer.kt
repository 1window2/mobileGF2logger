package dev.gf2log.app.management

import java.time.Instant

/** Computes a read-only impact summary for a validated CSV selection. */
object CsvImportPreviewAnalyzer {
    data class Preview(
        val validatedFiles: Int,
        val duplicateFiles: Int,
        val historicalFiles: Int,
        val uniqueMembers: Int,
        val newMembers: Int,
        val nameDifferences: Int,
        val potentialJoins: Int,
        val potentialWithdrawals: Int,
        val totalBytes: Long,
        val firstCapture: Instant?,
        val lastCapture: Instant?,
    )

    /** Returns selected source identities already represented in the database. */
    fun duplicateFileNames(
        prepared: List<PlatoonCsvImportStore.PreparedImport>,
        representedSourceFiles: Set<String>,
    ): Set<String> {
        val selectedNames = prepared.mapTo(mutableSetOf()) { it.fileName }
        return selectedNames.intersect(representedSourceFiles)
    }

    fun analyze(
        prepared: List<PlatoonCsvImportStore.PreparedImport>,
        duplicateFileNames: Set<String>,
        existingMembers: List<MemberStatus>,
        latestSnapshot: PlatoonSnapshot?,
    ): Preview {
        require(duplicateFileNames.all { name -> prepared.any { it.fileName == name } })
        val actionable = prepared
            .filterNot { it.fileName in duplicateFileNames }
            .sortedWith(compareBy(PlatoonCsvImportStore.PreparedImport::capturedAt, { it.fileName }))
        val knownUids = existingMembers.mapTo(mutableSetOf()) { it.uid }
        val importedUids = actionable
            .flatMap { item -> item.snapshot.members.map { it.uid.toLong() } }
            .toSet()
        val importedNames = linkedMapOf<Long, String>()
        actionable.forEach { item ->
            item.snapshot.members.forEach { importedNames[it.uid.toLong()] = it.name }
        }
        val existingNames = existingMembers.associate { it.uid to it.name }
        fun isHistorical(item: PlatoonCsvImportStore.PreparedImport): Boolean {
            val baseline = latestSnapshot ?: return false
            return item.capturedAt.isBefore(baseline.capturedAt) ||
                (item.capturedAt == baseline.capturedAt &&
                    item.fileName <= baseline.sourceFile.orEmpty())
        }
        val historicalFiles = actionable.count(::isHistorical)

        var roster = latestSnapshot?.members
            ?.associate { it.uid to it.name }
            ?: existingMembers.filter(MemberStatus::isActive).associate { it.uid to it.name }
        var joins = 0
        var withdrawals = 0
        actionable
            .filterNot(::isHistorical)
            .forEach { item ->
                val next = item.snapshot.members.associate { it.uid.toLong() to it.name }
                joins += (next.keys - roster.keys).size
                withdrawals += (roster.keys - next.keys).size
                roster = next
            }

        return Preview(
            validatedFiles = prepared.size,
            duplicateFiles = duplicateFileNames.size,
            historicalFiles = historicalFiles,
            uniqueMembers = importedUids.size,
            newMembers = (importedUids - knownUids).size,
            nameDifferences = importedNames.count { (uid, name) ->
                existingNames[uid]?.let { it != name } == true
            },
            potentialJoins = joins,
            potentialWithdrawals = withdrawals,
            totalBytes = prepared.sumOf { it.byteCount.toLong() },
            firstCapture = prepared.minOfOrNull { it.capturedAt },
            lastCapture = prepared.maxOfOrNull { it.capturedAt },
        )
    }
}
