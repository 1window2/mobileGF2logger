package dev.gf2log.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import dev.gf2log.app.management.DailyEvidence
import dev.gf2log.app.management.MetricCertainty
import dev.gf2log.app.management.PlatoonPeriods
import dev.gf2log.app.management.WeeklyMetricPresentation
import dev.gf2log.app.management.WeeklyOverrideEditing
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.settings.WeeklyCutlines
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.dpf
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.text
import java.time.Instant
import java.time.ZoneId
import java.util.WeakHashMap

/** Keeps every row's horizontal scroll position identical so the table scrolls as one grid. */
internal class HorizontalSync {
    private val views = WeakHashMap<SyncedScrollView, Unit>()
    var offset = 0
        private set

    fun register(view: SyncedScrollView) {
        views[view] = Unit
    }

    private var placedFor: Any? = null

    fun publish(source: SyncedScrollView, x: Int) {
        offset = x
        views.keys.forEach { if (it !== source) it.syncTo(x) }
    }

    /** Sets the start position once per [key], so re-renders of the same week keep the user's scroll. */
    fun placeOnce(key: Any, x: Int) {
        if (placedFor == key) return
        placedFor = key
        offset = x
        views.keys.forEach { it.syncTo(x) }
    }
}

internal class SyncedScrollView(context: Context, private val sync: HorizontalSync) : HorizontalScrollView(context) {
    private var syncing = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        sync.register(this)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!syncing) sync.publish(this, l)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        syncTo(sync.offset)
    }

    fun syncTo(x: Int) {
        if (scrollX == x) return
        syncing = true
        scrollTo(x, 0)
        syncing = false
    }
}

/** Display-only projection of one weekly cell. */
internal data class CellDisplay(
    val merit: String,
    val meritWarn: Boolean,
    val score: String?,
    val scoreWarn: Boolean,
    val attempts: String?,
    val attemptsWarn: Boolean,
    val login: Mark,
    val patrol: Mark,
    val edited: Boolean,
) {
    data class Mark(val symbol: String, @ColorRes val color: Int, val spoken: Int? = null)
}

/**
 * Builds the weekly grid rows: a frozen member column followed by seven daily cells and the
 * weekly total. Values keep the evidence notation from the domain (`≥` minimum, `?` unknown).
 */
