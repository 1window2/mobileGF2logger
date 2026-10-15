package dev.gf2log.app

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.WeeklyEvidenceAnalyzer
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.SingleViewAdapter
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.text
import java.time.Instant
import java.time.LocalDate

/** Read-only view of one recorded weekly table with the explicit restore action. */
class WeeklyTableHistoryPreviewActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var repository: PlatoonRepository
    private val work = BackgroundWork(this, "GF2WeeklyHistoryPreview")
    private val topAdapter = SingleViewAdapter()
    private val headerAdapter = SingleViewAdapter()
    private val rowsAdapter = RowsAdapter()
    private val periodStart: LocalDate by lazy {
        LocalDate.ofEpochDay(intent.getLongExtra(WeeklyTableHistoryActivity.EXTRA_PERIOD_START, 0L))
    }
    private val historyId: Long by lazy { intent.getLongExtra(EXTRA_HISTORY_ID, -1L) }
    private var restoreButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        val scope = profileBinding.scope
        if (historyId < 0L || !intent.hasExtra(WeeklyTableHistoryActivity.EXTRA_PERIOD_START) || scope == null) {
            finish()
            return
        }
        repository = PlatoonRepository(this, scope)
        val zone = GameTimeZonePreferences.get(this, scope.storageId)
        val recordedAt = intent.getLongExtra(EXTRA_RECORDED_AT, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
        val screen = Screen(this)
        screen.topBar.setNavigation(::finish).setTitle(
            getString(R.string.weekly_table_history_preview),
            recordedAt?.let { Labels.format(Labels.DATE_TIME_SECONDS, it, zone) },
        )
        screen.setBody(RecyclerView(this).apply {
            id = R.id.screen_list
            layoutManager = LinearLayoutManager(context)
            adapter = ConcatAdapter(topAdapter, headerAdapter, rowsAdapter)
            itemAnimator = null
        })
        topAdapter.view = StateViews.loading(this, getString(R.string.weekly_report_loading))
        work.run(
            task = {
                repository.weeklyReportHistory(historyId, periodStart) to WeeklyCutlinePreferences(this, scope.storageId).read()
            },
        ) { result ->
            val (report, cutlines) = result.getOrNull() ?: (null to null)
            if (report == null || cutlines == null) {
                topAdapter.view = StateViews.empty(this, R.drawable.ic_history_restore, getString(R.string.weekly_table_history_unavailable))
                return@run
            }
            val table = WeeklyTable(this, report.isGunsmokeWeek, zone, cutlines)
            topAdapter.view = column {
                setPadding(dp(16), dp(4), dp(16), dp(12))
                addView(panel(emphasized = true, padding = 12) {
                    addView(text(getString(R.string.weekly_table_history_preview_notice), TextRole.SECONDARY) {
                        setTextColor(getColor(R.color.accent_text))
                    }, lp())
                    restoreButton = button(getString(R.string.weekly_table_history_restore), Controls.Role.PRIMARY) {
                        confirmRestore()
                    }
                    addView(restoreButton, lp(top = 8))
                }, lp())
            }
            if (report.members.isNotEmpty()) headerAdapter.view = table.headerRow(report)
            rowsAdapter.show(table, report)
        }
    }

    override fun onScreenResumed() {
        if (!profileBinding.isCurrent(this)) finish()
    }

    private fun confirmRestore() {
        Dialogs.confirm(
            this,
            R.string.weekly_table_history_restore,
            R.string.weekly_table_history_restore_warning,
            R.string.weekly_table_history_restore,
        ) confirm@{
            if (work.isBusy(RESTORE)) return@confirm
            restoreButton?.isEnabled = false
            work.run(key = RESTORE, task = { repository.restoreWeeklyReportHistory(historyId, periodStart) }) { result ->
                if (result.getOrDefault(false)) {
                    Feedback.show(this, R.string.weekly_table_history_restored)
                    startActivity(
                        Intent(this, WeeklyReportActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                } else {
                    restoreButton?.isEnabled = true
                    Feedback.show(this, R.string.weekly_table_history_restore_failed, long = true)
                }
            }
        }
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private var table: WeeklyTable? = null
        private var report: WeeklyReportBuilder.Report? = null

        fun show(nextTable: WeeklyTable, nextReport: WeeklyReportBuilder.Report) {
            table = nextTable
            report = nextReport
            notifyDataSetChanged()
        }

        override fun getItemCount() = report?.members?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowHolder(requireNotNull(table).MemberRow())

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val current = report ?: return
            val member = current.members[position]
            holder.row.bind(member, (position + 1).takeIf { current.isGunsmokeWeek }, emptyMap()) { cell ->
                val label = cell?.let { Labels.dayHeader(this@WeeklyTableHistoryPreviewActivity, it.gameDay) } ?: getString(R.string.total)
                WeeklyDialogs.showEvidence(
                    this@WeeklyTableHistoryPreviewActivity,
                    getString(R.string.weekly_cell_title, member.name, label),
                    current.isGunsmokeWeek,
                    cell?.let { requireNotNull(table).daily(it) } ?: requireNotNull(table).total(member),
                    WeeklyEvidenceAnalyzer.Metric.entries.associateWith { metric ->
                        if (cell == null) WeeklyEvidenceAnalyzer.explainTotal(member, metric) else WeeklyEvidenceAnalyzer.explainDaily(cell, metric)
                    },
                )
            }
        }
    }

    private class RowHolder(val row: WeeklyTable.MemberRow) : RecyclerView.ViewHolder(row.view)

    companion object {
        const val EXTRA_HISTORY_ID = "weekly_history_id"
        const val EXTRA_RECORDED_AT = "weekly_history_recorded_at"
        private const val RESTORE = "restore"
    }
}
