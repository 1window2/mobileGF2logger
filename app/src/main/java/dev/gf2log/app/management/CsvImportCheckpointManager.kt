package dev.gf2log.app.management

import android.content.Context
import dev.gf2log.app.settings.AppSettingsStore
import java.io.File
import java.io.FileOutputStream

/**
 * Owns the one-level automatic checkpoint used to undo the most recent CSV import.
 *
 * The database archive and planned deterministic retained-file names are published together.
 * A provisional import preserves its predecessor until sealing. Restore progress is journaled so
 * quarantined evidence and the database converge after ordinary failures or process termination.
 */
class CsvImportCheckpointManager internal constructor(
    context: Context,
    private val storageScope: PlatoonStorageScope =
        PlatoonProfileRegistry(context).activeScope(),
    private val restoreObserver: (PlatoonBackupManager.RestoreCheckpoint) -> Unit,
) {
    constructor(context: Context) : this(
        context,
        PlatoonProfileRegistry(context).activeScope(),
        {},
    )

    internal constructor(context: Context, storageScope: PlatoonStorageScope) : this(
        context,
        storageScope,
        {},
    )

    private val appContext = context.applicationContext
    private val profileRoot = storageScope.rootDirectory(appContext)
    private val root = File(profileRoot, CHECKPOINT_DIRECTORY)
    private val staging = File(profileRoot, STAGING_DIRECTORY)
    private val previous = File(profileRoot, PREVIOUS_DIRECTORY)

    init {
        synchronized(STATE_LOCK) {
            if (!operationInProgress) recoverInterruptedState()
        }
    }

    fun canUndo(): Boolean = synchronized(STATE_LOCK) {
        !operationInProgress && hasBaseCheckpoint() && digest(root).isFile
    }
    private fun hasBaseCheckpoint(): Boolean = archive(root).isFile && manifest(root).isFile

    // Function Name: create
    // Description:
    // - Exports the current database before any selected CSV is retained.
    // - Atomically publishes the archive with the deterministic file identities the import may add.
    // Parameters:
    // - plannedFileNames: Validated retained CSV identities for non-duplicate selected files.
    // Returns:
    // - Unit after a durable provisional checkpoint is published beside its predecessor.
    fun create(plannedFileNames: Set<String>) {
        claimOperation()
        var published = false
        try {
            val names = plannedFileNames.toSortedSet()
            require(names.size <= MAX_PLANNED_FILES) { "Too many CSV files for one checkpoint" }
            names.forEach(::requireSafeImportName)
            check(!File(root, QUARANTINE_DIRECTORY).exists()) {
                "A previous CSV import undo must be retried before another import"
            }
            deleteDirectory(staging)
            check(staging.mkdirs()) { "Unable to stage the CSV import checkpoint" }
            FileOutputStream(archive(staging)).use { output ->
                PlatoonBackupManager(
                    appContext,
                    AppSettingsStore(appContext),
                    storageScope = storageScope,
                ).export(output)
                output.fd.sync()
            }
            FileOutputStream(manifest(staging)).use { output ->
                output.write(names.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(!previous.exists()) { "A previous CSV import checkpoint is still pending" }
            if (root.exists() && !root.renameTo(previous)) {
                error("Unable to preserve the previous CSV import checkpoint")
            }
            if (!staging.renameTo(root)) {
                if (previous.exists() && !previous.renameTo(root)) {
                    error("Unable to publish the CSV import checkpoint or restore its predecessor")
                }
                error("Unable to publish the CSV import checkpoint")
            }
            deleteDirectory(staging)
            published = true
        } finally {
            if (!published) {
                runCatching { deleteDirectory(staging) }
                releaseOperation()
            }
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
                    PlatoonBackupManager(
                        appContext,
                        AppSettingsStore(appContext),
                        storageScope = storageScope,
                    ).currentDatabaseSha256()
                        .toByteArray(Charsets.US_ASCII),
                )
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Unable to seal the CSV import checkpoint" }
        } finally {
            temporary.delete()
        }
        deleteDirectory(previous)
        releaseOperation()
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
        claimOperation()
        try {
            check(hasBaseCheckpoint() && digest(root).isFile) {
                "No CSV import checkpoint is available"
            }
            val expectedDigest = digest(root).readText(Charsets.US_ASCII)
            require(expectedDigest.matches(SHA256)) { "Invalid CSV checkpoint digest" }
            check(
                PlatoonBackupManager(
                    appContext,
                    AppSettingsStore(appContext),
                    storageScope = storageScope,
                ).currentDatabaseSha256() == expectedDigest,
            ) {
                "Platoon data changed after the CSV import; undo would overwrite newer changes"
            }
            restoreUnchecked()
        } finally {
            releaseOperation()
        }
    }

    internal fun rollbackFailedImport() {
        try {
            check(hasBaseCheckpoint()) { "No CSV import checkpoint is available" }
            restoreUnchecked()
        } finally {
            releaseOperation()
        }
    }

    private fun restoreUnchecked() {
        val names = manifest(root).readLines(Charsets.UTF_8)
            .filter(String::isNotBlank)
            .toSet()
        require(names.size <= MAX_PLANNED_FILES)
        names.forEach(::requireSafeImportName)
        val retained = storageScope.retainedCsvDirectory(appContext)
        val quarantine = File(root, QUARANTINE_DIRECTORY)
        if (!quarantine.exists()) {
            check(quarantine.mkdirs()) { "Unable to create the retained CSV quarantine" }
        }
        try {
            names.forEach { name ->
                val source = File(retained, name)
                val target = File(quarantine, name)
                if (target.exists()) {
                    check(!source.exists()) {
                        "Retained and quarantined CSV evidence both exist"
                    }
                } else if (source.isFile) {
                    check(source.renameTo(target)) { "Unable to quarantine an imported CSV" }
                }
            }
            val backupManager = PlatoonBackupManager(
                context = appContext,
                settingsStore = AppSettingsStore(appContext),
                storageScope = storageScope,
                restoreObserver = { checkpoint ->
                    if (checkpoint == PlatoonBackupManager.RestoreCheckpoint.DATABASE_INSTALLED) {
                        markRestoreDatabaseInstalled()
                    }
                    restoreObserver(checkpoint)
                },
            )
            archive(root).inputStream().use(backupManager::restoreCheckpoint)
        } catch (error: Exception) {
            val marker = restoreMarker(root)
            if (marker.exists() && !marker.delete()) {
                error.addSuppressed(
                    IllegalStateException("Unable to clear the CSV undo restore marker"),
                )
            }
            restoreQuarantinedFiles(names, retained, quarantine, error)
            throw error
        }
        deleteDirectory(root)
        restorePreviousCheckpoint()
    }

    // Function Name: recoverInterruptedState
    // Description:
    // - Restores an interrupted checkpoint publication, import rollback, or undo operation.
    // - Keeps a sealed latest checkpoint and discards its obsolete predecessor.
    // Parameters:
    // - None.
    // Returns:
    // - Unit after checkpoint state is usable or the interrupted mutation is rolled back.
    private fun recoverInterruptedState() {
        deleteDirectory(staging)
        if (!root.exists()) {
            restorePreviousCheckpoint()
            return
        }
        check(hasBaseCheckpoint()) { "CSV import checkpoint state is incomplete" }
        val quarantine = File(root, QUARANTINE_DIRECTORY)
        if (!digest(root).isFile || restoreMarker(root).isFile || quarantine.exists()) {
            restoreUnchecked()
        } else if (previous.exists()) {
            deleteDirectory(previous)
        }
    }

    private fun restorePreviousCheckpoint() {
        if (!previous.exists()) return
        check(!root.exists()) { "Cannot restore a predecessor over a live CSV checkpoint" }
        check(previous.renameTo(root)) { "Unable to restore the previous CSV import checkpoint" }
    }

    private fun markRestoreDatabaseInstalled() {
        val target = restoreMarker(root)
        if (target.isFile) return
        val temporary = File(root, "$RESTORE_MARKER.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write("DATABASE_INSTALLED".toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            check(temporary.renameTo(target)) {
                "Unable to publish the CSV undo restore marker"
            }
        } finally {
            temporary.delete()
        }
    }

    private fun restoreQuarantinedFiles(
        names: Set<String>,
        retained: File,
        quarantine: File,
        failure: Exception,
    ) {
        names.toList().asReversed().forEach { name ->
            val target = File(quarantine, name)
            if (!target.exists()) return@forEach
            if (!retained.exists() && !retained.mkdirs()) {
                failure.addSuppressed(
                    IllegalStateException("Unable to recreate retained CSV storage"),
                )
                return@forEach
            }
            val source = File(retained, name)
            if (source.exists() || !target.renameTo(source)) {
                failure.addSuppressed(
                    IllegalStateException("Unable to restore retained CSV evidence"),
                )
            }
        }
    }

    private fun claimOperation() {
        synchronized(STATE_LOCK) {
            check(!operationInProgress) { "Another CSV checkpoint operation is in progress" }
            operationInProgress = true
        }
    }

    private fun releaseOperation() {
        synchronized(STATE_LOCK) {
            operationInProgress = false
        }
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
    private fun restoreMarker(directory: File) = File(directory, RESTORE_MARKER)

    private companion object {
        val STATE_LOCK = Any()

        @Volatile
        var operationInProgress = false

        const val CHECKPOINT_DIRECTORY = "csv-import-checkpoint"
        const val STAGING_DIRECTORY = "csv-import-checkpoint.staging"
        const val PREVIOUS_DIRECTORY = "csv-import-checkpoint.previous"
        const val QUARANTINE_DIRECTORY = "retained-quarantine"
        const val ARCHIVE_FILE = "platoon.gf2backup"
        const val MANIFEST_FILE = "planned-files.txt"
        const val DIGEST_FILE = "post-import.sha256"
        const val RESTORE_MARKER = "restore-database-installed"
        const val MAX_PLANNED_FILES = 64
        val SAFE_IMPORT_NAME = Regex("import-\\d{8}T\\d{6}Z-[0-9a-f]{20}\\.csv")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
