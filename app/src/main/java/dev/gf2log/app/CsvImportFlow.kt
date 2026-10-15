package dev.gf2log.app

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import androidx.activity.result.contract.ActivityResultContracts
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.management.CsvImportCheckpointManager
import dev.gf2log.app.management.CsvImportPreviewAnalyzer
import dev.gf2log.app.management.PlatoonCsvImportStore
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.captureActive
import dev.gf2log.app.ui.DocumentAccess
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.text

/**
 * Roster CSV import: choose the destination Platoon, pick files, preview their impact, then apply
 * behind an automatic checkpoint. Payload 21917 carries no Platoon identity, so the destination is
 * always an explicit user choice that is re-verified before anything is retained.
 */
internal class CsvImportFlow(
    private val activity: BaseActivity,
    private val work: BackgroundWork,
    private val boundScope: () -> PlatoonStorageScope?,
    private val onProgress: (CharSequence?) -> Unit,
) {
    private var pickerTarget: String? = null
    private var launchPickerWhenReady = false
    private var pending: PendingImport? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pickerTarget?.let(::resolveTarget)
        if (uris.isNullOrEmpty()) {
            pickerTarget = null
            return@registerForActivityResult
        }
        if (target == null) {
            pickerTarget = null
            Feedback.show(activity, R.string.csv_import_target_changed, long = true)
            return@registerForActivityResult
        }
        prepare(uris.distinct(), target)
    }

    fun saveState(outState: Bundle) {
        pickerTarget?.let { outState.putString(STATE_TARGET, it) }
        outState.putBoolean(STATE_LAUNCH, launchPickerWhenReady)
    }

    fun restoreState(state: Bundle?) {
        pickerTarget = state?.getString(STATE_TARGET)
        launchPickerWhenReady = state?.getBoolean(STATE_LAUNCH) == true
    }

    /** Called once the hosting screen is bound to its final scope. */
    fun onScreenReady() {
        if (!launchPickerWhenReady) return
        launchPickerWhenReady = false
        launchPicker()
    }

    fun start() {
        if (captureActive) {
            Feedback.show(activity, R.string.stop_capture_before_csv_import, long = true)
            return
        }
        PlatoonCsvImportPrompt.show(activity) { profile -> beginFor(profile) }
    }

    fun undo() {
        if (captureActive) {
            Feedback.show(activity, R.string.stop_capture_before_csv_import, long = true)
            return
        }
        val scope = boundScope() ?: run {
            Feedback.show(activity, R.string.no_platoon_detected_detail, long = true)
            return
        }
        work.run(task = { CsvImportCheckpointManager(activity, scope).canUndo() }) { result ->
            if (!result.getOrDefault(false)) {
                Feedback.show(activity, R.string.no_csv_import_checkpoint)
                return@run
            }
            Dialogs.confirm(activity, R.string.undo_last_csv_import, R.string.undo_last_csv_import_warning, R.string.undo) {
                onProgress(activity.getString(R.string.csv_import_undoing))
                work.run(task = { CsvImportCheckpointManager(activity, scope).restore() }) { restore ->
                    onProgress(null)
                    Feedback.show(
                        activity,
                        if (restore.isSuccess) R.string.csv_import_undone else R.string.csv_import_undo_failed,
                        long = restore.isFailure,
                    )
                }
            }
        }
    }

    private fun beginFor(profile: PlatoonProfile) {
        if (!PlatoonProfileRegistry(activity).setActive(profile.storageId)) {
            Feedback.show(activity, R.string.csv_import_target_changed, long = true)
            return
        }
        pickerTarget = profile.storageId
        if (boundScope()?.storageId == profile.storageId) {
            launchPicker()
        } else {
            // Rebind every scoped helper to the newly selected Platoon before reading files.
            launchPickerWhenReady = true
            activity.recreate()
        }
    }

    private fun launchPicker() {
        if (pickerTarget?.let(::resolveTarget) == null) {
            pickerTarget = null
            return
        }
        picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
    }

    private fun resolveTarget(storageId: String): Target? {
        val active = PlatoonProfileRegistry(activity).active() ?: return null
        val scope = PlatoonStorageScope(storageId)
        if (active.storageId != storageId || active.legacy || boundScope() != scope) return null
        return Target(scope, active)
    }

    private fun prepare(sources: List<Uri>, target: Target) {
        onProgress(activity.getString(R.string.csv_import_preparing_preview))
        work.run(
            task = {
                // Recover an interrupted prior import before reading the preview baseline.
                CsvImportCheckpointManager(activity, target.scope)
                require(sources.size <= MAX_FILES) { "Too many Platoon CSV files were selected" }
                val directory = target.scope.retainedCsvDirectory(activity)
                val store = PlatoonCsvImportStore(directory)
                var bytes = 0L
                val selected = sources.map { source ->
                    DocumentAccess.openInput(activity.contentResolver, source).use(store::prepare).also {
                        bytes += it.byteCount
                        require(bytes <= MAX_BYTES) { "Selected Platoon CSV files exceed the total import size limit" }
                    }
                }
                val unique = selected.distinctBy(PlatoonCsvImportStore.PreparedImport::fileName)
                val repository = PlatoonRepository(activity, target.scope)
                repository.reconcileRetainedCsvFiles(directory)
                val duplicates = CsvImportPreviewAnalyzer.duplicateFileNames(unique, repository.representedSnapshotSources())
                val analyzed = CsvImportPreviewAnalyzer.analyze(
                    prepared = unique,
                    duplicateFileNames = duplicates,
                    existingMembers = repository.listMemberStatuses(),
                    latestSnapshot = repository.listSnapshots(limit = 1).firstOrNull(),
                )
                PendingImport(
                    target = target,
                    prepared = unique,
                    duplicateFileNames = duplicates,
                    preview = analyzed.copy(
                        validatedFiles = selected.size,
                        duplicateFiles = analyzed.duplicateFiles + selected.size - unique.size,
                        totalBytes = bytes,
                    ),
                )
            },
        ) { result ->
            onProgress(null)
            result.fold(
                onSuccess = {
                    pending = it
                    showPreview(it)
                },
                onFailure = {
                    pickerTarget = null
                    Feedback.show(activity, R.string.status_platoon_csv_import_failed, long = true)
                },
            )
        }
    }

    private fun showPreview(import: PendingImport) {
        val preview = import.preview
        val profile = import.target.profile
        val range = if (preview.firstCapture == null || preview.lastCapture == null) {
            activity.getString(R.string.none)
        } else {
            val zone = java.time.ZoneId.systemDefault()
            activity.getString(
                R.string.csv_import_capture_range,
                Labels.format(Labels.DATE_TIME, preview.firstCapture, zone),
                Labels.format(Labels.DATE_TIME, preview.lastCapture, zone),
            )
        }
        val facts = listOf(
            R.string.csv_preview_files to preview.validatedFiles.toString(),
            R.string.csv_preview_already_present to preview.duplicateFiles.toString(),
            R.string.csv_preview_historical to preview.historicalFiles.toString(),
            R.string.csv_preview_members to preview.uniqueMembers.toString(),
            R.string.csv_preview_new_members to preview.newMembers.toString(),
            R.string.csv_preview_name_changes to preview.nameDifferences.toString(),
            R.string.csv_preview_joins to preview.potentialJoins.toString(),
            R.string.csv_preview_withdrawals to preview.potentialWithdrawals.toString(),
            R.string.csv_preview_size to activity.getString(R.string.kib, preview.totalBytes / 1024),
            R.string.csv_preview_range to range,
        )
        val content = Dialogs.content(activity) {
            addView(activity.panel(emphasized = true, padding = 12) {
                addView(activity.text(activity.getString(R.string.csv_import_destination_label), TextRole.LABEL), activity.lp())
                addView(activity.text(profile.platoonName, TextRole.SUBTITLE), activity.lp(top = 2))
                addView(activity.text(Labels.profileIdentity(activity, profile), TextRole.CAPTION), activity.lp())
                addView(activity.text(activity.getString(R.string.csv_import_destination_warning), TextRole.CAPTION) {
                    setTextColor(activity.getColor(R.color.accent_text))
                }, activity.lp(top = 6))
            }, activity.lp())
            facts.forEach { (label, value) ->
                addView(activity.row(Gravity.TOP) {
                    minimumHeight = (32 * resources.displayMetrics.density).toInt()
                    addView(activity.text(activity.getString(label), TextRole.SECONDARY), activity.lp(width = 0, weight = 1f))
                    addView(activity.text(value, TextRole.BODY) { gravity = Gravity.END }, activity.lp(WRAP, WRAP, start = 12))
                }, activity.lp(top = 4))
            }
            addView(activity.text(activity.getString(R.string.csv_import_no_changes_yet), TextRole.CAPTION), activity.lp(top = 8))
        }
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.csv_import_preview_title)
                .setView(content)
                .setNegativeButton(R.string.cancel) { _, _ -> clear() }
                .setOnCancelListener { clear() }
                .setPositiveButton(R.string.import_action) { _, _ -> apply(import) },
        )
    }

    private fun clear() {
        pending = null
        pickerTarget = null
    }

    private fun apply(import: PendingImport) {
        val target = resolveTarget(import.target.scope.storageId)
        if (pending !== import || captureActive || target == null ||
            target.profile.client != import.target.profile.client ||
            target.profile.platoonId != import.target.profile.platoonId
        ) {
            clear()
            Feedback.show(
                activity,
                if (captureActive) R.string.stop_capture_before_csv_import else R.string.csv_import_target_changed,
                long = true,
            )
            return
        }
        pending = null
        onProgress(activity.getString(R.string.csv_import_applying))
        work.run(
            task = {
                val directory = import.target.scope.retainedCsvDirectory(activity)
                val store = PlatoonCsvImportStore(directory)
                val planned = import.prepared
                    .filterNot { it.fileName in import.duplicateFileNames }
                    .mapTo(mutableSetOf()) { it.fileName }
                CsvImportCheckpointManager(activity, import.target.scope).runImport(planned) {
                    var retained = 0
                    var duplicates = 0
                    import.prepared.forEach { prepared ->
                        if (store.retain(prepared).duplicate) duplicates += 1 else retained += 1
                    }
                    val imported = PlatoonRepository(activity, import.target.scope).reconcileRetainedCsvFiles(directory)
                    Summary(retained, duplicates, imported)
                }
            },
        ) { result ->
            onProgress(null)
            pickerTarget = null
            result.fold(
                onSuccess = { summary ->
                    Feedback.show(
                        activity,
                        activity.getString(
                            R.string.status_platoon_csv_imported_with_checkpoint,
                            summary.retained,
                            summary.imported.imported,
                            summary.imported.historical,
                            summary.duplicates + summary.imported.skipped,
                        ),
                        long = true,
                    )
                },
                onFailure = { Feedback.show(activity, R.string.status_platoon_csv_import_failed, long = true) },
            )
        }
    }

    private data class Target(val scope: PlatoonStorageScope, val profile: PlatoonProfile)

    private data class PendingImport(
        val target: Target,
        val prepared: List<PlatoonCsvImportStore.PreparedImport>,
        val duplicateFileNames: Set<String>,
        val preview: CsvImportPreviewAnalyzer.Preview,
    )

    private data class Summary(
        val retained: Int,
        val duplicates: Int,
        val imported: PlatoonRepository.ImportResult,
    )

    private companion object {
        const val MAX_FILES = 64
        const val MAX_BYTES = 16L * 1024 * 1024
        const val STATE_TARGET = "csv_import.target"
        const val STATE_LAUNCH = "csv_import.launch_picker"
    }
}
