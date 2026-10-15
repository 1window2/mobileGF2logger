package dev.gf2log.app

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.gf2log.app.management.MemberEvent
import dev.gf2log.app.management.MemberEventType
import dev.gf2log.app.management.MembershipEventPresentation
import dev.gf2log.app.management.PlatoonPeriods
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.management.WeeklyEvidenceAnalyzer
import dev.gf2log.app.management.WeeklyMemberNameProjection
import dev.gf2log.app.management.WeeklyNote
import dev.gf2log.app.management.WeeklyNoteLimitException
import dev.gf2log.app.management.WeeklyOverrideEditing
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.management.WeeklyReportCsv
import dev.gf2log.app.management.WeeklyReportStateHolder
import dev.gf2log.app.management.WeeklyShareProjection
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.MemberOrderPreferences
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import dev.gf2log.app.settings.WeeklyCutlines
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.DocumentAccess
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
import dev.gf2log.app.ui.divider
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.iconButton
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField
import java.io.File
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
    private lateinit var bottomSections: LinearLayout
    private lateinit var noteInput: EditText
    private lateinit var noteDayButton: TextView
    private val work = BackgroundWork(this, "GF2Weekly")

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
    private var editingPeriod: LocalDate? = null
    private val edits = linkedMapOf<Pair<Long, LocalDate>, WeeklyOverrideEditing.CellEdit>()
    private var noteDay: LocalDate? = null
    private var pendingExport: String? = null
    private var pendingPng: File? = null

    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri == null || request == null) return@registerForActivityResult
        work.run(
            task = {
                val zone = GameTimeZonePreferences.get(this, scope.storageId)
                val csv = if (request == EXPORT_ALL) {
                    WeeklyReportCsv.formatAll(repository.listAllWeeklyReports(zone))
                } else {
                    WeeklyReportCsv.format(
                        repository.buildWeeklyTableRevision(LocalDate.ofEpochDay(request.toLong()), zone).report,
                    )
                }
                DocumentAccess.openOutput(contentResolver, uri).writer(Charsets.UTF_8).use { it.write(csv) }
            },
        ) { result ->
            Feedback.show(this, if (result.isSuccess) R.string.weekly_csv_exported else R.string.status_export_failed)
        }
    }

    private val exportPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val source = pendingPng
        pendingPng = null
        if (uri == null || source == null) {
            source?.delete()
            return@registerForActivityResult
        }
        work.run(
            task = {
                DocumentAccess.openOutput(contentResolver, uri).use { output ->
                    source.inputStream().use { it.copyTo(output) }
                }
            },
        ) { result ->
            source.delete()
            Feedback.show(this, if (result.isSuccess) R.string.weekly_png_saved else R.string.weekly_png_failed)
        }
    }

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
        restoreState(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, editingBack)
        screen = Screen(this, PrimaryNavigation.Destination.WEEKLY)
        buildNoteComposer()
        savedInstanceState?.getString(STATE_NOTE_DRAFT)?.let(noteInput::setText)
        bottomSections = column()
        bottomAdapter.view = column {
            setPadding(dp(16), 0, dp(16), dp(24))
            addView(bottomSections, lp())
        }
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
        WeeklyPngExport.nameForState(cacheDir, pendingPng)?.let { outState.putString(STATE_PENDING_PNG_NAME, it) }
        pendingExport?.let { outState.putString(STATE_PENDING_EXPORT, it) }
        editingPeriod?.let { period ->
            outState.putString(STATE_EDITING_SCOPE, scope.storageId)
            outState.putLong(STATE_EDITING_PERIOD, period.toEpochDay())
            outState.putParcelableArrayList(STATE_EDITS, ArrayList(edits.values.map(::encodeEdit)))
        }
        noteDay?.let { outState.putLong(STATE_NOTE_DAY, it.toEpochDay()) }
        // The composer lives inside the RecyclerView, which does not save its children's state.
        if (::noteInput.isInitialized) outState.putString(STATE_NOTE_DRAFT, noteInput.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun restoreState(state: Bundle?) {
        pendingPng = WeeklyPngExport.restore(cacheDir, state?.getString(STATE_PENDING_PNG_NAME))
        pendingExport = state?.getString(STATE_PENDING_EXPORT)
        reportState = WeeklyReportStateHolder(
            state?.takeIf { it.containsKey(STATE_REFERENCE_DAY) }
                ?.getLong(STATE_REFERENCE_DAY)
                ?.let(LocalDate::ofEpochDay)
                ?: PlatoonPeriods.gameDay(Instant.now(), GameTimeZonePreferences.get(this, scope.storageId)),
        )
        // Edits belong to one Platoon; a profile switch recreates this screen in another scope.
        if (state?.containsKey(STATE_EDITING_PERIOD) == true && state.getString(STATE_EDITING_SCOPE) == scope.storageId) {
            editingPeriod = LocalDate.ofEpochDay(state.getLong(STATE_EDITING_PERIOD))
            @Suppress("DEPRECATION")
            state.getParcelableArrayList<Bundle>(STATE_EDITS)
                ?.map(::decodeEdit)
                ?.forEach { edits[it.uid to it.gameDay] = it }
        }
        noteDay = state?.takeIf { it.containsKey(STATE_NOTE_DAY) }
            ?.getLong(STATE_NOTE_DAY)
            ?.let(LocalDate::ofEpochDay)
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
                    bottomSections.removeAllViews()
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
        if (editingPeriod != null && editingPeriod != report.periodStart) {
            editingPeriod = null
            edits.clear()
        }
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
        renderBottom(next)
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
        val editing = editingPeriod == report.periodStart
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
                addView(iconButton(R.drawable.ic_chevron_next, getString(R.string.next_week)) {
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
                trailing = getString(R.string.how_it_works),
            ) { WeeklyDialogs.showGuide(this@WeeklyReportActivity, report.isGunsmokeWeek) }, lp())
        }
    }

    private fun renderBottom(next: RenderModel) {
        bottomSections.removeAllViews()
        if (editingPeriod == next.report.periodStart) return
        renderMembershipEvents(next)
        bottomSections.addView(sectionHeader(getString(R.string.notes)), lp())
        val notesByDay = next.notes.groupBy(WeeklyNote::gameDay)
        if (notesByDay.isEmpty()) {
            bottomSections.addView(text(getString(R.string.no_notes), TextRole.SECONDARY) {
                setPadding(dp(4), 0, dp(4), dp(8))
            }, lp())
        } else {
            bottomSections.addView(panel(padding = 8) {
                next.report.days.filter { it in notesByDay }.forEachIndexed { index, day ->
                    if (index > 0) addView(divider(), lp(top = 4, bottom = 4))
                    addView(text(Labels.dayHeader(context, day), TextRole.LABEL) {
                        setPadding(dp(8), dp(4), dp(8), 0)
                    }, lp())
                    notesByDay[day].orEmpty().forEach { note -> addView(noteRow(note), lp()) }
                }
            }, lp())
        }
        bottomSections.addView(noteComposerPanel(next.report), lp(top = 8))
    }

    private fun renderMembershipEvents(next: RenderModel) {
        val events = MembershipEventPresentation.deduplicate(next.events)
        bottomSections.addView(sectionHeader(getString(R.string.join_withdraw)), lp())
        if (events.isEmpty()) {
            bottomSections.addView(text(getString(R.string.no_membership_events), TextRole.SECONDARY) {
                setPadding(dp(4), 0, dp(4), dp(8))
            }, lp())
            return
        }
        val byDay = events.groupBy { MembershipEventPresentation.calendarDate(it, next.zone) }
        val ordered: List<LocalDate?> = next.report.days.filter { it in byDay } +
            if (null in byDay) listOf(null) else emptyList()
        bottomSections.addView(panel(padding = 8) {
            ordered.forEachIndexed { index, day ->
                if (index > 0) addView(divider(), lp(top = 4, bottom = 4))
                addView(text(day?.let { Labels.dayHeader(context, it) } ?: getString(R.string.unknown_date), TextRole.LABEL) {
                    setPadding(dp(8), dp(4), dp(8), 0)
                }, lp())
                byDay[day].orEmpty().forEach { event -> addView(eventRow(event, next), lp()) }
            }
        }, lp())
    }

    private fun eventRow(event: MemberEvent, next: RenderModel): View {
        val joined = event.type == MemberEventType.JOINED || event.type == MemberEventType.REJOINED
        val label = getString(if (joined) R.string.join_label else R.string.withdraw_label)
        val name = next.namesByUid[event.uid].orEmpty().ifBlank { event.note }
        val identity = name.takeIf(String::isNotBlank)?.let { "$it (#${event.uid})" } ?: "#${event.uid}"
        val time = MembershipEventPresentation.timePrefix(event, next.zone)
        return row {
            minimumHeight = dp(40)
            setPadding(dp(8), 0, dp(8), 0)
            isFocusable = true
            contentDescription = listOfNotNull(label, time, identity).joinToString(". ")
            addView(icon(
                if (joined) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up,
                if (joined) R.color.success_mark else R.color.failure_mark,
                sizeDp = 18,
            ), lp(18, 18, end = 8))
            addView(text(label, TextRole.LABEL) {
                setTextColor(getColor(if (joined) R.color.success_text else R.color.destructive_action))
                minWidth = dp(44)
            }, lp(WRAP, WRAP, end = 8))
            addView(text(listOfNotNull(time, identity).joinToString("  "), TextRole.BODY) {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, lp(width = 0, weight = 1f))
        }
    }

    private fun noteRow(note: WeeklyNote): View = row {
        minimumHeight = dp(48)
        setPadding(dp(8), 0, 0, 0)
        addView(text(note.text, TextRole.BODY) { setTextIsSelectable(true) }, lp(width = 0, weight = 1f))
        if (!note.isAutomatic) {
            addView(
                iconButton(R.drawable.ic_delete, getString(R.string.delete_note_description, note.text.take(40)), R.color.text_secondary) {
                    confirmNoteDeletion(note)
                },
                lp(48, 48),
            )
        }
    }

    private fun buildNoteComposer() {
        noteInput = textField(getString(R.string.note_hint), id = R.id.weekly_note_input, singleLine = false, maxLength = MAX_NOTE_LENGTH)
        noteDayButton = text(null, TextRole.BODY) {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(8), 0)
            background = selectableRect()
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_today, 0, R.drawable.ic_expand_more, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_secondary))
        }
    }

    private fun noteComposerPanel(report: WeeklyReportBuilder.Report): View {
        val today = model?.let { PlatoonPeriods.gameDay(Instant.now(), it.zone) }
        if (noteDay !in report.days) noteDay = today?.takeIf { it in report.days } ?: report.days.first()
        updateNoteDay()
        noteDayButton.setOnClickListener {
            Dialogs.choose(
                this,
                getString(R.string.note_day),
                report.days.map { Labels.dayHeader(this, it) },
                report.days.indexOf(noteDay),
            ) {
                noteDay = report.days[it]
                updateNoteDay()
            }
        }
        (noteInput.parent as? ViewGroup)?.removeView(noteInput)
        (noteDayButton.parent as? ViewGroup)?.removeView(noteDayButton)
        return panel(padding = 12) {
            addView(text(getString(R.string.add_note), TextRole.SUBTITLE), lp())
            addView(noteDayButton, lp(top = 8))
            addView(noteInput, lp(top = 8))
            addView(button(getString(R.string.add_note), Controls.Role.PRIMARY) { addNote(report) }, lp(top = 8))
        }
    }

    private fun updateNoteDay() {
        val day = noteDay ?: return
        noteDayButton.text = Labels.dayHeader(this, day)
        noteDayButton.contentDescription = getString(R.string.note_day) + ": " + noteDayButton.text
    }

    // endregion

    // region Top bar and actions

    private fun updateTopBar() {
        val current = model
        val editing = current != null && editingPeriod == current.report.periodStart
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
                    BarAction(R.drawable.ic_check, getString(R.string.save_weekly_edits), edits.isNotEmpty() && !work.isBusy(SAVE)) {
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
                    BarAction(R.drawable.ic_share, getString(R.string.share_weekly_table), hasMembers) { share() },
                    BarAction(R.drawable.ic_edit, getString(R.string.edit_weekly_table), hasMembers) { confirmEditing() },
                ),
                overflow = listOf(
                    MenuEntry(getString(R.string.weekly_table_history_with_count, historyCount), historyCount > 0) {
                        current?.let { openHistory(it.report.periodStart) }
                    },
                    MenuEntry(getString(R.string.export_weekly_csv), hasMembers) { exportWeek() },
                    MenuEntry(getString(R.string.copy_weekly_csv), hasMembers) { copyWeek() },
                    MenuEntry(getString(R.string.export_all_weekly_tables)) { exportAll() },
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
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                reportState.selectDate(LocalDate.of(year, month + 1, dayOfMonth))
                requestRender()
            },
            day.year,
            day.monthValue - 1,
            day.dayOfMonth,
        ).show()
    }

    private fun onCellTapped(member: WeeklyReportBuilder.MemberRow, cell: WeeklyReportBuilder.DayCell?) {
        val current = model ?: return
        val weeklyTable = table ?: return
        val dayLabel = cell?.let { Labels.dayHeader(this, it.gameDay) } ?: getString(R.string.total)
        val title = getString(R.string.weekly_cell_title, member.name, dayLabel)
        if (editingPeriod == current.report.periodStart) {
            if (cell == null) {
                Feedback.show(this, R.string.total_not_editable)
                return
            }
            val key = member.uid to cell.gameDay
            WeeklyDialogs.showEdit(this, title, current.report.isGunsmokeWeek, member.uid, cell, edits[key]) { edit ->
                if (edit.isEmpty) edits.remove(key) else edits[key] = edit
                rowsAdapter.notifyItemChanged(current.displayedMembers.indexOfFirst { it.uid == member.uid })
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
        val current = model ?: return
        Dialogs.confirm(this, R.string.manual_edit_warning_title, R.string.manual_edit_warning, R.string.start_editing) {
            editingPeriod = current.report.periodStart
            edits.clear()
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
        editingPeriod = null
        edits.clear()
        model?.let(::render) ?: updateTopBar()
    }

    private fun saveEdits() {
        val current = model ?: return
        val report = current.report
        val existing = report.members.flatMap { member -> member.days.mapNotNull { it.manualOverride } }
        when (val merged = WeeklyOverrideEditing.merge(report.periodStart, report.isGunsmokeWeek, existing, edits.values)) {
            is WeeklyOverrideEditing.Result.Invalid -> {
                val name = current.namesByUid[merged.uid] ?: "#${merged.uid}"
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
                        editingPeriod = null
                        edits.clear()
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

    private fun exportWeek() {
        val current = model ?: return
        pendingExport = current.report.periodStart.toEpochDay().toString()
        exportCsv.launch("GF2logger-week-${Labels.format(Labels.FILE_DATE, current.report.periodStart)}.csv")
    }

    private fun exportAll() {
        work.run(
            task = { repository.listAllWeeklyReports(GameTimeZonePreferences.get(this, scope.storageId)).isNotEmpty() },
        ) { result ->
            if (result.getOrDefault(false)) {
                pendingExport = EXPORT_ALL
                exportCsv.launch("GF2logger-all-weekly-tables.csv")
            } else {
                Feedback.show(this, R.string.no_weekly_tables_to_export)
            }
        }
    }

    private fun copyWeek() {
        val report = model?.report ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.weekly_csv_clipboard_label), WeeklyReportCsv.format(report)),
        )
        Feedback.show(this, R.string.weekly_csv_copied)
    }

    private fun share() {
        val current = model ?: return
        WeeklyDialogs.showShare(this) { privacy, shareAfter ->
            Feedback.show(this, R.string.weekly_png_rendering)
            work.run(
                task = {
                    WeeklyPngExport.render(
                        this,
                        WeeklyShareProjection.build(
                            current.report,
                            current.displayedMembers,
                            current.memberNotesByUid,
                            privacy,
                            WeeklyPngExport.labels(this),
                        ),
                        current.report.periodStart,
                    )
                },
            ) { result ->
                val file = result.getOrNull()
                when {
                    file == null -> Feedback.show(this, R.string.weekly_png_failed, long = true)
                    shareAfter -> WeeklyPngExport.share(this, file)
                    else -> {
                        pendingPng = file
                        exportPng.launch(WeeklyPngExport.exportName(current.report.periodStart))
                    }
                }
            }
        }
    }

    private fun addNote(report: WeeklyReportBuilder.Report) {
        val text = noteInput.text.toString().trim()
        val day = noteDay ?: return
        if (text.isEmpty()) {
            noteInput.error = getString(R.string.note_required)
            return
        }
        work.run(
            key = SAVE,
            task = { repository.addWeeklyNote(report.periodStart.toEpochDay(), day.toEpochDay(), text) },
        ) { result ->
            result.fold(
                onSuccess = {
                    noteInput.text = null
                    Feedback.show(this, R.string.note_added)
                    requestRender()
                },
                onFailure = { error ->
                    Feedback.show(
                        this,
                        if (error is WeeklyNoteLimitException) R.string.weekly_note_limit_reached else R.string.save_failed,
                    )
                },
            )
        }
    }

    private fun confirmNoteDeletion(note: WeeklyNote) {
        Dialogs.confirm(this, getString(R.string.delete_note), note.text, getString(R.string.delete), destructive = true) {
            work.run(task = { repository.deleteWeeklyNote(note.id) }) { result ->
                if (result.getOrDefault(false)) {
                    Feedback.show(this, R.string.note_deleted)
                    requestRender()
                }
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

        override fun getItemCount() = members.size

        override fun getItemViewType(position: Int) = if (table?.isGunsmokeWeek == true) GUNSMOKE_ROW else STANDARD_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(requireNotNull(tables[viewType == GUNSMOKE_ROW]).MemberRow())

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val member = members[position]
            val current = model ?: return
            val dayEdits = edits.values.filter { it.uid == member.uid }.associateBy { it.gameDay }
            val rank = current.scoreRanks[member.uid].takeIf { current.report.isGunsmokeWeek }
            holder.row.bind(member, rank, dayEdits) { cell -> onCellTapped(member, cell) }
        }
    }

    private class RowHolder(val row: WeeklyTable.MemberRow) : RecyclerView.ViewHolder(row.view)

    private fun encodeEdit(edit: WeeklyOverrideEditing.CellEdit) = Bundle().apply {
        putLong("uid", edit.uid)
        putLong("day", edit.gameDay.toEpochDay())
        edit.merit?.let { putString("merit", it) }
        edit.score?.let { putString("score", it) }
        edit.attempts?.let { putString("attempts", it) }
        edit.attended?.let { putString("attended", it.name) }
        edit.dailyPatrol?.let { putString("patrol", it.name) }
    }

    private fun decodeEdit(bundle: Bundle) = WeeklyOverrideEditing.CellEdit(
        uid = bundle.getLong("uid"),
        gameDay = LocalDate.ofEpochDay(bundle.getLong("day")),
        merit = bundle.getString("merit"),
        score = bundle.getString("score"),
        attempts = bundle.getString("attempts"),
        attended = bundle.getString("attended")?.let(WeeklyOverrideEditing.Choice::valueOf),
        dailyPatrol = bundle.getString("patrol")?.let(WeeklyOverrideEditing.Choice::valueOf),
    )

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
        const val EXPORT_ALL = "all"
        const val MAX_NOTE_LENGTH = 1_000
        const val STATE_REFERENCE_DAY = "weekly.reference_day"
        const val STATE_PENDING_PNG_NAME = "weekly.pending_png_name"
        const val STATE_PENDING_EXPORT = "weekly.pending_export"
        const val STATE_EDITING_PERIOD = "weekly.editing_period"
        const val STATE_EDITING_SCOPE = "weekly.editing_scope"
        const val STATE_EDITS = "weekly.edits"
        const val STATE_NOTE_DAY = "weekly.note_day"
        const val STATE_NOTE_DRAFT = "weekly.note_draft"
    }
}
