package dev.gf2log.app.management

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Owns the one-level automatic checkpoint used to undo the most recent CSV import.
 *
 * The database archive and planned deterministic retained-file names are published together.
 * A restore quarantines those files before replacing the database and puts them back on failure.
 */
class CsvImportCheckpointManager(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, CHECKPOINT_DIRECTORY)
    private val staging = File(appContext.filesDir, STAGING_DIRECTORY)
    private val previous = File(appContext.filesDir, PREVIOUS_DIRECTORY)

    fun canUndo(): Boolean = hasBaseCheckpoint() && digest(root).isFile
    private fun hasBaseCheckpoint(): Boolean = archive(root).isFile && manifest(root).isFile

    // Function Name: create
    // Description:
    // - Exports the current database before any selected CSV is retained.
    // - Atomically publishes the archive with the deterministic file identities the import may add.
    // Parameters:
    // - plannedFileNames: Validated retained CSV identities for non-duplicate selected files.
    // Returns:
    // - Unit after a durable checkpoint replaces the previous one.
    fun create(plannedFileNames: Set<String>) {
        val names = plannedFileNames.toSortedSet()
        require(names.size <= MAX_PLANNED_FILES) { "Too many CSV files for one checkpoint" }
        names.forEach(::requireSafeImportName)
        check(!File(root, QUARANTINE_DIRECTORY).exists()) {
            "A previous CSV import undo must be retried before another import"
        }
        deleteDirectory(staging)
        check(staging.mkdirs()) { "Unable to stage the CSV import checkpoint" }
        try {
            FileOutputStream(archive(staging)).use { output ->
                PlatoonBackupManager(appContext).export(output)
                output.fd.sync()
            }
            FileOutputStream(manifest(staging)).use { output ->
                output.write(names.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            deleteDirectory(previous)
            if (root.exists() && !root.renameTo(previous)) {
                error("Unable to preserve the previous CSV import checkpoint")
            }
            if (!staging.renameTo(root)) {
                if (previous.exists() && !previous.renameTo(root)) {
                    error("Unable to publish the CSV import checkpoint or restore its predecessor")
                }
                error("Unable to publish the CSV import checkpoint")
            }
            deleteDirectory(previous)
        } finally {
            deleteDirectory(staging)
        }
    }

    /** Seals a successful import so undo is refused after any later database mutation. */
    fun seal() {
        check(hasBaseCheckpoint()) { "No CSV import checkpoint is available" }
        val target = digest(root)
        check(!target.exists()) { "CSV import checkpoint is already sealed" }
        val temporary = File(root, "$DIGEST_FILE.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(
                    PlatoonBackupManager(appContext).currentDatabaseSha256()
                        .toByteArray(Charsets.US_ASCII),
                )
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Unable to seal the CSV import checkpoint" }
        } finally {
            temporary.delete()
        }
    }

    // Function Name: restore
    // Description:
    // - Quarantines only files planned by the reverted import, then restores the database.
    // - Restores quarantined evidence if database replacement fails.
    // Parameters:
    // - None.
    // Returns:
    // - Unit after the checkpoint is consumed.
    fun restore() {
        check(canUndo()) { "No CSV import checkpoint is available" }
        val expectedDigest = digest(root).readText(Charsets.US_ASCII)
        require(expectedDigest.matches(SHA256)) { "Invalid CSV checkpoint digest" }
        check(PlatoonBackupManager(appContext).currentDatabaseSha256() == expectedDigest) {
            "Platoon data changed after the CSV import; undo would overwrite newer changes"
        }
        restoreUnchecked()
    }

    internal fun rollbackFailedImport() {
        check(hasBaseCheckpoint()) { "No CSV import checkpoint is available" }
        restoreUnchecked()
    }

    private fun restoreUnchecked() {
        val names = manifest(root).readLines(Charsets.UTF_8)
            .filter(String::isNotBlank)
            .toSet()
        require(names.size <= MAX_PLANNED_FILES)
        names.forEach(::requireSafeImportName)
        val retained = File(appContext.filesDir, PlatoonRepository.RETAINED_CSV_DIRECTORY)
        val quarantine = File(root, QUARANTINE_DIRECTORY).apply { mkdirs() }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            names.forEach { name ->
                val source = File(retained, name)
                if (!source.isFile) return@forEach
                val target = File(quarantine, name)
                if (target.exists() && !target.delete()) error("Unable to clear undo quarantine")
                check(source.renameTo(target)) { "Unable to quarantine an imported CSV" }
                moved += source to target
            }
            archive(root).inputStream().use { PlatoonBackupManager(appContext).restoreCheckpoint(it) }
        } catch (error: Exception) {
            moved.asReversed().forEach { (source, target) ->
                if (target.exists() && !target.renameTo(source)) {
                    error.addSuppressed(IllegalStateException("Unable to restore retained CSV evidence"))
                }
            }
            throw error
        }
        deleteDirectory(root)
    }

    private fun requireSafeImportName(name: String) {
        require(name.matches(SAFE_IMPORT_NAME)) { "Invalid CSV checkpoint identity" }
    }

    private fun deleteDirectory(directory: File) {
        if (directory.exists()) check(directory.deleteRecursively()) {
            "Unable to remove CSV import checkpoint state"
        }
    }

    private fun archive(directory: File) = File(directory, ARCHIVE_FILE)
    private fun manifest(directory: File) = File(directory, MANIFEST_FILE)
    private fun digest(directory: File) = File(directory, DIGEST_FILE)

    private companion object {
        const val CHECKPOINT_DIRECTORY = "csv-import-checkpoint"
        const val STAGING_DIRECTORY = "csv-import-checkpoint.staging"
        const val PREVIOUS_DIRECTORY = "csv-import-checkpoint.previous"
        const val QUARANTINE_DIRECTORY = "retained-quarantine"
        const val ARCHIVE_FILE = "platoon.gf2backup"
        const val MANIFEST_FILE = "planned-files.txt"
        const val DIGEST_FILE = "post-import.sha256"
        const val MAX_PLANNED_FILES = 64
        val SAFE_IMPORT_NAME = Regex("import-\\d{8}T\\d{6}Z-[0-9a-f]{20}\\.csv")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
