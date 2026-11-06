package dev.gf2log.app.management

import android.content.Context
import android.util.Log
import dev.gf2log.app.settings.ScopedAppSettingsStore
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
    private val discarded = File(profileRoot, DISCARDED_DIRECTORY)

    /** Claims are per scope: an operation in one Platoon never blocks another Platoon's import. */
    private val scopeState = synchronized(SCOPE_STATES) {
        SCOPE_STATES.getOrPut(storageScope.storageId, ::ScopeState)
    }

    init {
        synchronized(scopeState) {
            if (!scopeState.operationInProgress) {
                recoverInterruptedState()
                synchronized(RECOVERED_SCOPES) { RECOVERED_SCOPES += storageScope.storageId }
            }
        }
    }

    fun canUndo(): Boolean = synchronized(scopeState) {
        !scopeState.operationInProgress && hasBaseCheckpoint() && digest(root).isFile
    }

    /**
     * Removes the undo checkpoint once its data must not survive, as after deleting a member it
     * contains. Leaves an interrupted undo's quarantine for recovery to finish.
     */
    fun discardCheckpoint() {
        claimOperation()
        try {
            discardSettled(profileRoot)
        } finally {
            releaseOperation()
        }
    }
    private fun hasBaseCheckpoint(): Boolean = archive(root).isFile && manifest(root).isFile

    /**
     * Exports the current database before any selected CSV is retained. Atomically publishes the
     * archive with the deterministic file identities the import may add.
     */
    fun create(plannedFileNames: Set<String>) {
        claimOperation()
        var published = false
        try {
            // Staging would otherwise recreate the directory of a Platoon deleted meanwhile.
            PlatoonProfileAdministration.requireStorageAllowed(appContext, storageScope)
            val names = plannedFileNames.toSortedSet()
            require(names.size <= MAX_PLANNED_FILES) { "Too many CSV files for one checkpoint" }
            names.forEach(::requireSafeImportName)
            check(!File(root, QUARANTINE_DIRECTORY).exists()) {
                "A previous CSV import undo must be retried before another import"
            }
            deleteDirectory(staging)
            check(staging.mkdirs()) { "Unable to stage the CSV import checkpoint" }
            FileOutputStream(archive(staging)).use { output ->
                backupManager().export(output)
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
        try {
            sealClaimed()
        } finally {
            releaseOperation()
        }
    }

    private fun sealClaimed() {
        check(hasBaseCheckpoint()) { "No CSV import checkpoint is available" }
        val target = digest(root)
        check(!target.exists()) { "CSV import checkpoint is already sealed" }
        val temporary = File(root, "$DIGEST_FILE.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(
                    backupManager().currentDatabaseSha256().toByteArray(Charsets.US_ASCII),
                )
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Unable to seal the CSV import checkpoint" }
        } finally {
            temporary.delete()
        }
        discard(previous)
    }

    /**
     * Runs one complete import: checkpoint, [apply], then seal. Any failure, including an
     * [Error], rolls the import back, and the scope's claim is always released.
     */
    fun <T> runImport(plannedFileNames: Set<String>, apply: () -> T): T {
        create(plannedFileNames)
        // The claim taken by create is held across apply, seal, and any rollback, and released
        // exactly once, so no other operation on this scope can interleave with a rollback.
        try {
            val result = apply()
            sealClaimed()
            return result
        } catch (failure: Throwable) {
            runCatching(::rollbackClaimed).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            releaseOperation()
        }
    }

    /**
     * Quarantines only files planned by the reverted import, then restores the database. Restores
     * quarantined evidence if database replacement fails.
     */
    fun restore() {
        claimOperation()
        try {
            check(hasBaseCheckpoint() && digest(root).isFile) {
                "No CSV import checkpoint is available"
            }
            val expectedDigest = digest(root).readText(Charsets.US_ASCII)
            require(expectedDigest.matches(SHA256)) { "Invalid CSV checkpoint digest" }
            // Fails fast before quarantining anything; the authoritative comparison runs again
            // inside the exclusive section that installs the checkpoint. A changed database never
            // returns to the sealed digest, so the checkpoint and its pre-import copy go now.
            if (backupManager().currentDatabaseSha256() != expectedDigest) {
                discardSettled(profileRoot)
                throw CsvCheckpointExpiredException()
            }
            restoreUnchecked(expectedDigest)
        } finally {
            releaseOperation()
        }
    }

    internal fun rollbackFailedImport() {
        try {
            rollbackClaimed()
        } finally {
            releaseOperation()
        }
    }

    private fun rollbackClaimed() {
        check(hasBaseCheckpoint()) { "No CSV import checkpoint is available" }
        restoreUnchecked()
    }

    /**
     * With [expectedDatabaseSha256], the checkpoint is installed only while the live database still
     * has that digest, compared under the same exclusive lock, so a concurrent ingest is never lost.
     */
    private fun restoreUnchecked(expectedDatabaseSha256: String? = null) {
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
            val backupManager = backupManager { checkpoint ->
                if (checkpoint == PlatoonBackupManager.RestoreCheckpoint.DATABASE_INSTALLED) {
                    markRestoreDatabaseInstalled()
                }
                restoreObserver(checkpoint)
            }
            archive(root).inputStream().use { input ->
                backupManager.restoreCheckpoint(input, expectedDatabaseSha256)
            }
        } catch (error: Exception) {
            // The checkpoint still waits to be applied: the next repository access retries it
            // before any write, instead of a later recovery erasing what was written meanwhile.
            synchronized(RECOVERED_SCOPES) { RECOVERED_SCOPES -= storageScope.storageId }
            val marker = restoreMarker(root)
            if (marker.exists() && !marker.delete()) {
                error.addSuppressed(
                    IllegalStateException("Unable to clear the CSV undo restore marker"),
                )
            }
            restoreQuarantinedFiles(names, retained, quarantine, error)
            throw error
        }
        discard(root)
        restorePreviousCheckpoint()
    }

    /**
     * Restores an interrupted checkpoint publication, import rollback, or undo operation. Keeps a
     * sealed latest checkpoint and discards its obsolete predecessor.
     */
    private fun recoverInterruptedState() {
        deleteDirectory(staging)
        deleteDirectory(discarded)
        if (!root.exists()) {
            restorePreviousCheckpoint()
            return
        }
        check(hasBaseCheckpoint()) { "CSV import checkpoint state is incomplete" }
        val quarantine = File(root, QUARANTINE_DIRECTORY)
        if (!digest(root).isFile || restoreMarker(root).isFile || quarantine.exists()) {
            // The rollback holds the claim like an import, so no foreign write or backup restore
            // lands on the state it is about to replace.
            scopeState.owner = Thread.currentThread()
            try {
                restoreUnchecked()
            } finally {
                scopeState.owner = null
            }
        } else if (previous.exists()) {
            discard(previous)
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
        synchronized(scopeState) {
            check(!scopeState.operationInProgress) {
                "Another CSV checkpoint operation is in progress"
            }
            scopeState.owner = Thread.currentThread()
        }
    }

    private fun releaseOperation() {
        synchronized(scopeState) {
            scopeState.owner = null
        }
    }

    /**
     * The thread holding this scope's claim. Every import, rollback, undo and recovery step runs
     * on that one thread, so any other thread's write would be sealed into the undo digest or
     * erased by a rollback. Read without the monitor by the repository gate, which runs while
     * holding the maintenance lock and so must never wait on a claim.
     */
    private class ScopeState {
        @Volatile
        var owner: Thread? = null

        val operationInProgress: Boolean
            get() = owner != null
    }

    /**
     * Always binds restore recovery to this scope's settings. The global store would roll an
     * interrupted scoped restore back into the legacy keys instead of the Platoon's own settings.
     */
    private fun backupManager(
        observer: (PlatoonBackupManager.RestoreCheckpoint) -> Unit = {},
    ) = PlatoonBackupManager(
        context = appContext,
        settingsStore = ScopedAppSettingsStore(appContext, storageScope.storageId),
        restoreObserver = observer,
        storageScope = storageScope,
    )

    private fun requireSafeImportName(name: String) {
        require(name.matches(SAFE_IMPORT_NAME)) { "Invalid CSV checkpoint identity" }
    }

    /**
     * Retires a checkpoint directory with one atomic rename before deleting it. A recursive delete
     * interrupted midway would otherwise leave a live-looking checkpoint without its archive.
     * A leftover retired copy is inert and removed again by the next recovery.
     */
    private fun discard(directory: File) {
        if (!directory.exists()) return
        deleteDirectory(discarded)
        check(directory.renameTo(discarded)) { "Unable to retire CSV import checkpoint state" }
        discarded.deleteRecursively()
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

    companion object {
        private const val TAG = "GF2CsvCheckpoint"
        private val SCOPE_STATES = mutableMapOf<String, ScopeState>()

        /** Scopes whose interrupted import or undo this process has already finished. */
        private val RECOVERED_SCOPES = mutableSetOf<String>()

        private fun claimOwner(storageId: String): Thread? =
            synchronized(SCOPE_STATES) { SCOPE_STATES[storageId] }?.owner

        /** Whether an import, rollback, undo or recovery currently holds [storageId]'s claim. */
        internal fun isBusy(storageId: String): Boolean = claimOwner(storageId) != null

        /**
         * Refuses database access to [scope] from any thread but its claim holder. Never waits,
         * so it is safe under the maintenance lock; a refused write is reported, not later erased.
         */
        internal fun requireAccessAllowed(scope: PlatoonStorageScope) {
            val owner = claimOwner(scope.storageId) ?: return
            if (owner !== Thread.currentThread()) throw CsvImportInProgressException()
        }

        /**
         * Finishes [scope]'s interrupted import or undo once per process before a repository
         * first touches it, so an edit can never commit ahead of a rollback that would erase it.
         * Skipped on the claim holder's own thread. Failures propagate and refuse the access.
         */
        internal fun recoverOnce(context: Context, scope: PlatoonStorageScope) {
            if (synchronized(RECOVERED_SCOPES) { scope.storageId in RECOVERED_SCOPES }) return
            if (claimOwner(scope.storageId) === Thread.currentThread()) return
            CsvImportCheckpointManager(context, scope)
        }

        /**
         * Removes [scope]'s checkpoint after a committed backup restore replaced the data it
         * describes. Called under the exclusive gate, so it takes no claim; see [discardSettled].
         */
        internal fun discardCheckpoints(context: Context, scope: PlatoonStorageScope) {
            discardSettled(scope.rootDirectory(context))
        }

        /**
         * Retires the checkpoint and its predecessor unless an interrupted undo's quarantine or
         * install marker still needs recovery to return its evidence.
         */
        private fun discardSettled(profileRoot: File) {
            val root = File(profileRoot, CHECKPOINT_DIRECTORY)
            if (File(root, QUARANTINE_DIRECTORY).exists() || File(root, RESTORE_MARKER).exists()) return
            val discarded = File(profileRoot, DISCARDED_DIRECTORY)
            listOf(root, File(profileRoot, PREVIOUS_DIRECTORY)).forEach { directory ->
                if (!directory.exists()) return@forEach
                if (discarded.exists()) check(discarded.deleteRecursively()) {
                    "Unable to remove CSV import checkpoint state"
                }
                check(directory.renameTo(discarded)) { "Unable to retire CSV import checkpoint state" }
                discarded.deleteRecursively()
            }
        }

        private const val CHECKPOINT_DIRECTORY = "csv-import-checkpoint"
        private const val STAGING_DIRECTORY = "csv-import-checkpoint.staging"
        private const val PREVIOUS_DIRECTORY = "csv-import-checkpoint.previous"
        private const val DISCARDED_DIRECTORY = "csv-import-checkpoint.discarded"
        private const val QUARANTINE_DIRECTORY = "retained-quarantine"
        private const val ARCHIVE_FILE = "platoon.gf2backup"
        private const val MANIFEST_FILE = "planned-files.txt"
        private const val DIGEST_FILE = "post-import.sha256"
        private const val RESTORE_MARKER = "restore-database-installed"
        private const val MAX_PLANNED_FILES = 64
        private val SAFE_IMPORT_NAME = Regex("import-\\d{8}T\\d{6}Z-[0-9a-f]{20}\\.csv")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * Finishes every [scopes] interrupted import or undo, as constructing a manager does. Runs at
         * process start and before capture ingests, so a recovery can never overwrite newer
         * evidence. A failing scope is logged and left for the next attempt; returns how many failed.
         */
        internal fun recoverInterruptedImports(context: Context, scopes: List<PlatoonStorageScope>): Int =
            scopes.count { scope ->
                try {
                    // A claim still held means an import is writing: capture must not start on it.
                    if (isBusy(scope.storageId)) {
                        Log.w(TAG, "CSV import still running for ${scope.storageId}")
                        return@count true
                    }
                    CsvImportCheckpointManager(context, scope)
                    false
                } catch (error: Exception) {
                    Log.e(TAG, "CSV import recovery failed for ${scope.storageId}", error)
                    true
                }
            }

        /** [recoverInterruptedImports] for every registered Platoon. */
        internal fun recoverInterruptedImports(context: Context): Int {
            val scopes = try {
                PlatoonProfileRegistry(context).ensureInitialized().map { PlatoonStorageScope(it.storageId) }
            } catch (error: Exception) {
                Log.e(TAG, "Platoon profiles unavailable for CSV import recovery", error)
                return 1
            }
            return recoverInterruptedImports(context, scopes)
        }
    }
}

/** Another thread's import, rollback, undo or recovery owns this Platoon's database right now. */
internal class CsvImportInProgressException :
    IllegalStateException("A CSV import or undo is in progress for this Platoon")

/** The Platoon changed after the import, so its checkpoint was discarded instead of applied. */
internal class CsvCheckpointExpiredException :
    IllegalStateException("Platoon data changed after the CSV import; undo would overwrite newer changes")
