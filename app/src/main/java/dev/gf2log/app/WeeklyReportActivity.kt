package dev.gf2log.app

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.management.PlatoonPeriods
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.MemberEvent
import dev.gf2log.app.management.MemberEventType
import dev.gf2log.app.management.EvidenceSource
import dev.gf2log.app.management.MembershipEventPresentation
import dev.gf2log.app.management.DailyEvidence
import dev.gf2log.app.management.MetricCertainty
import dev.gf2log.app.management.WeeklyCellOverride
import dev.gf2log.app.management.WeeklyNote
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.management.WeeklyReportCsv
import dev.gf2log.app.management.WeeklyMetricPresentation
import dev.gf2log.app.settings.MemberOrderPreferences
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import dev.gf2log.app.settings.WeeklyCutlines
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class WeeklyReportActivity : LocalizedActivity() {
    private lateinit var repository: PlatoonRepository
    private lateinit var body: LinearLayout
    private var referenceDay: LocalDate =
        PlatoonPeriods.gameDay(Instant.now(), ZoneId.systemDefault())
    private var pendingCsv: String? = null
    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2WeeklyWorker")
    }
    private var editingPeriodStart: LocalDate? = null
    private val editDraft = mutableMapOf<CellKey, EditableCell>()
    private var renderGeneration = 0
    private var screenResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = PlatoonRepository(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(body, matchWidth()) })
    }

    override fun onResume() {
        super.onResume()
        screenResumed = true
        requestRender(reconcileRetainedCsv = true)
    }

    override fun onPause() {
        screenResumed = false
        renderGeneration += 1
        super.onPause()
    }

    override fun onDestroy() {
        workerExecutor.shutdownNow()
        super.onDestroy()
    }

    // Function Name: requestRender
    // Description:
    // - Loads the weekly projection and all supporting database state away from the main thread.
    // - Uses a generation token so late results cannot replace a newer week or a paused screen.
    // Parameters:
    // - reconcileRetainedCsv: Whether retained CSV files must be reconciled before loading.
    // Returns:
    // - Returns immediately after scheduling the load.
    private fun requestRender(reconcileRetainedCsv: Boolean = false) {
        val targetDay = referenceDay
        val generation = ++renderGeneration
        showLoading()
        workerExecutor.execute {
            val result = runCatching {
                if (reconcileRetainedCsv) repository.reconcileRetainedCsvFiles()
                loadRenderModel(targetDay)
            }
            runOnUiThread {
                if (!canApplyRender(generation)) return@runOnUiThread
                result.fold(
                    onSuccess = { render(it, generation) },
                    onFailure = { showLoadFailure() },
                )
            }
        }
    }

    // Function Name: loadRenderModel
    // Description:
    // - Reads every repository and preference value needed by one weekly screen render.
    // - Keeps later view construction free of database access and N+1 member tenure queries.
    // Parameters:
    // - targetDay: A date inside the requested reporting week.
    // Returns:
    // - An immutable render model for the requested week.
    private fun loadRenderModel(targetDay: LocalDate): RenderModel {
        val zone = ZoneId.systemDefault()
        val periodStart = PlatoonPeriods.weekStart(targetDay)
        val membershipStartInstant = periodStart.atStartOfDay(zone).toInstant()
        val membershipEndInstant = periodStart.plusDays(7).atStartOfDay(zone).toInstant()
        val report = repository.buildWeeklyReport(targetDay, zone)
        return RenderModel(
            zone = zone,
            report = report,
            notes = repository.listWeeklyNotes(report.periodStart.toEpochDay())
                .filterNot(WeeklyNote::isAutomatic),
            events = repository.listEvents(
                membershipStartInstant,
                membershipEndInstant,
                periodStart,
                periodStart.plusDays(7),
            ).filter {
                it.type in MembershipEventPresentation.displayedTypes &&
                    it.source in MembershipEventPresentation.displayedSources
            },
            namesByUid = repository.listMemberStatuses().associate { it.uid to it.name },
            cutlines = WeeklyCutlinePreferences(this).read(),
            displayedMembers = MemberOrderPreferences(this).apply(report.members) { it.uid },
            scoreRanks = report.members.withIndex().associate { it.value.uid to it.index + 1 },
        )
    }

    private fun canApplyRender(generation: Int): Boolean =
        generation == renderGeneration && screenResumed && !isFinishing && !isDestroyed

    private fun showLoading() {
        body.removeAllViews()
        body.addView(TextView(this).apply {
            text = getString(R.string.weekly_report_loading)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
        }, matchWidth())
    }

    private fun showLoadFailure() {
        body.removeAllViews()
        body.addView(TextView(this).apply {
            text = getString(R.string.weekly_report_load_failed)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(12))
        }, matchWidth())
        body.addView(Button(this).apply {
            text = getString(R.string.retry)
            setOnClickListener { requestRender() }
        }, matchWidth())
    }

    // Function Name: render
    // Description:
    // - Builds the weekly screen from an already-loaded immutable model.
    // - Delegates table rows to frame-sized batches so input remains responsive.
    // Parameters:
    // - model: Repository and preference state for one reporting week.
    // - generation: Token used to cancel stale row batches.
    // Returns:
    // - Returns after building the screen shell and scheduling table rows.
    private fun render(model: RenderModel, generation: Int) {
        body.removeAllViews()
        val zone = model.zone
        val report = model.report
        val isEditing = editingPeriodStart == report.periodStart

        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = getString(
                    if (report.isGunsmokeWeek) R.string.gunsmoke_week else R.string.off_week,
                )
                textSize = 27f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(ImageButton(context).apply {
                setImageResource(if (isEditing) R.drawable.ic_save else R.drawable.ic_edit)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = getString(
                    if (isEditing) R.string.save_weekly_edits else R.string.edit_weekly_table,
                )
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    if (isEditing) {
                        saveWeeklyEdits(report)
                    } else {
                        showManualEditWarning(report)
                    }
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_settings)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = getString(R.string.weekly_table_settings)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isEnabled = !isEditing
                alpha = if (isEditing) 0.35f else 1f
                setOnClickListener {
                    startActivity(
                        Intent(this@WeeklyReportActivity, WeeklySettingsActivity::class.java),
                    )
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, matchWidth())
        body.addView(TextView(this).apply {
            text = getString(
                R.string.week_period,
                report.periodStart.format(DATE),
                report.periodEnd.format(DATE),
                zone.id,
            )
            textSize = 15f
        }, matchWidth())
        if (report.hasIncompleteDailyEvidence) {
            body.addView(TextView(this).apply {
                text = getString(
                    if (report.isGunsmokeWeek) {
                        R.string.incomplete_daily_evidence_gunsmoke
                    } else {
                        R.string.incomplete_daily_evidence_standard
                    },
                )
                setTextColor(WARNING_COLOR)
                setPadding(0, dp(8), 0, dp(8))
            }, matchWidth())
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "\u2039"
                isEnabled = !isEditing
                contentDescription = getString(R.string.previous_week)
                setOnClickListener {
                    referenceDay = report.periodStart.minusDays(1)
                    requestRender()
                }
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(Button(context).apply {
                text = getString(R.string.current_week)
                isEnabled = !isEditing
                setOnClickListener { showDatePicker() }
            }, LinearLayout.LayoutParams(0, wrap(), 2f))
            addView(Button(context).apply {
                text = "\u203a"
                isEnabled = !isEditing
                contentDescription = getString(R.string.next_week)
                setOnClickListener {
                    referenceDay = report.periodEnd.plusDays(1)
                    requestRender()
                }
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
        }, matchWidth())
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = getString(R.string.export_weekly_csv)
                isEnabled = !isEditing
                setOnClickListener { exportWeeklyCsv(report) }
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(Button(context).apply {
                text = getString(R.string.copy_weekly_csv)
                isEnabled = !isEditing
                setOnClickListener { copyWeeklyCsv(report) }
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
        }, matchWidth())
        body.addView(Button(this).apply {
            text = getString(R.string.export_all_weekly_tables)
            isEnabled = !isEditing
            setOnClickListener { exportAllWeeklyTables() }
        }, matchWidth())
        body.addView(Button(this).apply {
            text = getString(R.string.edit_member_order)
            isEnabled = !isEditing
            setOnClickListener {
                startActivity(Intent(this@WeeklyReportActivity, MemberOrderActivity::class.java))
            }
        }, matchWidth())

        if (report.members.isEmpty()) {
            body.addView(TextView(this).apply {
                text = getString(R.string.no_weekly_data)
                setPadding(0, dp(16), 0, dp(16))
            }, matchWidth())
        } else {
            body.addView(buildTable(model, isEditing, generation), matchWidth())
        }

        if (isEditing) return

        addMembershipEvents(report, model.events, model.namesByUid, zone)
        body.addView(TextView(this).apply {
            text = getString(R.string.notes)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }, matchWidth())
        report.days.forEach { day ->
            val dayNotes = model.notes.filter { it.gameDay == day }
            if (dayNotes.isNotEmpty()) {
                body.addView(TextView(this).apply {
                    text = day.format(DATE)
                    setTypeface(typeface, Typeface.BOLD)
                }, matchWidth())
                dayNotes.forEach(::addNote)
            }
        }
        addNoteEditor(report)
    }

    private fun buildTable(
        model: RenderModel,
        isEditing: Boolean,
        generation: Int,
    ) = HorizontalScrollView(this).apply {
        isFillViewport = false
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerRow(model.report), wrapWidth())
        }
        addView(rows)
        appendMemberRows(rows, model, isEditing, generation)
    }

    // Function Name: appendMemberRows
    // Description:
    // - Creates only a small number of expensive nested table rows per display frame.
    // - Stops immediately when navigation or lifecycle changes invalidate this render.
    // Parameters:
    // - rows: Vertical table container receiving rendered member rows.
    // - model: Immutable data and display ordering for the selected week.
    // - isEditing: Whether rows contain editable fields.
    // - generation: Token identifying the active render request.
    // Returns:
    // - Returns after scheduling the first batch.
    private fun appendMemberRows(
        rows: LinearLayout,
        model: RenderModel,
        isEditing: Boolean,
        generation: Int,
    ) {
        var nextIndex = 0
        val appendBatch = object : Runnable {
            override fun run() {
                if (!canApplyRender(generation)) return
                val until = minOf(nextIndex + TABLE_ROW_BATCH_SIZE, model.displayedMembers.size)
                while (nextIndex < until) {
                    val member = model.displayedMembers[nextIndex++]
                    rows.addView(
                        memberRow(
                            member = member,
                            rank = model.scoreRanks[member.uid],
                            gunsmokeWeek = model.report.isGunsmokeWeek,
                            cutlines = model.cutlines,
                            isEditing = isEditing,
                            zoneId = model.zone,
                        ),
                        wrapWidth(),
                    )
                }
                if (nextIndex < model.displayedMembers.size) rows.postOnAnimation(this)
            }
        }
        rows.postOnAnimation(appendBatch)
    }

    private fun headerRow(report: WeeklyReportBuilder.Report) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        if (report.isGunsmokeWeek) {
            addView(gridCell(getString(R.string.rank), RANK_WIDTH, HEADER_HEIGHT, header = true))
        }
        addView(gridCell(getString(R.string.member), MEMBER_WIDTH, HEADER_HEIGHT, header = true))
        report.days.forEach {
            addView(gridCell(it.format(DAY), DAILY_WIDTH, HEADER_HEIGHT, header = true))
        }
        addView(gridCell(getString(R.string.total), DAILY_WIDTH, HEADER_HEIGHT, header = true))
    }

    private fun memberRow(
        member: WeeklyReportBuilder.MemberRow,
        rank: Int?,
        gunsmokeWeek: Boolean,
        cutlines: WeeklyCutlines,
        isEditing: Boolean,
        zoneId: ZoneId,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        val rowHeight = metricGroupHeight(gunsmokeWeek)
        if (gunsmokeWeek) {
            addView(gridCell(rank?.toString().orEmpty(), RANK_WIDTH, rowHeight))
        }
        addView(gridCell("${member.name}\n#${member.uid}", MEMBER_WIDTH, rowHeight, textSize = 15f))
        member.days.forEach { cell ->
            addView(
                if (isEditing) {
                    editableDailyMetricGroup(member.uid, cell, gunsmokeWeek)
                } else {
                    dailyMetricGroup(
                        cell = cell,
                        gunsmokeWeek = gunsmokeWeek,
                        dayClosed = !PlatoonPeriods.periodStartInstant(
                            cell.gameDay.plusDays(1),
                            zoneId,
                        ).isAfter(Instant.now()),
                        cutlines = cutlines,
                    )
                },
            )
        }
        addView(totalMetricGroup(member, gunsmokeWeek, cutlines))
    }

    private fun editableDailyMetricGroup(
        uid: Long,
        cell: WeeklyReportBuilder.DayCell,
        gunsmokeWeek: Boolean,
    ): LinearLayout {
        val key = CellKey(uid, cell.gameDay)
        val draft = requireNotNull(editDraft[key]) { "Missing weekly edit draft for $key" }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val groupHeight = metricGroupHeight(gunsmokeWeek)
            layoutParams = LinearLayout.LayoutParams(dp(DAILY_WIDTH), dp(groupHeight))
            addView(
                editableNumberCell(
                    label = getString(R.string.merit_short),
                    value = draft.merit,
                    width = DAILY_WIDTH,
                ) {
                    draft.merit = it
                    draft.meritDirty = true
                },
                LinearLayout.LayoutParams(dp(DAILY_WIDTH), dp(METRIC_HEIGHT)),
            )
            if (gunsmokeWeek) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    addView(
                        editableNumberCell(
                            label = getString(R.string.point_short),
                            value = draft.score,
                            width = DAILY_WIDTH / 2,
                        ) {
                            draft.score = it
                            draft.scoreDirty = true
                        },
                        LinearLayout.LayoutParams(0, dp(METRIC_HEIGHT), 1f),
                    )
                    addView(
                        editableNumberCell(
                            label = getString(R.string.attempt_short),
                            value = draft.attempts,
                            width = DAILY_WIDTH / 2,
                        ) {
                            draft.attempts = it
                            draft.attemptsDirty = true
                        },
                        LinearLayout.LayoutParams(0, dp(METRIC_HEIGHT), 1f),
                    )
                }, LinearLayout.LayoutParams(dp(DAILY_WIDTH), dp(METRIC_HEIGHT)))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                addView(
                    editableStatusCell(
                        getString(R.string.login_short),
                        draft.attended,
                        DAILY_WIDTH / 2,
                    ) { value ->
                        draft.attended = value
                        draft.attendedDirty = true
                    },
                    LinearLayout.LayoutParams(0, dp(METRIC_HEIGHT), 1f),
                )
                addView(
                    editableStatusCell(
                        getString(R.string.patrol_short),
                        draft.dailyPatrol,
                        DAILY_WIDTH / 2,
                    ) { value ->
                        draft.dailyPatrol = value
                        draft.dailyPatrolDirty = true
                    },
                    LinearLayout.LayoutParams(0, dp(METRIC_HEIGHT), 1f),
                )
            }, LinearLayout.LayoutParams(dp(DAILY_WIDTH), dp(METRIC_HEIGHT)))
        }
    }

    private fun editableNumberCell(
        label: String,
        value: String,
        width: Int,
        onChange: (String) -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(2), 0, dp(2), 0)
        background = gridBackground()
        layoutParams = LinearLayout.LayoutParams(dp(width), dp(METRIC_HEIGHT))
        addView(TextView(context).apply {
            text = label
            textSize = 8f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(matchParent(), 0, 0.38f))
        addView(EditText(context).apply {
            setText(value)
            textSize = 11f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setPadding(0, 0, 0, 0)
            background = editableFieldBackground()
            selectAll()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = onChange(text?.toString().orEmpty())

                override fun afterTextChanged(text: Editable?) = Unit
            })
        }, LinearLayout.LayoutParams(matchParent(), 0, 0.62f).apply {
            setMargins(dp(3), 0, dp(3), dp(2))
        })
    }

    private fun editableStatusCell(
        label: String,
        initialValue: Boolean?,
        width: Int,
        onChange: (Boolean?) -> Unit,
    ) = Button(this).apply {
        var value = initialValue
        isAllCaps = false
        textSize = 9f
        setPadding(0, 0, 0, 0)
        background = editableFieldBackground()
        layoutParams = LinearLayout.LayoutParams(dp(width), dp(METRIC_HEIGHT))

        fun update() {
            text = statusText(label, editableActivityMark(value))
        }
        update()
        setOnClickListener {
            value = when (value) {
                null -> true
                true -> false
                false -> null
            }
            onChange(value)
            update()
        }
    }

    private fun dailyMetricGroup(
        cell: WeeklyReportBuilder.DayCell,
        gunsmokeWeek: Boolean,
        dayClosed: Boolean,
        cutlines: WeeklyCutlines,
    ): LinearLayout {
        return metricGroup(
        showGunsmokeMetrics = gunsmokeWeek,
        merit = metricText(
            getString(R.string.merit_short),
            WeeklyMetricPresentation.format(cell.meritDelta, cell.meritCertainty),
            WeeklyMetricPresentation.warnsBelowCutline(
                cell.meritDelta,
                cell.meritCertainty,
                cutlines::belowDailyMerit,
            ),
        ),
        score = metricText(
            getString(R.string.point_short),
            if (gunsmokeWeek) {
                WeeklyMetricPresentation.format(cell.scoreDelta, cell.scoreCertainty)
            } else {
                "-"
            },
            gunsmokeWeek && WeeklyMetricPresentation.warnsBelowCutline(
                cell.scoreDelta,
                cell.scoreCertainty,
                cutlines::belowDailyScore,
            ),
        ),
        attempts = metricText(
            getString(R.string.attempt_short),
            if (gunsmokeWeek) {
                WeeklyMetricPresentation.format(
                    cell.attempts,
                    cell.attemptsCertainty,
                    missing = if (cell.manualOverride != null) "-" else "?",
                )
            } else {
                "-"
            },
            gunsmokeWeek && WeeklyMetricPresentation.warnsBelowCutline(
                cell.attempts,
                cell.attemptsCertainty,
                cutlines::belowDailyAttempts,
            ),
        ),
        login = statusText(
            getString(R.string.login_short),
            if (cell.attended == null && cell.evidence != DailyEvidence.MANUAL) {
                unknownActivityMark()
            } else {
                activityMark(cell.attended, cell.observed, dayClosed)
            },
        ),
        patrol = statusText(
            getString(R.string.patrol_short),
            if (cell.dailyPatrol == null && cell.evidence != DailyEvidence.MANUAL) {
                unknownActivityMark()
            } else {
                activityMark(cell.dailyPatrol, cell.observed, dayClosed)
            },
        ),
    )
    }

    private fun totalMetricGroup(
        member: WeeklyReportBuilder.MemberRow,
        gunsmokeWeek: Boolean,
        cutlines: WeeklyCutlines,
    ): LinearLayout {
        return metricGroup(
        showGunsmokeMetrics = gunsmokeWeek,
        merit = metricText(
            getString(R.string.merit_short),
            WeeklyMetricPresentation.format(member.totalMerit, member.totalMeritCertainty),
            WeeklyMetricPresentation.warnsBelowCutline(
                member.totalMerit,
                member.totalMeritCertainty,
                cutlines::belowWeeklyMerit,
            ),
        ),
        score = metricText(
            getString(R.string.point_short),
            if (gunsmokeWeek) {
                WeeklyMetricPresentation.format(member.totalScore, member.totalScoreCertainty)
            } else {
                "-"
            },
            gunsmokeWeek && WeeklyMetricPresentation.warnsBelowCutline(
                member.totalScore,
                member.totalScoreCertainty,
                cutlines::belowWeeklyScore,
            ),
        ),
        attempts = metricText(
            getString(R.string.attempt_short),
            if (gunsmokeWeek) {
                WeeklyMetricPresentation.format(
                    member.totalAttempts,
                    member.totalAttemptsCertainty,
                )
            } else {
                "-"
            },
            gunsmokeWeek && WeeklyMetricPresentation.warnsBelowCutline(
                member.totalAttempts,
                member.totalAttemptsCertainty,
                cutlines::belowWeeklyAttempts,
            ),
        ),
        login = metricText(
            getString(R.string.login_short),
            WeeklyMetricPresentation.format(member.loginDays, member.loginDaysCertainty),
            WeeklyMetricPresentation.warnsBelowCutline(
                member.loginDays,
                member.loginDaysCertainty,
                cutlines::belowWeeklyLoginDays,
            ),
        ),
        patrol = metricText(
            getString(R.string.patrol_short),
            WeeklyMetricPresentation.format(member.patrolDays, member.patrolDaysCertainty),
            WeeklyMetricPresentation.warnsBelowCutline(
                member.patrolDays,
                member.patrolDaysCertainty,
                cutlines::belowWeeklyPatrolDays,
            ),
        ),
    )
    }

    private fun metricGroup(
        showGunsmokeMetrics: Boolean,
        merit: CharSequence,
        score: CharSequence,
        attempts: CharSequence,
        login: CharSequence,
        patrol: CharSequence,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val groupHeight = metricGroupHeight(showGunsmokeMetrics)
        layoutParams = LinearLayout.LayoutParams(dp(DAILY_WIDTH), dp(groupHeight))
        addView(
            gridCell(merit, DAILY_WIDTH, METRIC_HEIGHT, textSize = 11f),
            LinearLayout.LayoutParams(dp(DAILY_WIDTH), dp(METRIC_HEIGHT)),
        )
        if (showGunsmokeMetrics) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                addView(
                    gridCell(score, DAILY_WIDTH / 2, METRIC_HEIGHT, textSize = 10f),
                    LinearLayout.LayoutParams(0, dp(METRIC_HEIGHT), 1f),
                )
                addView(
                    gridCell(attempts, DAILY_WIDTH / 2, METRIC_HEIGHT, textSize = 10f),
                    LinearLayout.LayoutParams(0, dp(METRIC_HEIGHT), 1f),
                )
            }, LinearLayout.LayoutParams(dp(DAILY_WIDTH), dp(METRIC_HEIGHT)))
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            addView(
                gridCell(login, DAILY_WIDTH / 2, METRIC_HEIGHT, textSize = 10f),
                LinearLayout.LayoutParams(0, dp(METRIC_HEIGHT), 1f),
            )
            addView(
                gridCell(patrol, DAILY_WIDTH / 2, METRIC_HEIGHT, textSize = 10f),
                LinearLayout.LayoutParams(0, dp(METRIC_HEIGHT), 1f),
            )
        }, LinearLayout.LayoutParams(dp(DAILY_WIDTH), dp(METRIC_HEIGHT)))
    }

    private fun metricGroupHeight(showGunsmokeMetrics: Boolean): Int =
        METRIC_HEIGHT * if (showGunsmokeMetrics) 3 else 2

    private fun gridCell(
        value: CharSequence,
        width: Int,
        height: Int,
        header: Boolean = false,
        textSize: Float = if (header) 12f else 11f,
    ) = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        this.textSize = textSize
        if (header) {
            setTextColor(Color.rgb(28, 32, 38))
            setTypeface(typeface, Typeface.BOLD)
        }
        setPadding(dp(4), dp(2), dp(4), dp(2))
        background = GradientDrawable().apply {
            setColor(if (header) Color.rgb(210, 222, 241) else Color.TRANSPARENT)
            setStroke(1, GRID_COLOR)
        }
        layoutParams = LinearLayout.LayoutParams(dp(width), dp(height))
    }

    private fun metricText(label: String, value: String, highlighted: Boolean): CharSequence {
        val result = SpannableString("$label\n$value")
        if (highlighted) {
            result.setSpan(
                ForegroundColorSpan(CUTLINE_YELLOW),
                label.length + 1,
                result.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return result
    }

    private fun statusText(label: String, mark: ActivityMark): CharSequence {
        val result = SpannableString("$label\n${mark.symbol}")
        mark.color?.let { color ->
            result.setSpan(
                ForegroundColorSpan(color),
                label.length + 1,
                result.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return result
    }

    private fun activityMark(value: Boolean?, observed: Boolean, dayClosed: Boolean): ActivityMark =
        when {
            value == true -> ActivityMark("\u2713", SUCCESS_GREEN)
            !observed -> ActivityMark("-", null)
            value == false && dayClosed -> ActivityMark("\u00d7", FAILURE_RED)
            value == false -> ActivityMark("-", null)
            else -> ActivityMark("?", null)
        }

    private fun editableActivityMark(value: Boolean?): ActivityMark = when (value) {
        true -> ActivityMark("\u2713", SUCCESS_GREEN)
        false -> ActivityMark("\u00d7", FAILURE_RED)
        null -> ActivityMark("-", null)
    }

    private fun unknownActivityMark() = ActivityMark("?", WARNING_COLOR)

    private fun showManualEditWarning(report: WeeklyReportBuilder.Report) {
        AlertDialog.Builder(this)
            .setTitle(R.string.manual_edit_warning_title)
            .setMessage(R.string.manual_edit_warning)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ -> beginWeeklyEditing(report) }
            .show()
    }

    private fun beginWeeklyEditing(report: WeeklyReportBuilder.Report) {
        editingPeriodStart = report.periodStart
        editDraft.clear()
        report.members.forEach { member ->
            member.days.forEach { cell ->
                editDraft[CellKey(member.uid, cell.gameDay)] = EditableCell(
                    uid = member.uid,
                    gameDay = cell.gameDay,
                    merit = cell.meritDelta?.toString().orEmpty(),
                    score = cell.scoreDelta?.toString().orEmpty(),
                    attempts = cell.attempts?.toString().orEmpty(),
                    attended = cell.attended,
                    dailyPatrol = cell.dailyPatrol,
                    existingOverride = cell.manualOverride,
                )
            }
        }
        requestRender()
    }

    private fun saveWeeklyEdits(report: WeeklyReportBuilder.Report) {
        if (editingPeriodStart != report.periodStart) {
            cancelWeeklyEditing()
            return
        }
        val overrides = runCatching {
            editDraft.values.mapNotNull { draft ->
                if (draft.existingOverride == null && !draft.isDirty) return@mapNotNull null
                WeeklyCellOverride(
                    uid = draft.uid,
                    periodStart = report.periodStart,
                    gameDay = draft.gameDay,
                    meritDelta = if (draft.meritDirty) {
                        parseNonNegativeLong(draft.merit)
                    } else {
                        draft.existingOverride?.meritDelta
                    },
                    scoreDelta = if (!report.isGunsmokeWeek) {
                        null
                    } else if (draft.scoreDirty) {
                        parseNonNegativeLong(draft.score)
                    } else {
                        draft.existingOverride?.scoreDelta
                    },
                    attempts = if (!report.isGunsmokeWeek) {
                        null
                    } else if (draft.attemptsDirty) {
                        parseAttempts(draft.attempts)
                    } else {
                        draft.existingOverride?.attempts
                    },
                    attended = if (draft.attendedDirty) {
                        draft.attended
                    } else {
                        draft.existingOverride?.attended
                    },
                    dailyPatrol = if (draft.dailyPatrolDirty) {
                        draft.dailyPatrol
                    } else {
                        draft.existingOverride?.dailyPatrol
                    },
                ).takeIf { it.hasAnyValue() }
            }
        }.getOrElse {
            Toast.makeText(this, R.string.invalid_weekly_edit, Toast.LENGTH_LONG).show()
            return
        }
        repository.replaceWeeklyOverrides(report.periodStart.toEpochDay(), overrides)
        cancelWeeklyEditing()
        Toast.makeText(this, R.string.weekly_edits_saved, Toast.LENGTH_SHORT).show()
        requestRender()
    }

    private fun cancelWeeklyEditing() {
        editingPeriodStart = null
        editDraft.clear()
    }

    private fun parseNonNegativeLong(value: String): Long? {
        if (value.isBlank()) return null
        return requireNotNull(value.toLongOrNull()).also { require(it >= 0) }
    }

    private fun parseAttempts(value: String): Int? {
        if (value.isBlank()) return null
        return requireNotNull(value.toIntOrNull()).also { require(it in 0..3) }
    }

    private fun showDatePicker() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                referenceDay = LocalDate.of(year, month + 1, day)
                requestRender()
            },
            referenceDay.year,
            referenceDay.monthValue - 1,
            referenceDay.dayOfMonth,
        ).show()
    }

    private fun copyWeeklyCsv(report: WeeklyReportBuilder.Report) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.weekly_csv_clipboard_label),
                WeeklyReportCsv.format(report),
            ),
        )
        Toast.makeText(this, R.string.weekly_csv_copied, Toast.LENGTH_SHORT).show()
    }

    private fun addMembershipEvents(
        report: WeeklyReportBuilder.Report,
        events: List<MemberEvent>,
        namesByUid: Map<Long, String>,
        zoneId: ZoneId,
    ) {
        body.addView(TextView(this).apply {
            text = getString(R.string.join_withdraw)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }, matchWidth())
        val membershipEvents = MembershipEventPresentation.deduplicate(events)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(membershipHeader(getString(R.string.join_label)), weightedCell())
            addView(membershipHeader(getString(R.string.withdraw_label)), weightedCell())
        }, matchWidth())

        val datedEvents = membershipEvents
            .mapNotNull { event ->
                MembershipEventPresentation.calendarDate(event, zoneId)?.let { it to event }
            }
            .groupBy({ it.first }, { it.second })
        report.days.forEach { day ->
            val dayEvents = datedEvents[day].orEmpty()
            if (dayEvents.isEmpty()) return@forEach
            body.addView(TextView(this).apply {
                text = day.format(DATE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            }, matchWidth())
            addMembershipEventRow(dayEvents, namesByUid, zoneId)
        }

        val unknownEvents = membershipEvents.filter {
            MembershipEventPresentation.calendarDate(it, zoneId) == null
        }
        if (unknownEvents.isNotEmpty()) {
            body.addView(TextView(this).apply {
                text = getString(R.string.unknown_date)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            }, matchWidth())
            addMembershipEventRow(unknownEvents, namesByUid, zoneId)
        }
    }

    private fun addMembershipEventRow(
        events: List<MemberEvent>,
        namesByUid: Map<Long, String>,
        zoneId: ZoneId,
    ) {
        val joins = events.filter {
            it.type == MemberEventType.JOINED || it.type == MemberEventType.REJOINED
        }
        val withdrawals = events.filter {
            it.type == MemberEventType.LEFT || it.type == MemberEventType.REMOVED
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(membershipEventCell(joins, namesByUid, zoneId), weightedCell())
            addView(membershipEventCell(withdrawals, namesByUid, zoneId), weightedCell())
        }, matchWidth())
    }

    private fun membershipHeader(label: String) = TextView(this).apply {
        text = label
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), dp(8), dp(4))
    }

    private fun weightedCell() = LinearLayout.LayoutParams(0, wrap(), 1f).apply {
        marginEnd = dp(8)
    }

    private fun membershipEventCell(
        events: List<MemberEvent>,
        namesByUid: Map<Long, String>,
        zoneId: ZoneId,
    ) = TextView(this).apply {
        text = events.joinToString("\n") { event ->
            val eventName = namesByUid[event.uid].orEmpty().ifBlank { event.note }
            val identity = eventName.takeIf { it.isNotBlank() }
                ?.let { "$it (#${event.uid})" }
                ?: "#${event.uid}"
            MembershipEventPresentation.timePrefix(event, zoneId)
                ?.let { "$it $identity" }
                ?: identity
        }
        setPadding(0, dp(2), dp(8), dp(4))
    }

    private fun addNote(note: WeeklyNote) {
        body.addView(Button(this).apply {
            isAllCaps = false
            text = note.text
            setOnClickListener { confirmNoteDeletion(note) }
        }, matchWidth())
    }

    private fun confirmNoteDeletion(note: WeeklyNote) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_note)
            .setMessage(R.string.delete_note_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (repository.deleteWeeklyNote(note.id)) {
                    Toast.makeText(this, R.string.note_deleted, Toast.LENGTH_SHORT).show()
                    requestRender()
                }
            }
            .show()
    }

    @Deprecated("Uses the platform document picker without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_WEEKLY) return
        val content = pendingCsv
        pendingCsv = null
        val destination = data?.data
        if (resultCode != RESULT_OK || destination == null || content == null) return
        val exported = runCatching {
            val output = TrustedExportDestination.openOutputStream(contentResolver, destination)
                ?: error("Document provider did not open an output stream")
            output.writer(Charsets.UTF_8).use { it.write(content) }
        }.isSuccess
        Toast.makeText(
            this,
            getString(if (exported) R.string.weekly_csv_exported else R.string.status_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    @Suppress("DEPRECATION")
    private fun exportWeeklyCsv(report: WeeklyReportBuilder.Report) {
        pendingCsv = WeeklyReportCsv.format(report)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(
                Intent.EXTRA_TITLE,
                "GF2logger-week-${report.periodStart.format(FILE_DATE)}.csv",
            )
        startActivityForResult(intent, REQUEST_EXPORT_WEEKLY)
    }

    private fun exportAllWeeklyTables() {
        workerExecutor.execute {
            val content = runCatching {
                repository.listAllWeeklyReports(ZoneId.systemDefault())
                    .takeIf(List<*>::isNotEmpty)
                    ?.let(WeeklyReportCsv::formatAll)
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (content == null) {
                    Toast.makeText(this, R.string.no_weekly_tables_to_export, Toast.LENGTH_SHORT)
                        .show()
                    return@runOnUiThread
                }
                pendingCsv = content
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_TITLE, "mobileGF2logger-all-weekly-tables.csv")
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_EXPORT_WEEKLY)
            }
        }
    }

    private fun addNoteEditor(report: WeeklyReportBuilder.Report) {
        val day = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@WeeklyReportActivity,
                android.R.layout.simple_spinner_dropdown_item,
                report.days.map { it.format(DATE) },
            )
        }
        val note = EditText(this).apply {
            hint = getString(R.string.note_hint)
            setSingleLine(true)
        }
        body.addView(day, matchWidth())
        body.addView(note, matchWidth())
        body.addView(Button(this).apply {
            text = getString(R.string.add_note)
            setOnClickListener {
                val text = note.text.toString().trim()
                if (text.isBlank()) return@setOnClickListener
                val gameDay = report.days[day.selectedItemPosition]
                repository.addWeeklyNote(
                    report.periodStart.toEpochDay(),
                    gameDay.toEpochDay(),
                    text,
                )
                Toast.makeText(
                    this@WeeklyReportActivity,
                    getString(R.string.saved),
                    Toast.LENGTH_SHORT,
                ).show()
                requestRender()
            }
        }, matchWidth())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchParent() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun wrapWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
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
    )

    private data class ActivityMark(val symbol: String, val color: Int?)

    private data class CellKey(val uid: Long, val gameDay: LocalDate)

    private data class EditableCell(
        val uid: Long,
        val gameDay: LocalDate,
        var merit: String,
        var score: String,
        var attempts: String,
        var attended: Boolean?,
        var dailyPatrol: Boolean?,
        val existingOverride: WeeklyCellOverride?,
        var meritDirty: Boolean = false,
        var scoreDirty: Boolean = false,
        var attemptsDirty: Boolean = false,
        var attendedDirty: Boolean = false,
        var dailyPatrolDirty: Boolean = false,
    ) {
        val isDirty: Boolean
            get() = meritDirty || scoreDirty || attemptsDirty || attendedDirty || dailyPatrolDirty
    }

    private fun WeeklyCellOverride.hasAnyValue(): Boolean =
        meritDelta != null || scoreDelta != null || attempts != null ||
            attended != null || dailyPatrol != null

    private fun gridBackground() = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(1, GRID_COLOR)
    }

    private fun editableFieldBackground() = GradientDrawable().apply {
        setColor(EDITABLE_FIELD_COLOR)
        setStroke(dp(1), EDITABLE_FIELD_BORDER_COLOR)
        cornerRadius = dp(3).toFloat()
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yy/MM/dd")
        private val DAY = DateTimeFormatter.ofPattern("MM/dd")
        private val FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE
        private const val REQUEST_EXPORT_WEEKLY = 201
        private const val HEADER_HEIGHT = 40
        private const val METRIC_HEIGHT = 36
        private const val RANK_WIDTH = 42
        private const val MEMBER_WIDTH = 120
        private const val DAILY_WIDTH = 128
        private const val TABLE_ROW_BATCH_SIZE = 2
        private val GRID_COLOR = Color.rgb(112, 118, 128)
        private val EDITABLE_FIELD_COLOR = Color.rgb(47, 58, 72)
        private val EDITABLE_FIELD_BORDER_COLOR = Color.rgb(126, 164, 218)
        private val WARNING_COLOR = Color.rgb(255, 193, 7)
        private val SUCCESS_GREEN = Color.rgb(45, 170, 75)
        private val FAILURE_RED = Color.rgb(215, 60, 55)
        private val CUTLINE_YELLOW = Color.rgb(232, 174, 22)
    }
}
