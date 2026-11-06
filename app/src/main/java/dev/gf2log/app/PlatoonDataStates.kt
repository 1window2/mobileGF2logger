package dev.gf2log.app

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.annotation.StringRes
import dev.gf2log.app.management.PlatoonAccessFailure
import dev.gf2log.app.management.PlatoonDatabaseDamage
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.CaptureGuard
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.StateViews

/**
 * What a screen shows when its Platoon data could not be read. A damaged database blocks the
 * screen until a backup is restored or the Platoon is rebuilt; a CSV import that holds the
 * Platoon asks to retry shortly; anything else keeps the screen's own failure message.
 */
internal object PlatoonDataStates {
    private const val REBUILD = "platoon_database.rebuild"

    fun failure(
        activity: Activity,
        work: BackgroundWork,
        scope: PlatoonStorageScope,
        error: Throwable?,
        failureMessage: CharSequence,
        onRetry: () -> Unit,
    ): View = when (PlatoonAccessFailure.of(error)) {
        PlatoonAccessFailure.DATABASE_DAMAGED -> damaged(activity, work, scope)
        PlatoonAccessFailure.IMPORT_IN_PROGRESS -> StateViews.empty(
            activity,
            R.drawable.ic_upload,
            activity.getString(R.string.platoon_csv_import_in_progress_title),
            activity.getString(R.string.platoon_csv_import_in_progress_detail),
            activity.getString(R.string.retry),
            onRetry,
        )
        PlatoonAccessFailure.OTHER -> StateViews.failure(activity, failureMessage, onRetry)
    }

    /** Restoring is offered first: it is the only way back to the data the rosters do not hold. */
    fun damaged(activity: Activity, work: BackgroundWork, scope: PlatoonStorageScope): View = StateViews.blocked(
        activity,
        R.drawable.ic_warning,
        activity.getString(R.string.platoon_database_damaged_title),
        activity.getString(R.string.platoon_database_damaged_detail),
        listOf(
            StateViews.Action(activity.getString(R.string.restore_full_backup), Controls.Role.PRIMARY) {
                openBackupRestore(activity)
            },
            StateViews.Action(activity.getString(R.string.rebuild_platoon_database), Controls.Role.DESTRUCTIVE_TEXT) {
                confirmRebuild(activity, work, REBUILD, scope, R.string.platoon_database_rebuild_warning)
            },
        ),
    )

    /** Settings owns the backup picker; it opens straight into the restore confirmation. */
    fun openBackupRestore(activity: Activity) {
        activity.startActivity(
            Intent(activity, OptionsActivity::class.java).putExtra(OptionsActivity.EXTRA_RESTORE_BACKUP, true),
        )
    }

    /**
     * Confirms, waits for capture to stop, then sets the damaged database aside and rebuilds it
     * from the retained roster CSVs; the screen showing afterwards reloads on the rebuilt data.
     * [key] serializes it with the hosting screen's other maintenance.
     */
    fun confirmRebuild(
        activity: Activity,
        work: BackgroundWork,
        key: String,
        scope: PlatoonStorageScope,
        @StringRes message: Int,
    ) {
        Dialogs.confirm(
            activity,
            R.string.platoon_database_damaged_title,
            message,
            R.string.rebuild_platoon_database,
            destructive = true,
        ) {
            CaptureGuard.whenStopped(activity, R.string.stop_capture_before_rebuild) {
                if (work.isBusy(key)) return@whenStopped
                work.run(
                    key = key,
                    task = {
                        // Checked again where it runs: standby or an alarm may have started capture.
                        CaptureGuard.whileStopped { PlatoonDatabaseDamage.rebuildFromRetainedCsv(activity, scope) }
                            ?: throw CaptureGuard.CaptureActiveException()
                    },
                    // Runs even if the screen was recreated meanwhile; the showing screen reloads.
                    always = { result ->
                        Feedback.show(activity, rebuildResultMessage(result.exceptionOrNull()), long = true)
                        if (result.isSuccess) {
                            (activity.takeUnless { it.isFinishing || it.isDestroyed } ?: BaseActivity.foregroundScreen())
                                ?.recreate()
                        }
                    },
                )
            }
        }
    }
}

/** [fallback] for an ordinary failure; a cause the user can act on is named instead. */
@StringRes
internal fun platoonDataFailureMessage(error: Throwable?, @StringRes fallback: Int): Int =
    when (PlatoonAccessFailure.of(error)) {
        PlatoonAccessFailure.DATABASE_DAMAGED -> R.string.platoon_database_damaged_short
        PlatoonAccessFailure.IMPORT_IN_PROGRESS -> R.string.platoon_csv_import_in_progress
        PlatoonAccessFailure.OTHER -> fallback
    }

/** The outcome of a rebuild; a refusal says what to wait for, since the damaged copy is kept. */
@StringRes
internal fun rebuildResultMessage(error: Throwable?): Int = when {
    error == null -> R.string.platoon_database_rebuilt
    error is CaptureGuard.CaptureActiveException -> R.string.stop_capture_before_rebuild
    PlatoonAccessFailure.of(error) == PlatoonAccessFailure.IMPORT_IN_PROGRESS -> R.string.platoon_csv_import_in_progress
    else -> R.string.platoon_database_rebuild_failed
}
