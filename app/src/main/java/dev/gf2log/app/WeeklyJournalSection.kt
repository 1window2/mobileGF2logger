package dev.gf2log.app

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.gf2log.app.management.MemberEvent
import dev.gf2log.app.management.MemberEventType
import dev.gf2log.app.management.MembershipEventPresentation
import dev.gf2log.app.management.PlatoonPeriods
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.WeeklyNote
import dev.gf2log.app.management.WeeklyNoteLimitException
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.divider
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.iconButton
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The weekly screen's footer: the week's joins and withdrawals, its notes, and the note composer.
 * The composer views live for the whole screen so a typed draft survives every re-render, and
 * the draft and chosen day are saved with the screen because the RecyclerView does not save them.
 */
internal class WeeklyJournalSection(
    private val activity: Activity,
    private val work: BackgroundWork,
    private val saveKey: String,
    private val repository: () -> PlatoonRepository,
    private val onChanged: () -> Unit,
) {
    /** What the footer shows for one rendered week. */
    class Content(
        val report: WeeklyReportBuilder.Report,
        val notes: List<WeeklyNote>,
        val events: List<MemberEvent>,
        val namesByUid: Map<Long, String>,
        val zone: ZoneId,
    )

    private val sections = activity.column()
    val view: View = activity.column {
        setPadding(context.dp(16), 0, context.dp(16), context.dp(24))
        addView(sections, context.lp())
    }

    private val noteInput: EditText = activity.textField(
        activity.getString(R.string.note_hint),
        id = R.id.weekly_note_input,
        singleLine = false,
        maxLength = MAX_NOTE_LENGTH,
    )
    private val noteDayButton: TextView = activity.text(null, TextRole.BODY) {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = context.dp(48)
        setPadding(context.dp(12), 0, context.dp(8), 0)
        background = context.selectableRect()
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_today, 0, R.drawable.ic_expand_more, 0)
        compoundDrawablePadding = context.dp(8)
        compoundDrawableTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
    }
    private var noteDay: LocalDate? = null

    fun saveState(outState: Bundle) {
        noteDay?.let { outState.putLong(STATE_NOTE_DAY, it.toEpochDay()) }
        outState.putString(STATE_NOTE_DRAFT, noteInput.text.toString())
    }

    fun restoreState(state: Bundle?) {
        noteDay = state?.takeIf { it.containsKey(STATE_NOTE_DAY) }?.getLong(STATE_NOTE_DAY)?.let(LocalDate::ofEpochDay)
        state?.getString(STATE_NOTE_DRAFT)?.let(noteInput::setText)
    }

    fun clear() = sections.removeAllViews()

    fun render(content: Content) {
        sections.removeAllViews()
        renderMembershipEvents(content)
        sections.addView(activity.sectionHeader(activity.getString(R.string.notes)), activity.lp())
        val notesByDay = content.notes.groupBy(WeeklyNote::gameDay)
        if (notesByDay.isEmpty()) {
            sections.addView(placeholder(R.string.no_notes), activity.lp())
        } else {
            sections.addView(dayPanel(content.report.days.filter { it in notesByDay }) { day ->
                notesByDay[day].orEmpty().map(::noteRow)
            }, activity.lp())
        }
        sections.addView(composer(content.report, content.zone), activity.lp(top = 8))
    }

    private fun renderMembershipEvents(content: Content) {
        val events = MembershipEventPresentation.deduplicate(content.events)
        sections.addView(activity.sectionHeader(activity.getString(R.string.join_withdraw)), activity.lp())
        if (events.isEmpty()) {
            sections.addView(placeholder(R.string.no_membership_events), activity.lp())
            return
        }
        val byDay = events.groupBy { MembershipEventPresentation.calendarDate(it, content.zone) }
        val ordered: List<LocalDate?> = content.report.days.filter { it in byDay } +
            if (null in byDay) listOf(null) else emptyList()
        sections.addView(dayPanel(ordered) { day ->
            byDay[day].orEmpty().map { eventRow(it, content) }
        }, activity.lp())
    }

    private fun placeholder(message: Int): View = activity.text(activity.getString(message), TextRole.SECONDARY) {
        setPadding(context.dp(4), 0, context.dp(4), context.dp(8))
    }

    /** One panel grouping rows under game-day headings; a null day collects undated rows. */
    private fun dayPanel(days: List<LocalDate?>, rows: (LocalDate?) -> List<View>): View = activity.panel(padding = 8) {
        days.forEachIndexed { index, day ->
            if (index > 0) addView(context.divider(), context.lp(top = 4, bottom = 4))
            val heading = day?.let { Labels.dayHeader(context, it) } ?: context.getString(R.string.unknown_date)
            addView(context.text(heading, TextRole.LABEL) {
                setPadding(context.dp(8), context.dp(4), context.dp(8), 0)
            }, context.lp())
            rows(day).forEach { addView(it, context.lp()) }
        }
    }

    private fun eventRow(event: MemberEvent, content: Content): View = with(activity) {
        val joined = event.type == MemberEventType.JOINED || event.type == MemberEventType.REJOINED
        val label = getString(if (joined) R.string.join_label else R.string.withdraw_label)
        val name = content.namesByUid[event.uid].orEmpty().ifBlank { event.note }
        val identity = name.takeIf(String::isNotBlank)?.let { getString(R.string.member_name_uid, it, event.uid) }
            ?: Labels.uid(this, event.uid)
        val time = MembershipEventPresentation.timePrefix(event, content.zone)
        row {
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

    private fun noteRow(note: WeeklyNote): View = with(activity) {
        row {
            minimumHeight = dp(48)
            setPadding(dp(8), 0, 0, 0)
            addView(text(note.text, TextRole.BODY) { setTextIsSelectable(true) }, lp(width = 0, weight = 1f))
            if (!note.isAutomatic) {
                addView(
                    iconButton(R.drawable.ic_delete, getString(R.string.delete_note_description, note.text.take(40)), R.color.text_secondary) {
                        confirmDeletion(note)
                    },
                    lp(48, 48),
                )
            }
        }
    }

    private fun composer(report: WeeklyReportBuilder.Report, zone: ZoneId): View {
        val today = PlatoonPeriods.gameDay(Instant.now(), zone)
        if (noteDay !in report.days) noteDay = today.takeIf { it in report.days } ?: report.days.first()
        updateNoteDay()
        noteDayButton.setOnClickListener {
            Dialogs.choose(
                activity,
                activity.getString(R.string.note_day),
                report.days.map { Labels.dayHeader(activity, it) },
                report.days.indexOf(noteDay),
            ) {
                noteDay = report.days[it]
                updateNoteDay()
            }
        }
        (noteInput.parent as? ViewGroup)?.removeView(noteInput)
        (noteDayButton.parent as? ViewGroup)?.removeView(noteDayButton)
        return activity.panel(padding = 12) {
            addView(context.text(context.getString(R.string.add_note), TextRole.SUBTITLE), context.lp())
            addView(noteDayButton, context.lp(top = 8))
            addView(noteInput, context.lp(top = 8))
            addView(
                context.button(context.getString(R.string.add_note), Controls.Role.PRIMARY) { addNote(report) },
                context.lp(top = 8),
            )
        }
    }

    private fun updateNoteDay() {
        val day = noteDay ?: return
        noteDayButton.text = Labels.dayHeader(activity, day)
        noteDayButton.contentDescription = Labels.labeled(activity, activity.getString(R.string.note_day), noteDayButton.text)
    }

    private fun addNote(report: WeeklyReportBuilder.Report) {
        val text = noteInput.text.toString().trim()
        val day = noteDay ?: return
        if (work.isBusy(saveKey)) return
        if (text.isEmpty()) {
            noteInput.error = activity.getString(R.string.note_required)
            return
        }
        work.run(
            key = saveKey,
            task = { repository().addWeeklyNote(report.periodStart.toEpochDay(), day.toEpochDay(), text) },
        ) { result ->
            result.fold(
                onSuccess = {
                    noteInput.text = null
                    Feedback.show(activity, R.string.note_added)
                    onChanged()
                },
                onFailure = { error ->
                    Feedback.show(
                        activity,
                        if (error is WeeklyNoteLimitException) R.string.weekly_note_limit_reached else R.string.save_failed,
                    )
                },
            )
        }
    }

    private fun confirmDeletion(note: WeeklyNote) {
        Dialogs.confirm(
            activity,
            activity.getString(R.string.delete_note),
            note.text,
            activity.getString(R.string.delete),
            destructive = true,
        ) {
            work.run(task = { repository().deleteWeeklyNote(note.id) }) { result ->
                if (result.getOrDefault(false)) {
                    Feedback.show(activity, R.string.note_deleted)
                    onChanged()
                }
            }
        }
    }

    private companion object {
        const val MAX_NOTE_LENGTH = 1_000
        const val STATE_NOTE_DAY = "weekly.note_day"
        const val STATE_NOTE_DRAFT = "weekly.note_draft"
    }
}
