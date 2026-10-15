package dev.gf2log.app

import android.os.Bundle
import dev.gf2log.app.management.WeeklyOverrideEditing.CellEdit
import dev.gf2log.app.management.WeeklyOverrideEditing.Choice
import java.time.LocalDate

/**
 * The unsaved manual edits of the weekly table. At most one week is edited at a time, and a draft
 * belongs to the Platoon scope it was started in, so a recreated screen in another scope drops it.
 */
internal class WeeklyEditSession {
    /** The week being edited, or null outside edit mode. */
    var period: LocalDate? = null
        private set
    private val edits = linkedMapOf<Pair<Long, LocalDate>, CellEdit>()

    val values: Collection<CellEdit> get() = edits.values
    val size: Int get() = edits.size

    fun isEmpty(): Boolean = edits.isEmpty()

    fun isEditing(periodStart: LocalDate): Boolean = period == periodStart

    fun start(periodStart: LocalDate) {
        period = periodStart
        edits.clear()
    }

    fun finish() {
        period = null
        edits.clear()
    }

    /** Drops a draft for another week than [periodStart], the week now shown. */
    fun retainFor(periodStart: LocalDate) {
        if (period != null && period != periodStart) finish()
    }

    operator fun get(uid: Long, gameDay: LocalDate): CellEdit? = edits[uid to gameDay]

    /** Records [edit]; an empty edit restores the cell. Ignored outside edit mode. */
    fun put(edit: CellEdit): Boolean {
        if (period == null) return false
        val key = edit.uid to edit.gameDay
        if (edit.isEmpty) edits.remove(key) else edits[key] = edit
        return true
    }

    fun forMember(uid: Long): Map<LocalDate, CellEdit> =
        edits.values.filter { it.uid == uid }.associateBy(CellEdit::gameDay)

    fun save(state: Bundle, scopeId: String) {
        val editing = period ?: return
        state.putString(STATE_SCOPE, scopeId)
        state.putLong(STATE_PERIOD, editing.toEpochDay())
        state.putParcelableArrayList(STATE_EDITS, ArrayList(edits.values.map(::encode)))
    }

    fun restore(state: Bundle?, scopeId: String) {
        if (state?.containsKey(STATE_PERIOD) != true || state.getString(STATE_SCOPE) != scopeId) return
        start(LocalDate.ofEpochDay(state.getLong(STATE_PERIOD)))
        @Suppress("DEPRECATION")
        state.getParcelableArrayList<Bundle>(STATE_EDITS)?.map(::decode)?.forEach(::put)
    }

    private fun encode(edit: CellEdit) = Bundle().apply {
        putLong("uid", edit.uid)
        putLong("day", edit.gameDay.toEpochDay())
        edit.merit?.let { putString("merit", it) }
        edit.score?.let { putString("score", it) }
        edit.attempts?.let { putString("attempts", it) }
        edit.attended?.let { putString("attended", it.name) }
        edit.dailyPatrol?.let { putString("patrol", it.name) }
    }

    private fun decode(bundle: Bundle) = CellEdit(
        uid = bundle.getLong("uid"),
        gameDay = LocalDate.ofEpochDay(bundle.getLong("day")),
        merit = bundle.getString("merit"),
        score = bundle.getString("score"),
        attempts = bundle.getString("attempts"),
        attended = bundle.getString("attended")?.let(Choice::valueOf),
        dailyPatrol = bundle.getString("patrol")?.let(Choice::valueOf),
    )

    private companion object {
        const val STATE_PERIOD = "weekly.editing_period"
        const val STATE_SCOPE = "weekly.editing_scope"
        const val STATE_EDITS = "weekly.edits"
    }
}
