package dev.gf2log.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.gf2log.app.management.BackupFileName
import dev.gf2log.app.management.InvalidBackupException
import dev.gf2log.app.management.PlatoonAccessFailure
import dev.gf2log.app.management.PlatoonBackupManager
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.management.RestoredBackupKind
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.CaptureGuard
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.DocumentAccess
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import java.time.LocalDateTime

/**
 * Settings backup actions: export the selected Platoon or everything, and restore a chosen file.
 * Each waits for capture to stop and shares [maintenanceKey] with other maintenance so only one
 * runs at a time; a successful restore recreates the screen on the restored data.
 */
internal class BackupFlow(
    private val activity: ComponentActivity,
    private val work: BackgroundWork,
    private val maintenanceKey: String,
    private val scope: () -> PlatoonStorageScope?,
) {
    private var pendingExportComplete = false

    private val exportDocument = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument(PlatoonBackupManager.MIME_TYPE),
    ) { uri ->
        val target = scope()
        if (uri == null) return@registerForActivityResult
        if (target == null) {
            work.run(task = { DocumentAccess.discard(activity.contentResolver, uri) })
            return@registerForActivityResult
        }
        val complete = pendingExportComplete
        run(
            progress = R.string.backup_exporting,
            success = if (complete) R.string.full_backup_exported else R.string.status_backup_exported,
            failure = {
                platoonExportFailureMessage(it, backupExportFailureMessage(complete))
            },
        ) {
            DocumentAccess.writeExport(activity.contentResolver, uri) { output ->
                val manager = PlatoonBackupManager(activity, target)
                if (complete) manager.exportFull(output) else manager.export(output)
            }
        }
    }

    private val restoreDocument = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::restore)
    }

    fun saveState(outState: Bundle) {
        outState.putBoolean(STATE_EXPORT_COMPLETE, pendingExportComplete)
    }

    fun restoreState(state: Bundle?) {
        pendingExportComplete = state?.getBoolean(STATE_EXPORT_COMPLETE) == true
    }

    fun export(complete: Boolean) = CaptureGuard.whenStopped(activity, R.string.stop_capture_before_backup) {
        pendingExportComplete = complete
        val prefix = if (complete) "GF2logger-full" else "GF2logger-platoon"
        exportDocument.launch("$prefix-${Labels.FILE_STAMP.format(LocalDateTime.now())}.${PlatoonBackupManager.FILE_EXTENSION}")
    }

    fun confirmRestore() = CaptureGuard.whenStopped(activity, R.string.stop_capture_before_backup) {
        Dialogs.confirm(activity, R.string.restore_full_backup, R.string.restore_backup_warning, R.string.choose_file, destructive = true) {
            restoreDocument.launch(arrayOf(PlatoonBackupManager.MIME_TYPE, "application/zip", "application/octet-stream"))
        }
    }

    private fun restore(uri: Uri) {
        if (DocumentAccess.displayName(activity.contentResolver, uri)?.let(BackupFileName::isValid) != true) {
            Feedback.show(activity, R.string.invalid_full_backup, long = true)
            return
        }
        if (work.isBusy(maintenanceKey)) return
        Feedback.show(activity, R.string.backup_restoring)
        work.run(
            key = maintenanceKey,
            task = {
                // Checked again where it runs: standby or an alarm may have started capture while
                // the file was being chosen.
                CaptureGuard.whileStopped {
                    DocumentAccess.openInput(activity.contentResolver, uri).use { input ->
                        PlatoonBackupManager.restoreSelected(activity, input)
                    }
                } ?: throw CaptureGuard.CaptureActiveException()
            },
            // Runs even if this screen was recreated meanwhile; the showing screen reloads the data.
            always = { result ->
                result.fold(
                    onSuccess = { kind ->
                        Feedback.show(
                            activity,
                            if (kind == RestoredBackupKind.COMPLETE) R.string.full_backup_restored else R.string.status_backup_restored,
                            long = true,
                        )
                        (activity.takeUnless { it.isFinishing || it.isDestroyed } ?: BaseActivity.foregroundScreen())
                            ?.recreate()
                    },
                    onFailure = {
                        val message = if (it is CaptureGuard.CaptureActiveException) {
                            R.string.stop_capture_before_backup
                        } else {
                            fullBackupRestoreFailureMessage(it)
                        }
                        Feedback.show(activity, message, long = true)
                    },
                )
            },
        )
    }

    private fun run(progress: Int, success: Int, failure: (Throwable) -> Int, operation: () -> Unit) {
        if (work.isBusy(maintenanceKey)) return
        Feedback.show(activity, progress)
        work.run(key = maintenanceKey, task = operation, always = { result ->
            val damagedScope = scope()?.takeIf {
                PlatoonAccessFailure.of(result.exceptionOrNull()) == PlatoonAccessFailure.DATABASE_DAMAGED
            }
            if (damagedScope != null) {
                offerRebuild(damagedScope)
            } else {
                Feedback.show(activity, result.fold(onSuccess = { success }, onFailure = failure), long = true)
            }
        })
    }

    /**
     * No backup is written from a damaged database. The user may restore an earlier backup, or
     * continue with a database rebuilt from the retained roster CSVs; the damaged copy is kept.
     */
    private fun offerRebuild(target: PlatoonStorageScope) = PlatoonDataStates.confirmRebuild(
        activity,
        work,
        maintenanceKey,
        target,
        R.string.platoon_database_damaged_message,
    )

    private companion object {
        const val STATE_EXPORT_COMPLETE = "settings.export_complete"
    }
}

/**
 * Known validation failures blame the selected file, and a CSV import holding the Platoon asks to
 * try again shortly; anything else is an operational failure.
 */
internal fun fullBackupRestoreFailureMessage(error: Throwable): Int = when {
    error is InvalidBackupException -> R.string.invalid_full_backup
    PlatoonAccessFailure.of(error) == PlatoonAccessFailure.IMPORT_IN_PROGRESS -> R.string.platoon_csv_import_in_progress
    else -> R.string.full_backup_restore_failed
}

/** A Platoon-only export failure must not claim that the complete backup failed. */
internal fun backupExportFailureMessage(complete: Boolean): Int =
    if (complete) R.string.full_backup_export_failed else R.string.status_backup_export_failed
