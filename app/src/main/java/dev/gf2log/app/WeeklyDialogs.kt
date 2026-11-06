package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import dev.gf2log.app.management.MetricCertainty
import dev.gf2log.app.management.WeeklyEvidenceAnalyzer
import dev.gf2log.app.management.WeeklyEvidenceAnalyzer.Metric
import dev.gf2log.app.management.WeeklyMetricPresentation
import dev.gf2log.app.management.WeeklyOverrideEditing
import dev.gf2log.app.management.WeeklyOverrideEditing.Choice
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.management.WeeklyShareProjection
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.LabeledField
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.badge
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.divider
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.switchRow
import dev.gf2log.app.ui.errorLine
import dev.gf2log.app.ui.showMessage
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField

/** Weekly-table dialogs: evidence explanations, manual cell edits, the guide, and sharing. */
internal object WeeklyDialogs {
    /** Explains every metric of one member-day (or weekly total) in a single sheet. */
    fun showEvidence(
        activity: Activity,
        title: String,
        isGunsmokeWeek: Boolean,
        display: CellDisplay,
        explanations: Map<Metric, WeeklyEvidenceAnalyzer.Explanation>,
    ) {
        // Each value carries the words the table cell speaks for it, never the glyph's name.
        val metrics = buildList {
            add(Triple(Metric.MERIT, display.merit, CellSpeech.value(activity, display.merit)))
            if (isGunsmokeWeek) {
                val score = display.score ?: "-"
                val attempts = display.attempts ?: "-"
                add(Triple(Metric.SCORE, score, CellSpeech.value(activity, score)))
                add(Triple(Metric.ATTEMPTS, attempts, CellSpeech.value(activity, attempts)))
            }
            add(Triple(Metric.LOGIN, display.login.symbol, CellSpeech.mark(activity, display.login)))
            add(Triple(Metric.DAILY_PATROL, display.patrol.symbol, CellSpeech.mark(activity, display.patrol)))
        }
        val content = Dialogs.content(activity) {
            metrics.forEachIndexed { index, (metric, value, spoken) ->
                val explanation = explanations[metric] ?: return@forEachIndexed
                if (index > 0) addView(activity.divider(), activity.lp(top = 12, bottom = 12))
                addView(activity.row {
                    addView(activity.text(metricLabel(activity, metric), TextRole.SUBTITLE), activity.lp(width = 0, weight = 1f))
                    addView(activity.text(value, TextRole.TITLE) {
                        gravity = Gravity.END
                        contentDescription = spoken
                    }, activity.lp(WRAP, WRAP, start = 8))
                }, activity.lp())
                addView(certaintyBadge(activity, explanation.certainty), activity.lp(WRAP, WRAP, top = 4))
                explanation.facts.ifEmpty { listOf(WeeklyEvidenceAnalyzer.Fact.NO_OBSERVATION) }.forEach { fact ->
                    addView(activity.text("• " + activity.getString(factString(fact)), TextRole.SECONDARY), activity.lp(top = 4))
                }
            }
        }
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(title)
                .setView(content)
                .setPositiveButton(R.string.done, null),
        )
    }

    /**
     * Edits one member-day. Exact values start filled in; only fields the user changes become
     * edits, so untouched derived values keep their certainty.
     */
    fun showEdit(
        activity: Activity,
        title: String,
        isGunsmokeWeek: Boolean,
        uid: Long,
        cell: WeeklyReportBuilder.DayCell,
        current: WeeklyOverrideEditing.CellEdit?,
        next: (() -> Unit)? = null,
        onDone: (WeeklyOverrideEditing.CellEdit) -> Unit,
    ) {
        // A minimum or unknown value starts empty with its notation as the hint, so typing the
        // shown number confirms it instead of reading as "unchanged".
        fun numberField(label: Int, field: WeeklyCellEditor.Field, hint: Int): Pair<LabeledField, String> {
            val start = WeeklyCellEditor.start(cell, current, field)
            val hintText = start.notation?.let { activity.getString(R.string.captured_value_hint, it) } ?: activity.getString(hint)
            val input = activity.textField(hintText, inputType = InputType.TYPE_CLASS_NUMBER).apply { setText(start.text) }
            return LabeledField(activity, activity.getString(label), input) to start.text
        }
        val (merit, meritInitial) = numberField(R.string.merit_short, WeeklyCellEditor.Field.MERIT, R.string.blank_clears)
        val score = if (isGunsmokeWeek) {
            numberField(R.string.point_short, WeeklyCellEditor.Field.SCORE, R.string.blank_clears)
        } else {
            null
        }
        val attempts = if (isGunsmokeWeek) {
            numberField(R.string.attempt_short, WeeklyCellEditor.Field.ATTEMPTS, R.string.attempts_range)
        } else {
            null
        }
        val loginInitial = current?.attended ?: Choice.of(cell.attended)
        val patrolInitial = current?.dailyPatrol ?: Choice.of(cell.dailyPatrol)
        var login = loginInitial
        var patrol = patrolInitial
        val error = activity.errorLine()
        val clear = activity.button(activity.getString(R.string.clear_manual_values), Controls.Role.DESTRUCTIVE_TEXT) {}
        val content = Dialogs.content(activity) {
            addView(activity.text(activity.getString(R.string.manual_edit_hint), TextRole.SECONDARY), activity.lp())
            addView(merit, activity.lp(top = 12))
            if (score != null && attempts != null) {
                addView(activity.row(Gravity.TOP) {
                    addView(score.first, activity.lp(width = 0, weight = 1f))
                    addView(attempts.first, activity.lp(width = 0, weight = 1f, start = 12))
                }, activity.lp(top = 12))
            }
            addView(choiceRow(activity, R.string.login_short, loginInitial) { login = it }, activity.lp(top = 12))
            addView(choiceRow(activity, R.string.patrol_short, patrolInitial) { patrol = it }, activity.lp(top = 12))
            addView(error, activity.lp(top = 8))
            if (cell.manualOverride != null) addView(clear, activity.lp(WRAP, WRAP, top = 4))
        }
        // The neutral slot walks the member's week so a whole row can be corrected in one pass.
        val builder = Dialogs.builder(activity)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.apply, null)
        if (next != null) builder.setNeutralButton(R.string.apply_and_next_day, null)
        Dialogs.show(builder) { dialog ->
            fun changed(field: Pair<LabeledField, String>?): String? =
                field?.let { (view, initial) -> WeeklyCellEditor.changed(view.input.text.toString(), initial) }
            fun finish(edit: WeeklyOverrideEditing.CellEdit, then: (() -> Unit)? = null) {
                val probe = WeeklyOverrideEditing.merge(
                    cell.manualOverride?.periodStart ?: cell.gameDay,
                    isGunsmokeWeek,
                    emptyList(),
                    listOf(edit),
                )
                if (probe is WeeklyOverrideEditing.Result.Invalid) {
                    error.showMessage(
                        when (probe.field) {
                            WeeklyOverrideEditing.Field.ATTEMPTS -> activity.getString(R.string.invalid_attempts)
                            WeeklyOverrideEditing.Field.ACTIVITY -> activity.getString(R.string.invalid_activity_choice)
                            WeeklyOverrideEditing.Field.MERIT -> if (isGunsmokeWeek) {
                                activity.getString(R.string.invalid_counter)
                            } else {
                                activity.getString(
                                    R.string.invalid_standard_merit,
                                    WeeklyOverrideEditing.maximumDailyMerit(isGunsmokeWeek = false),
                                )
                            }
                            else -> activity.getString(R.string.invalid_counter)
                        },
                    )
                    return
                }
                if (WeeklyOverrideEditing.contradictsEvidence(cell, edit)) {
                    error.showMessage(activity.getString(R.string.invalid_activity_evidence))
                    return
                }
                dialog.dismiss()
                onDone(edit)
                then?.invoke()
            }
            fun edited() = WeeklyOverrideEditing.CellEdit(
                uid = uid,
                gameDay = cell.gameDay,
                merit = changed(merit to meritInitial) ?: current?.merit,
                score = changed(score) ?: current?.score,
                attempts = changed(attempts) ?: current?.attempts,
                attended = login.takeIf { it != loginInitial } ?: current?.attended,
                dailyPatrol = patrol.takeIf { it != patrolInitial } ?: current?.dailyPatrol,
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { finish(edited()) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { finish(edited(), next) }
            clear.setOnClickListener {
                finish(
                    WeeklyOverrideEditing.CellEdit(
                        uid = uid,
                        gameDay = cell.gameDay,
                        merit = "",
                        score = if (isGunsmokeWeek) "" else null,
                        attempts = if (isGunsmokeWeek) "" else null,
                        attended = Choice.UNSET,
                        dailyPatrol = Choice.UNSET,
                    ),
                )
            }
        }
    }

    private fun choiceRow(activity: Activity, label: Int, initial: Choice, onChanged: (Choice) -> Unit): View =
        activity.column {
            addView(activity.text(activity.getString(label), TextRole.LABEL) {
                setPadding(activity.dp(4), 0, 0, activity.dp(4))
            }, activity.lp())
            addView(Controls.segmentedControl(
                context = activity,
                options = listOf(
                    Choice.UNSET.name to activity.getString(R.string.choice_unset),
                    Choice.YES.name to activity.getString(R.string.yes),
                    Choice.NO.name to activity.getString(R.string.no),
                ),
                selectedValue = initial.name,
            ) { onChanged(Choice.valueOf(it)) }, activity.lp())
        }

    fun showGuide(activity: Activity, isGunsmokeWeek: Boolean) {
        val rows = listOf(
            "90" to activity.getString(R.string.evidence_guide_exact),
            "≥90" to activity.getString(R.string.evidence_guide_minimum),
            "?" to activity.getString(R.string.evidence_guide_unknown),
            activity.getString(R.string.login_initial) + " / " + activity.getString(R.string.patrol_initial) to
                activity.getString(R.string.evidence_guide_marks),
            (if (isGunsmokeWeek) activity.getString(R.string.total) else "0 / 50 / 90") to activity.getString(
                if (isGunsmokeWeek) R.string.evidence_guide_gunsmoke_total else R.string.evidence_guide_standard_allocation,
            ),
        )
        val content = Dialogs.content(activity) {
            rows.forEachIndexed { index, (symbol, description) ->
                addView(activity.row(Gravity.TOP) {
                    addView(activity.badge(symbol, R.color.accent_surface, R.color.accent_text).apply {
                        textSize = 15f
                        minWidth = activity.dp(56)
                        minHeight = activity.dp(32)
                    }, activity.lp(WRAP, WRAP, end = 12))
                    addView(activity.text(description, TextRole.BODY), activity.lp(width = 0, weight = 1f))
                }, activity.lp(top = if (index == 0) 0 else 12))
            }
            addView(activity.text(activity.getString(R.string.evidence_guide_gap_warning), TextRole.SECONDARY), activity.lp(top = 16))
        }
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.weekly_evidence_guide)
                .setView(content)
                .setPositiveButton(R.string.done, null),
        )
    }

    fun showShare(activity: Activity, onChosen: (WeeklyShareProjection.Privacy, Boolean) -> Unit) {
        var names = true
        var uids = false
        var notes = false
        val content = Dialogs.content(activity) {
            setPadding(activity.dp(16), activity.dp(4), activity.dp(16), 0)
            addView(activity.switchRow(activity.getString(R.string.share_include_member_names), null, names) { names = it }, activity.lp())
            addView(activity.switchRow(activity.getString(R.string.share_include_uids), null, uids) { uids = it }, activity.lp())
            addView(activity.switchRow(
                activity.getString(R.string.share_include_private_notes),
                activity.getString(R.string.share_private_notes_detail),
                notes,
            ) { notes = it }, activity.lp())
            addView(activity.text(activity.getString(R.string.share_privacy_notice), TextRole.CAPTION) {
                setPadding(activity.dp(4), activity.dp(8), activity.dp(4), 0)
            }, activity.lp())
        }
        fun privacy() = WeeklyShareProjection.Privacy(names, uids, notes)
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.share_weekly_table)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.save_png) { _, _ -> onChosen(privacy(), false) }
                .setPositiveButton(R.string.share_png) { _, _ -> onChosen(privacy(), true) },
        )
    }

    private fun certaintyBadge(activity: Activity, certainty: MetricCertainty) = when (certainty) {
        MetricCertainty.EXACT -> activity.badge(activity.getString(R.string.evidence_certainty_exact), R.color.success_surface, R.color.success_text)
        MetricCertainty.LOWER_BOUND -> activity.badge(activity.getString(R.string.evidence_certainty_lower_bound), R.color.warning_surface, R.color.warning_text)
        MetricCertainty.UNKNOWN -> activity.badge(activity.getString(R.string.evidence_certainty_unknown), R.color.surface_variant, R.color.text_secondary)
    }.apply { maxLines = 2 }

    fun metricLabel(activity: Activity, metric: Metric): String = activity.getString(
        when (metric) {
            Metric.MERIT -> R.string.merit_short
            Metric.SCORE -> R.string.point_short
            Metric.ATTEMPTS -> R.string.attempt_short
            Metric.LOGIN -> R.string.login_short
            Metric.DAILY_PATROL -> R.string.patrol_short
        },
    )

    private fun factString(fact: WeeklyEvidenceAnalyzer.Fact): Int = when (fact) {
        WeeklyEvidenceAnalyzer.Fact.MANUAL_OVERRIDE -> R.string.evidence_fact_manual
        WeeklyEvidenceAnalyzer.Fact.EXACT_CLOSING_BOUNDARY -> R.string.evidence_fact_closing
        WeeklyEvidenceAnalyzer.Fact.FINAL_GUNSMOKE_SCORE -> R.string.evidence_fact_final_score
        WeeklyEvidenceAnalyzer.Fact.EXACT_DAILY_PATROL_EVENT -> R.string.evidence_fact_patrol
        WeeklyEvidenceAnalyzer.Fact.LOGIN_TIMESTAMP -> R.string.evidence_fact_login
        WeeklyEvidenceAnalyzer.Fact.SOLVER_CONSENSUS -> R.string.evidence_fact_solver
        WeeklyEvidenceAnalyzer.Fact.DAILY_CAP_REACHED -> R.string.evidence_fact_daily_cap
        WeeklyEvidenceAnalyzer.Fact.WEEKLY_CAP_REACHED -> R.string.evidence_fact_weekly_cap
        WeeklyEvidenceAnalyzer.Fact.ALL_DAYS_EXACT -> R.string.evidence_fact_all_exact
        WeeklyEvidenceAnalyzer.Fact.CONFIRMED_LOWER_BOUND -> R.string.evidence_fact_lower_bound
        WeeklyEvidenceAnalyzer.Fact.NO_OBSERVATION -> R.string.evidence_fact_no_observation
        WeeklyEvidenceAnalyzer.Fact.INCOMPLETE_BOUNDARY -> R.string.evidence_fact_incomplete_boundary
        WeeklyEvidenceAnalyzer.Fact.PARTIAL_DAY -> R.string.evidence_fact_partial
        WeeklyEvidenceAnalyzer.Fact.SPARSE_INFERENCE -> R.string.evidence_fact_sparse
        WeeklyEvidenceAnalyzer.Fact.AMBIGUOUS_ALLOCATION -> R.string.evidence_fact_ambiguous
    }
}

