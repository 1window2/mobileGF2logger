package dev.gf2log.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.management.WeeklyReportCsv
import dev.gf2log.app.management.WeeklyShareProjection
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.DocumentAccess
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import java.io.File
import java.time.LocalDate

/**
 * Weekly-table exports: CSV documents for one or every week, clipboard CSV, and the privacy-filtered
 * PNG that is shared or saved. The pending request survives recreation and process death, so a
 * document picker that returns to a new instance still writes what the user asked for.
 */
internal class WeeklyExportFlow(
    private val activity: ComponentActivity,
    private val work: BackgroundWork,
    private val repository: () -> PlatoonRepository,
    private val storageId: () -> String,
) {
    /** What a PNG is rendered from: the table as currently ordered and annotated on screen. */
    class Source(
        val report: WeeklyReportBuilder.Report,
        val displayedMembers: List<WeeklyReportBuilder.MemberRow>,
        val memberNotesByUid: Map<Long, String>,
    )

    private var pendingCsv: String? = null
    private var pendingPng: File? = null

    private val csvDocument = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val request = pendingCsv
        pendingCsv = null
        if (uri == null || request == null) return@registerForActivityResult
        work.run(
            task = {
                val zone = GameTimeZonePreferences.get(activity, storageId())
                val csv = if (request == EXPORT_ALL) {
                    WeeklyReportCsv.formatAll(repository().listAllWeeklyReports(zone))
                } else {
                    // Exporting reads the displayed table; it never adds a history revision.
                    WeeklyReportCsv.format(repository().buildWeeklyReport(LocalDate.ofEpochDay(request.toLong()), zone))
                }
                DocumentAccess.openOutput(activity.contentResolver, uri).writer(Charsets.UTF_8).use { it.write(csv) }
            },
        ) { result ->
            Feedback.show(activity, if (result.isSuccess) R.string.weekly_csv_exported else R.string.weekly_csv_export_failed)
        }
    }

    private val pngDocument = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val source = pendingPng
        pendingPng = null
        if (uri == null || source == null) {
            source?.delete()
            return@registerForActivityResult
        }
        work.run(
            task = {
                DocumentAccess.openOutput(activity.contentResolver, uri).use { output ->
                    source.inputStream().use { it.copyTo(output) }
                }
            },
        ) { result ->
            source.delete()
            Feedback.show(activity, if (result.isSuccess) R.string.weekly_png_saved else R.string.weekly_png_failed)
        }
    }

    fun saveState(outState: Bundle) {
        WeeklyPngExport.nameForState(activity.cacheDir, pendingPng)?.let { outState.putString(STATE_PENDING_PNG_NAME, it) }
        pendingCsv?.let { outState.putString(STATE_PENDING_CSV, it) }
    }

    fun restoreState(state: Bundle?) {
        pendingPng = WeeklyPngExport.restore(activity.cacheDir, state?.getString(STATE_PENDING_PNG_NAME))
        pendingCsv = state?.getString(STATE_PENDING_CSV)
    }

    fun exportWeek(periodStart: LocalDate) {
        pendingCsv = periodStart.toEpochDay().toString()
        csvDocument.launch("GF2logger-week-${Labels.format(Labels.FILE_DATE, periodStart)}.csv")
    }

    fun exportAll() {
        work.run(
            task = { repository().hasWeeklyReports(GameTimeZonePreferences.get(activity, storageId())) },
        ) { result ->
            if (result.getOrDefault(false)) {
                pendingCsv = EXPORT_ALL
                csvDocument.launch("GF2logger-all-weekly-tables.csv")
            } else {
                Feedback.show(activity, R.string.no_weekly_tables_to_export)
            }
        }
    }

    fun copyWeek(report: WeeklyReportBuilder.Report) {
        Feedback.copy(
            activity,
            activity.getString(R.string.weekly_csv_clipboard_label),
            WeeklyReportCsv.format(report),
            R.string.weekly_csv_copied,
        )
    }

    /** Asks for the privacy options, then shares or saves a PNG of what [source] returns then. */
    fun share(source: () -> Source?) {
        if (source() == null || work.isBusy(PNG)) return
        WeeklyDialogs.showShare(activity) { privacy, shareAfter ->
            // A second share sheet opened by a double tap must not start another render.
            val current = source() ?: return@showShare
            if (work.isBusy(PNG)) return@showShare
            val periodStart = current.report.periodStart
            Feedback.show(activity, R.string.weekly_png_rendering)
            work.run(
                key = PNG,
                task = {
                    WeeklyPngExport.render(
                        activity,
                        WeeklyShareProjection.build(
                            current.report,
                            current.displayedMembers,
                            current.memberNotesByUid,
                            privacy,
                            WeeklyPngExport.labels(activity),
                        ),
                        periodStart,
                    )
                },
            ) { result ->
                val file = result.getOrNull()
                when {
                    file == null -> Feedback.show(activity, R.string.weekly_png_failed, long = true)
                    shareAfter -> WeeklyPngExport.share(activity, file)
                    else -> {
                        pendingPng = file
                        pngDocument.launch(WeeklyPngExport.exportName(periodStart))
                    }
                }
            }
        }
    }

    private companion object {
        const val PNG = "png"
        const val EXPORT_ALL = "all"
        const val STATE_PENDING_PNG_NAME = "weekly.pending_png_name"
        const val STATE_PENDING_CSV = "weekly.pending_export"
    }
}
