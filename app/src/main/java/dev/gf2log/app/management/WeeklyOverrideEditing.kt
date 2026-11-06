package dev.gf2log.app.management

import java.time.LocalDate

/**
 * Merges the user's manual weekly-table edits with the overrides already stored for a week.
 *
 * Only explicitly edited fields change; every untouched field keeps its stored override value so
 * derived certainty is never replaced by an accidental edit. The result is the complete override
 * set for the week, because the repository replaces a week's overrides as one unit.
 */
object WeeklyOverrideEditing {
    /** The invalid input; [ACTIVITY] is a Login NO with a Daily Patrol YES in the same edit. */
    enum class Field { MERIT, SCORE, ATTEMPTS, ACTIVITY }

    /** A tri-state activity choice: unset clears the manual value, yes/no confirms it. */
    enum class Choice(val value: Boolean?) {
        UNSET(null),
        YES(true),
        NO(false),
        ;

        companion object {
            fun of(value: Boolean?): Choice = entries.first { it.value == value }
        }
    }

    /** One cell's edits. A null property means that field was not edited. */
    data class CellEdit(
        val uid: Long,
        val gameDay: LocalDate,
        val merit: String? = null,
        val score: String? = null,
        val attempts: String? = null,
        val attended: Choice? = null,
        val dailyPatrol: Choice? = null,
    ) {
        val isEmpty: Boolean
            get() = merit == null && score == null && attempts == null &&
                attended == null && dailyPatrol == null
    }

    sealed interface Result {
        data class Valid(val overrides: List<WeeklyCellOverride>) : Result
        data class Invalid(val uid: Long, val gameDay: LocalDate, val field: Field) : Result
    }

    fun merge(
        periodStart: LocalDate,
        isGunsmokeWeek: Boolean,
        existing: Collection<WeeklyCellOverride>,
        edits: Collection<CellEdit>,
    ): Result {
        val existingByCell = existing.associateBy { it.uid to it.gameDay }
        val editsByCell = edits.filterNot(CellEdit::isEmpty).associateBy { it.uid to it.gameDay }
        val merged = (existingByCell.keys + editsByCell.keys).mapNotNull { cell ->
            val stored = existingByCell[cell]
            val edit = editsByCell[cell]
            fun invalid(field: Field) = Result.Invalid(cell.first, cell.second, field)
            val merit = edit?.merit?.let {
                parseCounter(it, maximumDailyMerit(isGunsmokeWeek)) ?: return invalid(Field.MERIT)
            }
            val score = edit?.score?.let {
                parseCounter(it, MAX_DAILY_SCORE) ?: return invalid(Field.SCORE)
            }
            val attempts = edit?.attempts?.let { parseAttempts(it) ?: return invalid(Field.ATTEMPTS) }
            if (edit?.attended == Choice.NO && edit.dailyPatrol == Choice.YES) {
                return invalid(Field.ACTIVITY)
            }
            // A completed Daily Patrol requires attendance, so an edited choice settles a
            // contradiction with the other stored one instead of producing an unstorable pair.
            val attended = when {
                edit?.attended != null -> edit.attended.value
                edit?.dailyPatrol == Choice.YES && stored?.attended == false -> true
                else -> stored?.attended
            }
            val dailyPatrol = when {
                edit?.dailyPatrol != null -> edit.dailyPatrol.value
                attended == false && stored?.dailyPatrol == true -> false
                else -> stored?.dailyPatrol
            }
            WeeklyCellOverride(
                uid = cell.first,
                periodStart = periodStart,
                gameDay = cell.second,
                meritDelta = if (edit?.merit != null) merit?.value else stored?.meritDelta,
                scoreDelta = when {
                    !isGunsmokeWeek -> null
                    edit?.score != null -> score?.value
                    else -> stored?.scoreDelta
                },
                attempts = when {
                    !isGunsmokeWeek -> null
                    edit?.attempts != null -> attempts?.value
                    else -> stored?.attempts
                },
                attended = attended,
                dailyPatrol = dailyPatrol,
            ).takeIf { it.hasAnyValue() }
        }
        return Result.Valid(merged.sortedWith(compareBy({ it.gameDay }, { it.uid })))
    }

    /**
     * Whether [edit] sets Login or Daily Patrol to "no" although [cell]'s evidence proves it: a
     * Daily Patrol event, a login time on the day, or a positive score or attempt count. The cell
     * would keep showing "yes", so the editor refuses such an edit instead of storing it.
     */
    fun contradictsEvidence(cell: WeeklyReportBuilder.DayCell, edit: CellEdit): Boolean {
        fun positive(edited: String?, shown: Long?): Boolean =
            (if (edited != null) edited.trim().toLongOrNull() else shown)?.let { it > 0L } == true
        val loginProven = cell.hasDailyPatrolFact || cell.hasLoginFact ||
            positive(edit.score, cell.scoreDelta) ||
            positive(edit.attempts, cell.attempts?.toLong())
        return (edit.dailyPatrol == Choice.NO && cell.hasDailyPatrolFact) ||
            (edit.attended == Choice.NO && loginProven)
    }

    private class Parsed<T>(val value: T?)

    /** Blank clears the field; otherwise an integer from 0 to [maximum] is required. */
    private fun parseCounter(text: String, maximum: Long): Parsed<Long>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed(null)
        return trimmed.toLongOrNull()?.takeIf { it in 0..maximum }?.let { Parsed(it) }
    }

    /**
     * A Standard day never earns more than its login and Daily Patrol. A Gunsmoke day adds its
     * attempts and the merit of at most [MAX_DAILY_SCORE] points.
     */
    internal fun maximumDailyMerit(isGunsmokeWeek: Boolean): Long = if (isGunsmokeWeek) {
        ActivityInference.LOGIN_AND_PATROL_MERIT +
            ActivityInference.MAX_DAILY_ATTEMPTS * ActivityInference.MERIT_PER_ATTEMPT +
            MAX_DAILY_SCORE / ActivityInference.SCORE_POINTS_PER_MERIT
    } else {
        ActivityInference.LOGIN_AND_PATROL_MERIT
    }

    /** A generous ceiling for one day's Gunsmoke score that keeps every weekly sum exact. */
    internal const val MAX_DAILY_SCORE = 1_000_000_000L

    private fun parseAttempts(text: String): Parsed<Int>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed(null)
        return trimmed.toIntOrNull()?.takeIf { it in 0..ActivityInference.MAX_DAILY_ATTEMPTS }?.let { Parsed(it) }
    }

    private fun WeeklyCellOverride.hasAnyValue(): Boolean =
        meritDelta != null || scoreDelta != null || attempts != null ||
            attended != null || dailyPatrol != null
}