/** The pure part of the cell editor: what each number field starts with and what counts as a change. */
internal object WeeklyCellEditor {
    enum class Field { MERIT, SCORE, ATTEMPTS }

    /**
     * [text] fills the field; [notation] is the displayed value (`≥90`, `?`) shown as a hint when
     * the field starts empty because that value is not exact.
     */
    data class Start(val text: String, val notation: String? = null)

    /**
     * A field is filled in only with an unsaved draft value or an exact value (a manual override
     * is exact). Prefilling a minimum or an unknown would make the confirmed number impossible to
     * enter, because typing the same digits leaves the field "unchanged".
     */
    fun start(cell: WeeklyReportBuilder.DayCell, current: WeeklyOverrideEditing.CellEdit?, field: Field): Start {
        val draft = when (field) {
            Field.MERIT -> current?.merit
            Field.SCORE -> current?.score
            Field.ATTEMPTS -> current?.attempts
        }
        if (draft != null) return Start(draft)
        val (value, certainty) = when (field) {
            Field.MERIT -> cell.meritDelta to cell.meritCertainty
            Field.SCORE -> cell.scoreDelta to cell.scoreCertainty
            Field.ATTEMPTS -> cell.attempts to cell.attemptsCertainty
        }
        if (value != null && certainty == MetricCertainty.EXACT) return Start(value.toString())
        // Same notation as the table cell, including its "-" for attempts on a manual cell.
        val missing = if (field == Field.ATTEMPTS && cell.manualOverride != null) "-" else "?"
        return Start("", WeeklyMetricPresentation.format(value, certainty, missing))
    }

    /** The typed text when it differs from what the field started with, otherwise null. */
    fun changed(input: String, start: String): String? = input.takeIf { it != start }
}