internal class WeeklyTable(
    private val context: Context,
    val isGunsmokeWeek: Boolean,
    var zone: ZoneId,
    var cutlines: WeeklyCutlines,
    val sync: HorizontalSync = HorizontalSync(),
) {
    /** Reference instant for open-day marks; refreshed with each render. */
    var now: Instant = Instant.now()
    /** Heights follow the user's font size; widths grow more gently so a week still fits. */
    private val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
    private fun height(dp: Int) = (context.dp(dp) * fontScale).toInt()
    private fun width(dp: Int) = (context.dp(dp) * fontScale.coerceAtMost(1.5f)).toInt()

    val rowHeight = height(if (isGunsmokeWeek) GUNSMOKE_ROW_HEIGHT else STANDARD_ROW_HEIGHT)

    fun headerRow(report: WeeklyReportBuilder.Report): View {
        // Open the current week at yesterday so today's column is on screen without swiping.
        val todayIndex = report.days.indexOf(PlatoonPeriods.gameDay(now, zone))
        sync.placeOnce(report.days.first(), (width(DAY_WIDTH) + hairline) * (todayIndex - 1).coerceAtLeast(0))
        return headerGridRow(report)
    }

    private fun headerGridRow(report: WeeklyReportBuilder.Report): View = gridRow(
        frozen = headerCell(context.getString(R.string.member), null, MEMBER_WIDTH, Gravity.START or Gravity.CENTER_VERTICAL),
        cells = report.days.map { day ->
            headerCell(
                day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, context.resources.configuration.locales[0]),
                "${day.monthValue}/${day.dayOfMonth}",
                DAY_WIDTH,
                Gravity.CENTER,
                today = day == PlatoonPeriods.gameDay(now, zone),
            )
        } + headerCell(context.getString(R.string.total), null, TOTAL_WIDTH, Gravity.CENTER),
        height = height(HEADER_HEIGHT),
    )

    /** One reusable member row; [bind] refreshes it in place for RecyclerView recycling. */
    inner class MemberRow {
        private val name = context.text(null, TextRole.BODY) {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val detail = context.text(null, TextRole.CAPTION) { maxLines = 1 }
        private val rankChip = GradientDrawable().apply { cornerRadius = context.dpf(8f) }
        private val rank = context.text(null, TextRole.LABEL) {
            gravity = Gravity.CENTER
            background = rankChip
            minWidth = context.dp(24)
            setPadding(context.dp(4), 0, context.dp(4), 0)
            visibility = if (isGunsmokeWeek) View.VISIBLE else View.GONE
        }
        private val frozen = context.row {
            setPadding(context.dp(10), 0, context.dp(6), 0)
            background = ColorDrawable(context.getColor(R.color.table_cell))
            addView(rank, context.lp(WRAP, WRAP, end = 6))
            addView(context.column {
                addView(name, context.lp())
                addView(detail, context.lp())
            }, context.lp(width = 0, weight = 1f))
        }
        private val cells = List(DAYS + 1) { MetricCell(if (it == DAYS) TOTAL_WIDTH else DAY_WIDTH, total = it == DAYS) }
        val view: View = gridRow(frozen, cells.map { it.view }, rowHeight)

        fun bind(
            member: WeeklyReportBuilder.MemberRow,
            rankValue: Int?,
            edits: Map<java.time.LocalDate, WeeklyOverrideEditing.CellEdit>,
            onCell: (WeeklyReportBuilder.DayCell?) -> Unit,
        ) {
            name.text = member.name
            detail.text = "#${member.uid}"
            rank.text = rankValue?.toString().orEmpty()
            // Only the podium carries the accent; the rest of the ranking stays quiet.
            val podium = rankValue != null && rankValue <= PODIUM
            rankChip.setColor(context.getColor(if (podium) R.color.accent_surface else R.color.surface_variant))
            rank.setTextColor(context.getColor(if (podium) R.color.accent_text else R.color.text_secondary))
            frozen.contentDescription = listOfNotNull(
                rankValue?.let { context.getString(R.string.rank_description, it) },
                member.name,
                "#${member.uid}",
            ).joinToString(". ")
            frozen.isFocusable = true
            member.days.forEachIndexed { index, cell ->
                cells.getOrNull(index)?.bind(
                    display = daily(cell, edits[cell.gameDay]),
                    description = context.getString(R.string.weekly_cell_description, member.name, Labels.dayHeader(context, cell.gameDay)),
                ) { onCell(cell) }
            }
            cells.last().bind(
                display = total(member),
                description = context.getString(R.string.weekly_cell_description, member.name, context.getString(R.string.total)),
            ) { onCell(null) }
        }
    }

    private inner class MetricCell(widthDp: Int, private val total: Boolean) {
        private val fill = ColorDrawable()
        private val merit = context.text(null, TextRole.SUBTITLE) {
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        private val gunsmoke = context.text(null, TextRole.CAPTION) {
            gravity = Gravity.CENTER
            maxLines = 1
            visibility = if (isGunsmokeWeek) View.VISIBLE else View.GONE
        }
        private val activity = context.text(null, TextRole.CAPTION) {
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val view: LinearLayout = context.column {
            gravity = Gravity.CENTER
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            addView(merit, context.lp())
            addView(gunsmoke, context.lp())
            addView(activity, context.lp())
            layoutParams = LinearLayout.LayoutParams(width(widthDp), rowHeight)
            background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.control_ripple)), fill, null)
            isClickable = true
            isFocusable = true
        }

        fun bind(display: CellDisplay, description: String, onClick: () -> Unit) {
            merit.text = display.merit
            // Unknown values recede so confirmed numbers and cutline warnings stand out.
            merit.setTextColor(
                context.getColor(
                    when {
                        display.meritWarn -> R.color.cutline_mark
                        display.merit == "?" -> R.color.text_secondary
                        else -> R.color.text_primary
                    },
                ),
            )
            merit.paintFlags = if (display.meritWarn) merit.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            else merit.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            if (isGunsmokeWeek) {
                gunsmoke.text = "${display.score ?: "-"} · ${display.attempts ?: "-"}"
                gunsmoke.setTextColor(
                    context.getColor(if (display.scoreWarn || display.attemptsWarn) R.color.cutline_mark else R.color.text_secondary),
                )
            }
            activity.text = android.text.SpannableStringBuilder().apply {
                appendMark(context.getString(R.string.login_initial), display.login)
                append("  ")
                appendMark(context.getString(R.string.patrol_initial), display.patrol)
            }
            // Recolor the cell's own fill in place; binding allocates no drawables.
            fill.color = context.getColor(
                when {
                    display.edited -> R.color.accent_surface
                    display.meritWarn || display.scoreWarn || display.attemptsWarn -> R.color.warning_surface
                    total -> R.color.table_header
                    else -> R.color.table_cell
                },
            )
            view.contentDescription = buildString {
                append(description)
                append(". ")
                append(context.getString(R.string.merit_short)).append(' ').append(spoken(display.merit))
                if (display.meritWarn) append(", ").append(context.getString(R.string.below_cutline))
                if (isGunsmokeWeek) {
                    append(". ").append(context.getString(R.string.point_short)).append(' ').append(spoken(display.score ?: "-"))
                    append(". ").append(context.getString(R.string.attempt_short)).append(' ').append(spoken(display.attempts ?: "-"))
                }
                append(". ").append(context.getString(R.string.login_short)).append(' ').append(spoken(display.login))
                append(". ").append(context.getString(R.string.patrol_short)).append(' ').append(spoken(display.patrol))
                if (display.edited) append(". ").append(context.getString(R.string.edited))
            }
            view.setOnClickListener { onClick() }
        }

        private fun android.text.SpannableStringBuilder.appendMark(label: String, mark: CellDisplay.Mark) {
            append(label)
            val start = length
            append(mark.symbol)
            setSpan(
                android.text.style.ForegroundColorSpan(context.getColor(mark.color)),
                start,
                length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun spoken(mark: CellDisplay.Mark): String = mark.spoken?.let(context::getString) ?: spoken(mark.symbol)

    private fun spoken(value: String): String = when {
        value == "?" -> context.getString(R.string.evidence_unknown_short)
        value.startsWith("≥") -> context.getString(R.string.at_least, value.drop(1))
        else -> value
    }

    fun daily(cell: WeeklyReportBuilder.DayCell, edit: WeeklyOverrideEditing.CellEdit? = null): CellDisplay {
        val dayClosed = !PlatoonPeriods.periodStartInstant(cell.gameDay.plusDays(1), zone).isAfter(now)
        val base = CellDisplay(
            merit = WeeklyMetricPresentation.format(cell.meritDelta, cell.meritCertainty),
            meritWarn = WeeklyMetricPresentation.warnsBelowCutline(cell.meritDelta, cell.meritCertainty, cutlines::belowDailyMerit),
            score = if (isGunsmokeWeek) WeeklyMetricPresentation.format(cell.scoreDelta, cell.scoreCertainty) else null,
            scoreWarn = isGunsmokeWeek &&
                WeeklyMetricPresentation.warnsBelowCutline(cell.scoreDelta, cell.scoreCertainty, cutlines::belowDailyScore),
            attempts = if (isGunsmokeWeek) {
                WeeklyMetricPresentation.format(
                    cell.attempts,
                    cell.attemptsCertainty,
                    missing = if (cell.manualOverride != null) "-" else "?",
                )
            } else {
                null
            },
            attemptsWarn = isGunsmokeWeek &&
                WeeklyMetricPresentation.warnsBelowCutline(cell.attempts, cell.attemptsCertainty, cutlines::belowDailyAttempts),
            login = if (cell.attended == null && cell.evidence != DailyEvidence.MANUAL) {
                UNKNOWN_MARK
            } else {
                activityMark(cell.attended, cell.observed, dayClosed)
            },
            patrol = if (cell.dailyPatrol == null && cell.evidence != DailyEvidence.MANUAL) {
                UNKNOWN_MARK
            } else {
                activityMark(cell.dailyPatrol, cell.observed, dayClosed)
            },
            edited = false,
        )
        if (edit == null || edit.isEmpty) return base
        return base.copy(
            merit = edit.merit?.let { it.trim().ifEmpty { "-" } } ?: base.merit,
            meritWarn = edit.merit == null && base.meritWarn,
            score = edit.score?.let { it.trim().ifEmpty { "-" } } ?: base.score,
            scoreWarn = edit.score == null && base.scoreWarn,
            attempts = edit.attempts?.let { it.trim().ifEmpty { "-" } } ?: base.attempts,
            attemptsWarn = edit.attempts == null && base.attemptsWarn,
            login = edit.attended?.let { editedMark(it.value) } ?: base.login,
            patrol = edit.dailyPatrol?.let { editedMark(it.value) } ?: base.patrol,
            edited = true,
        )
    }

    fun total(member: WeeklyReportBuilder.MemberRow): CellDisplay = CellDisplay(
        merit = WeeklyMetricPresentation.format(member.totalMerit, member.totalMeritCertainty),
        meritWarn = WeeklyMetricPresentation.warnsBelowCutline(member.totalMerit, member.totalMeritCertainty, cutlines::belowWeeklyMerit),
        score = if (isGunsmokeWeek) WeeklyMetricPresentation.format(member.totalScore, member.totalScoreCertainty) else null,
        scoreWarn = isGunsmokeWeek &&
            WeeklyMetricPresentation.warnsBelowCutline(member.totalScore, member.totalScoreCertainty, cutlines::belowWeeklyScore),
        attempts = if (isGunsmokeWeek) WeeklyMetricPresentation.format(member.totalAttempts, member.totalAttemptsCertainty) else null,
        attemptsWarn = isGunsmokeWeek &&
            WeeklyMetricPresentation.warnsBelowCutline(member.totalAttempts, member.totalAttemptsCertainty, cutlines::belowWeeklyAttempts),
        login = countMark(member.loginDays, member.loginDaysCertainty, cutlines::belowWeeklyLoginDays),
        patrol = countMark(member.patrolDays, member.patrolDaysCertainty, cutlines::belowWeeklyPatrolDays),
        edited = false,
    )

    private fun countMark(value: Int?, certainty: MetricCertainty, below: (Int) -> Boolean) = CellDisplay.Mark(
        WeeklyMetricPresentation.format(value, certainty),
        if (WeeklyMetricPresentation.warnsBelowCutline(value, certainty, below)) R.color.cutline_mark else R.color.text_secondary,
    )

    private fun activityMark(value: Boolean?, observed: Boolean, dayClosed: Boolean): CellDisplay.Mark = when {
        value == true -> YES_MARK
        !observed -> NONE_MARK
        value == false && dayClosed -> NO_MARK
        value == false -> NONE_MARK
        else -> CellDisplay.Mark("?", R.color.text_secondary, R.string.evidence_unknown_short)
    }

    private fun editedMark(value: Boolean?): CellDisplay.Mark = when (value) {
        true -> YES_MARK
        false -> NO_MARK
        null -> NONE_MARK
    }

    /** Today's column is marked with the accent so the open game day is found at a glance. */
    private fun headerCell(title: String, subtitle: String?, widthDp: Int, gravity: Int, today: Boolean = false): View = context.column {
        this.gravity = gravity
        setPadding(context.dp(10), 0, context.dp(6), 0)
        setBackgroundColor(context.getColor(if (today) R.color.accent_surface else R.color.table_header))
        addView(context.text(title, TextRole.LABEL) {
            setTextColor(context.getColor(if (today) R.color.accent_text else R.color.text_primary))
            this.gravity = gravity
        }, context.lp())
        subtitle?.let {
            addView(context.text(it, TextRole.CAPTION) {
                this.gravity = gravity
                if (today) setTextColor(context.getColor(R.color.accent_text))
            }, context.lp())
        }
        if (today) contentDescription = context.getString(R.string.today_column, title, subtitle.orEmpty())
        layoutParams = LinearLayout.LayoutParams(width(widthDp), LinearLayout.LayoutParams.MATCH_PARENT)
    }

    /** Grid row: hairline separators come from a 1px gap over the outline-colored row. */
    private val hairline = maxOf(1, context.dp(1) / 2)

    private fun gridRow(frozen: View, cells: List<View>, height: Int): View {
        val scroller = SyncedScrollView(context, sync).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                cells.forEach { cell ->
                    val cellWidth = (cell.layoutParams as? LinearLayout.LayoutParams)?.width ?: width(DAY_WIDTH)
                    addView(cell, LinearLayout.LayoutParams(cellWidth, height - hairline).apply { marginStart = hairline })
                }
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(context.getColor(R.color.outline))
            addView(frozen, LinearLayout.LayoutParams(width(MEMBER_WIDTH), height - hairline))
            addView(scroller, LinearLayout.LayoutParams(0, height, 1f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
        }
    }

    companion object {
        const val DAYS = 7
        const val MEMBER_WIDTH = 124
        const val DAY_WIDTH = 76
        const val TOTAL_WIDTH = 124
        const val HEADER_HEIGHT = 48
        const val STANDARD_ROW_HEIGHT = 64
        const val GUNSMOKE_ROW_HEIGHT = 80
        const val PODIUM = 3

        private val YES_MARK = CellDisplay.Mark("✓", R.color.success_mark, R.string.yes)
        private val NO_MARK = CellDisplay.Mark("×", R.color.failure_mark, R.string.no)
        private val NONE_MARK = CellDisplay.Mark("-", R.color.text_secondary, R.string.not_observed)
        private val UNKNOWN_MARK = CellDisplay.Mark("?", R.color.warning_mark, R.string.evidence_unknown_short)
    }
}
