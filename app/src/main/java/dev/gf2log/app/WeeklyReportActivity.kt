package dev.gf2log.app

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.gf2log.app.management.MemberEvent
import dev.gf2log.app.management.PlatoonPeriods
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.management.WeeklyEvidenceAnalyzer
import dev.gf2log.app.management.WeeklyMemberNameProjection
import dev.gf2log.app.management.WeeklyNote
import dev.gf2log.app.management.WeeklyOverrideEditing
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.management.WeeklyReportStateHolder
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.MemberOrderPreferences
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import dev.gf2log.app.settings.WeeklyCutlines
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.MATCH
import dev.gf2log.app.ui.MenuEntry
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.SingleViewAdapter
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.iconButton
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.text
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The weekly table: a frozen member column, synced horizontal day columns, and a sticky header in
 * one vertical list. Tapping a cell explains its evidence; edit mode turns taps into manual edits.
 */
class WeeklyReportActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var scope: PlatoonStorageScope
    private lateinit var repository: PlatoonRepository
    private lateinit var reportState: WeeklyReportStateHolder
    private lateinit var screen: Screen
    private lateinit var list: RecyclerView
    private lateinit var stickyHeader: FrameLayout
    private lateinit var journal: WeeklyJournalSection
    private val work = BackgroundWork(this, "GF2Weekly")
    private val exports = WeeklyExportFlow(this, work, { repository }, { scope.storageId })

    private val topAdapter = SingleViewAdapter()
    private val headerAdapter = SingleViewAdapter()
    private val rowsAdapter = RowsAdapter()
    private val stateAdapter = SingleViewAdapter()
    private val bottomAdapter = SingleViewAdapter()

    private var model: RenderModel? = null
    private var table: WeeklyTable? = null

    /** One table per week kind, reused across renders so recycled rows and scroll position survive. */
    private val tables = HashMap<Boolean, WeeklyTable>()
    private val horizontalSync = HorizontalSync()
    private val edits = WeeklyEditSession()
    private val editingBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cancelEditing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        scope = profileBinding.scope ?: run {
            Feedback.show(this, R.string.no_platoon_detected_detail, long = true)
            finish()
            return
        }
        repository = PlatoonRepository(this, scope)
        journal = WeeklyJournalSection(this, work, SAVE, { repository }, ::requestRender)
        restoreState(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, editingBack)
        screen = Screen(this, PrimaryNavigation.Destination.WEEKLY)
        bottomAdapter.view = journal.view
        list = RecyclerView(this).apply {
            id = R.id.screen_list
            layoutManager = LinearLayoutManager(context)
            adapter = ConcatAdapter(topAdapter, headerAdapter, rowsAdapter, stateAdapter, bottomAdapter)
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateStickyHeader()
            })
        }
        stickyHeader = FrameLayout(this).apply { visibility = View.GONE }
        screen.setBody(list)
        (list.parent as ViewGroup).addView(stickyHeader, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        updateTopBar()
    }

    override fun onScreenResumed() {
        if (!profileBinding.isCurrent(this)) {
            recreate()
            return
        }
        reportState.onResume()
        requestRender(reconcileRetainedCsv = true)
    }

    override fun onPause() {
        if (::reportState.isInitialized) reportState.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::reportState.isInitialized) {
            outState.putLong(STATE_REFERENCE_DAY, reportState.referenceDay.toEpochDay())
        }
        exports.saveState(outState)
        if (::scope.isInitialized) edits.save(outState, scope.storageId)
        if (::journal.isInitialized) journal.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun restoreState(state: Bundle?) {
        exports.restoreState(state)
        reportState = WeeklyReportStateHolder(
            state?.takeIf { it.containsKey(STATE_REFERENCE_DAY) }
                ?.getLong(STATE_REFERENCE_DAY)
                ?.let(LocalDate::ofEpochDay)
                ?: PlatoonPeriods.gameDay(Instant.now(), GameTimeZonePreferences.get(this, scope.storageId)),
        )
        // Edits belong to one Platoon; a profile switch recreates this screen in another scope.
        edits.restore(state, scope.storageId)
        journal.restoreState(state)
    }

    // region Loading and rendering

    private fun requestRender(reconcileRetainedCsv: Boolean = false) {
        val request = reportState.newRenderRequest()
        if (model == null) stateAdapter.view = StateViews.loading(this, getString(R.string.weekly_report_loading))
        work.run(
            key = RENDER,
            task = {
                if (reconcileRetainedCsv) repository.reconcileRetainedCsvFiles()
                loadRenderModel(request.referenceDay)
            },
        ) { result ->
            result.fold(
                onSuccess = ::render,
                onFailure = {
                    model = null
                    topAdapter.view = null
                    headerAdapter.view = null
                    stickyHeader.visibility = View.GONE
                    rowsAdapter.submit(emptyList())
                    journal.clear()
                    stateAdapter.view = StateViews.failure(this, getString(R.string.weekly_report_load_failed)) {
                        requestRender()
                    }
                    updateTopBar()
                },
            )
        }
    }

    private fun loadRenderModel(targetDay: LocalDate): RenderModel {
        val zone = GameTimeZonePreferences.get(this, scope.storageId)
        val revision = repository.buildWeeklyTableRevision(targetDay, zone)
        val report = revision.report
        val history = repository.listWeeklyReportHistory(report.periodStart)
        return RenderModel(
            zone = zone,
            report = report,
            notes = revision.notes,
            events = revision.membershipEvents,
            namesByUid = WeeklyMemberNameProjection.merge(
                reportNamesByUid = report.members.associate { it.uid to it.name },
                capturedNamesByUid = revision.memberNamesByUid,
            ),
            cutlines = WeeklyCutlinePreferences(this, scope.storageId).read(),
            memberNotesByUid = revision.memberPrivateNotesByUid,
            displayedMembers = MemberOrderPreferences(this, scope.storageId).apply(report.members) { it.uid },
            scoreRanks = report.members.withIndex().associate { it.value.uid to it.index + 1 },
            historyCount = history.size,
            restoredHistory = history.any { it.active },
            currentWeekStart = PlatoonPeriods.weekStart(PlatoonPeriods.gameDay(Instant.now(), zone)),
        )
    }

    private fun render(next: RenderModel) {
        val report = next.report
        edits.retainFor(report.periodStart)
        model = next
        val weeklyTable = tables.getOrPut(report.isGunsmokeWeek) {
            WeeklyTable(this, report.isGunsmokeWeek, next.zone, next.cutlines, horizontalSync)
        }.apply {
            zone = next.zone
            cutlines = next.cutlines
            now = Instant.now()
        }
        table = weeklyTable
        topAdapter.view = buildTop(next)
        stickyHeader.removeAllViews()
        if (report.members.isEmpty()) {
            headerAdapter.view = null
            stateAdapter.view = StateViews.empty(
                this,
                R.drawable.ic_calendar,
                getString(R.string.no_weekly_data_title),
                getString(R.string.no_weekly_data),
            )
        } else {
            stateAdapter.view = null
            headerAdapter.view = weeklyTable.headerRow(report)
            stickyHeader.addView(weeklyTable.headerRow(report))
        }
        rowsAdapter.submit(next.displayedMembers)
        if (edits.isEditing(report.periodStart)) {
            journal.clear()
        } else {
            journal.render(WeeklyJournalSection.Content(report, next.notes, next.events, next.namesByUid, next.zone))
        }
        updateTopBar()
        list.post(::updateStickyHeader)
    }

    private fun updateStickyHeader() {
        val manager = list.layoutManager as? LinearLayoutManager ?: return
        if (headerAdapter.view == null) {
            stickyHeader.visibility = View.GONE
            return
        }
        val headerPosition = topAdapter.itemCount
        val lastRowPosition = headerPosition + rowsAdapter.itemCount
        val first = manager.findFirstVisibleItemPosition()
        val headerTop = manager.findViewByPosition(headerPosition)?.top ?: 0
        val scrolledPast = first > headerPosition || (first == headerPosition && headerTop < 0)
        stickyHeader.visibility = if (scrolledPast && first <= lastRowPosition) View.VISIBLE else View.GONE
    }

    private fun buildTop(next: RenderModel): View {
        val report = next.report
        val editing = edits.isEditing(report.periodStart)
        return column {
            setPadding(dp(16), 0, dp(16), dp(12))
            addView(PlatoonProfileSelector.card(this@WeeklyReportActivity, compact = true).apply {
                // Switching Platoons mid-edit would discard the draft without confirmation.
                isEnabled = !editing
                alpha = if (editing) 0.6f else 1f
            }, lp(top = 4))
            addView(weekNavigator(next, editing), lp(top = 12))
            if (next.restoredHistory) {
                addView(panel(emphasized = true, padding = 12) {
                    addView(text(getString(R.string.weekly_table_history_live_notice), TextRole.SECONDARY) {
                        setTextColor(getColor(R.color.accent_text))
                    }, lp())
                    addView(
                        button(getString(R.string.show_live_table), Controls.Role.SECONDARY) {
                            showLiveTable(report.periodStart)
                        },
                        lp(WRAP, WRAP, top = 8),
                    )
                }, lp(top = 8))
            }
            if (editing) {
                addView(panel(emphasized = true, padding = 12) {
                    addView(row {
                        addView(icon(R.drawable.ic_edit, R.color.accent_text, sizeDp = 20), lp(20, 20, end = 8))
                        addView(text(getString(R.string.weekly_edit_banner), TextRole.SECONDARY) {
                            setTextColor(getColor(R.color.accent_text))
                        }, lp(width = 0, weight = 1f))
                    }, lp())
                }, lp(top = 8))
            } else if (report.members.isNotEmpty()) {
                addView(evidencePanel(report), lp(top = 8))
            }
        }
    }

    private fun weekNavigator(next: RenderModel, editing: Boolean): View {
        val report = next.report
        val weekType = getString(if (report.isGunsmokeWeek) R.string.gunsmoke_week else R.string.off_week)
        return panel(padding = 4) {
            addView(row {
                addView(iconButton(R.drawable.ic_chevron_left, getString(R.string.previous_week)) {
                    reportState.showPreviousWeek(report.periodStart)
                    requestRender()
                }.apply { isEnabled = !editing }, lp(48, 48))
                addView(column {
                    gravity = Gravity.CENTER_HORIZONTAL
                    minimumHeight = dp(56)
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    background = selectableRect()
                    isEnabled = !editing
                    setOnClickListener { showDatePicker() }
                    addView(text(weekType, TextRole.LABEL) {
                        gravity = Gravity.CENTER
                        setTextColor(getColor(if (report.isGunsmokeWeek) R.color.accent_text else R.color.text_secondary))
                    }, lp())
                    val range = getString(
                        R.string.week_range,
                        Labels.format(Labels.DATE, report.periodStart),
                        Labels.format(Labels.DATE, report.periodEnd),
                    )
                    addView(text(range, TextRole.SUBTITLE) { gravity = Gravity.CENTER }, lp())
                    addView(text(getString(R.string.week_reset_zone, next.zone.id), TextRole.CAPTION) {
                        gravity = Gravity.CENTER
                    }, lp())
                    contentDescription = "$weekType. $range. ${getString(R.string.select_week)}"
                }, lp(width = 0, weight = 1f))
                addView(iconButton(R.drawable.ic_chevron_right, getString(R.string.next_week)) {
                    reportState.showNextWeek(report.periodEnd)
                    requestRender()
                }.apply { isEnabled = !editing }, lp(48, 48))
            }, lp())
            if (report.periodStart != next.currentWeekStart && !editing) {
                addView(
                    button(getString(R.string.go_to_current_week), Controls.Role.TERTIARY, R.drawable.ic_today) {
                        reportState.selectDate(PlatoonPeriods.gameDay(Instant.now(), next.zone))
                        requestRender()
                    },
                    lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL },
                )
            }
        }
    }

    private fun evidencePanel(report: WeeklyReportBuilder.Report): View {
        val health = WeeklyEvidenceAnalyzer.health(report)
        return panel(padding = 4) {
            addView(navigationRow(
                title = getString(if (health.isComplete) R.string.evidence_complete else R.string.evidence_incomplete),
                detail = getString(
                    R.string.evidence_health_compact,
                    health.observedDays,
                    health.totalDays,
                    health.exactMetrics,
                    health.lowerBoundMetrics,
                    health.unknownMetrics,
                ),
                icon = if (health.isComplete) R.drawable.ic_check_circle else R.drawable.ic_warning,
            ) { WeeklyDialogs.showGuide(this@WeeklyReportActivity, report.isGunsmokeWeek) }.apply {
                // The chevron carries the guide affordance so the counts keep the full row width.
                contentDescription = "$contentDescription. ${getString(R.string.how_it_works)}"
            }, lp())
        }
    }

    // endregion

    // region Top bar and actions

    private fun updateTopBar() {
        val current = model
        val editing = current != null && edits.isEditing(current.report.periodStart)
        editingBack.isEnabled = editing
        screen.setNavigationEnabled(!editing)
        val bar = screen.topBar
        if (editing) {
            bar.setNavigationIcon(R.drawable.ic_close, getString(R.string.cancel_editing))
                .setNavigation(::cancelEditing)
                .setTitle(
                    getString(R.string.edit_weekly_table_title),
                    resources.getQuantityString(R.plurals.edited_cells, edits.size, edits.size),
                )
                .setActions(listOf(
                    BarAction(R.drawable.ic_check, getString(R.string.save_weekly_edits), !edits.isEmpty() && !work.isBusy(SAVE)) {
                        saveEdits()
                    },
                ))
            return
        }
        val hasMembers = current?.report?.members?.isNotEmpty() == true
        val historyCount = current?.historyCount ?: 0
        bar.setNavigation(null)
            .setTitle(getString(R.string.weekly_table))
            .setActions(
                listOf(
                    BarAction(R.drawable.ic_share, getString(R.string.share_weekly_table), hasMembers) {
                        exports.share {
                            model?.let { WeeklyExportFlow.Source(it.report, it.displayedMembers, it.memberNotesByUid) }
                        }
                    },
                    BarAction(R.drawable.ic_edit, getString(R.string.edit_weekly_table), hasMembers) { confirmEditing() },
                ),
                overflow = listOf(
                    MenuEntry(getString(R.string.weekly_table_history_with_count, historyCount), historyCount > 0) {
                        current?.let { openHistory(it.report.periodStart) }
                    },
                    MenuEntry(getString(R.string.export_weekly_csv), hasMembers) {
                        current?.let { exports.exportWeek(it.report.periodStart) }
                    },
                    MenuEntry(getString(R.string.copy_weekly_csv), hasMembers) {
                        current?.let { exports.copyWeek(it.report) }
                    },
                    MenuEntry(getString(R.string.export_all_weekly_tables)) { exports.exportAll() },
                    MenuEntry(getString(R.string.edit_member_order)) {
                        startActivity(Intent(this, MemberOrderActivity::class.java))
                    },
                    MenuEntry(getString(R.string.weekly_table_settings)) {
                        startActivity(Intent(this, WeeklySettingsActivity::class.java))
                    },
                    MenuEntry(getString(R.string.weekly_evidence_guide)) {
                        WeeklyDialogs.showGuide(this, current?.report?.isGunsmokeWeek == true)
                    },
                ),
            )
    }

    private fun openHistory(periodStart: LocalDate) {
        startActivity(
            Intent(this, WeeklyTableHistoryActivity::class.java)
                .putExtra(WeeklyTableHistoryActivity.EXTRA_PERIOD_START, periodStart.toEpochDay()),
        )
    }

    private fun showLiveTable(periodStart: LocalDate) {
        work.run(task = { repository.showLiveWeeklyReport(periodStart) }) { requestRender() }
    }

    private fun showDatePicker() {
        val day = reportState.referenceDay
        Dialogs.showTracked(
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    reportState.selectDate(LocalDate.of(year, month + 1, dayOfMonth))
                    requestRender()
                },
                day.year,
                day.monthValue - 1,
                day.dayOfMonth,
            ),
        )
    }

    private fun onCellTapped(member: WeeklyReportBuilder.MemberRow, cell: WeeklyReportBuilder.DayCell?) {
        val current = model ?: return
        val weeklyTable = table ?: return
        val dayLabel = cell?.let { Labels.dayHeader(this, it.gameDay) } ?: getString(R.string.total)
        val title = getString(R.string.weekly_cell_title, member.name, dayLabel)
        if (edits.isEditing(current.report.periodStart)) {
            if (cell == null) {
                Feedback.show(this, R.string.total_not_editable)
                return
            }
            // The draft is being committed; edits made now would be cleared with it.
            if (work.isBusy(SAVE)) return
            val nextCell = member.days.getOrNull(member.days.indexOf(cell) + 1)
            val next = nextCell?.let { { onCellTapped(member, it) } }
            WeeklyDialogs.showEdit(this, title, current.report.isGunsmokeWeek, member.uid, cell, edits[member.uid, cell.gameDay], next) { edit ->
                if (!edits.isEditing(current.report.periodStart) || !edits.put(edit)) return@showEdit
                // Rows are addressed in the list the adapter currently shows, not the tapped model.
                rowsAdapter.notifyMemberChanged(member.uid)
                updateTopBar()
            }
            return
        }
        val explanations = WeeklyEvidenceAnalyzer.Metric.entries.associateWith { metric ->
            if (cell == null) {
                WeeklyEvidenceAnalyzer.explainTotal(member, metric)
            } else {
                WeeklyEvidenceAnalyzer.explainDaily(cell, metric)
            }
        }
        WeeklyDialogs.showEvidence(
            this,
            title,
            current.report.isGunsmokeWeek,
            cell?.let { weeklyTable.daily(it) } ?: weeklyTable.total(member),
            explanations,
        )
    }

    private fun confirmEditing() {
        if (model == null) return
        Dialogs.confirm(this, R.string.manual_edit_warning_title, R.string.manual_edit_warning, R.string.start_editing) {
            // A resume may have re-rendered while the warning was open; edit what is shown now.
            val current = model ?: return@confirm
            edits.start(current.report.periodStart)
            render(current)
        }
    }

    private fun cancelEditing() {
        if (edits.isEmpty()) {
            finishEditing()
            return
        }
        Dialogs.confirm(
            this,
            R.string.discard_weekly_edits_title,
            R.string.discard_weekly_edits,
            R.string.discard,
            destructive = true,
        ) { finishEditing() }
    }

    private fun finishEditing() {
        edits.finish()
        model?.let(::render) ?: updateTopBar()
    }

    private fun saveEdits() {
        val current = model ?: return
        val report = current.report
        val existing = report.members.flatMap { member -> member.days.mapNotNull { it.manualOverride } }
        when (val merged = WeeklyOverrideEditing.merge(report.periodStart, report.isGunsmokeWeek, existing, edits.values)) {
            is WeeklyOverrideEditing.Result.Invalid -> {
                val name = current.namesByUid[merged.uid] ?: Labels.uid(this, merged.uid)
                Feedback.show(
                    this,
                    getString(R.string.invalid_weekly_edit_cell, name, Labels.dayHeader(this, merged.gameDay)),
                    long = true,
                )
            }
            is WeeklyOverrideEditing.Result.Valid -> {
                work.run(
                    key = SAVE,
                    task = { repository.replaceWeeklyOverrides(report.periodStart.toEpochDay(), merged.overrides) },
                ) { result ->
                    if (result.isSuccess) {
                        edits.finish()
                        Feedback.show(this, R.string.weekly_edits_saved)
                        requestRender()
                    } else {
                        Feedback.show(this, R.string.save_failed)
                    }
                    updateTopBar()
                }
                updateTopBar()
            }
        }
    }

    // endregion

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private var members = emptyList<WeeklyReportBuilder.MemberRow>()

        fun submit(next: List<WeeklyReportBuilder.MemberRow>) {
            members = next
            notifyDataSetChanged()
        }

        fun notifyMemberChanged(uid: Long) {
            val position = members.indexOfFirst { it.uid == uid }
            if (position >= 0) notifyItemChanged(position)
        }

        override fun getItemCount() = members.size

        override fun getItemViewType(position: Int) = if (table?.isGunsmokeWeek == true) GUNSMOKE_ROW else STANDARD_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(requireNotNull(tables[viewType == GUNSMOKE_ROW]).MemberRow())

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val member = members[position]
            val current = model ?: return
            val dayEdits = edits.forMember(member.uid)
            val rank = current.scoreRanks[member.uid].takeIf { current.report.isGunsmokeWeek }
            holder.row.bind(member, rank, dayEdits) { cell -> onCellTapped(member, cell) }
        }
    }

    private class RowHolder(val row: WeeklyTable.MemberRow) : RecyclerView.ViewHolder(row.view)

    private data class RenderModel(
        val zone: ZoneId,
        val report: WeeklyReportBuilder.Report,
        val notes: List<WeeklyNote>,
        val events: List<MemberEvent>,
        val namesByUid: Map<Long, String>,
        val cutlines: WeeklyCutlines,
        val displayedMembers: List<WeeklyReportBuilder.MemberRow>,
        val scoreRanks: Map<Long, Int>,
        val memberNotesByUid: Map<Long, String>,
        val historyCount: Int,
        val restoredHistory: Boolean,
        val currentWeekStart: LocalDate,
    )

    private companion object {
        const val RENDER = "render"
        const val STANDARD_ROW = 0
        const val GUNSMOKE_ROW = 1
        const val SAVE = "save"
        const val STATE_REFERENCE_DAY = "weekly.reference_day"
    }
}
