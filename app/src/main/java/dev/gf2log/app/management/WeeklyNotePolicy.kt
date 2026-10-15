package dev.gf2log.app.management

import java.time.LocalDate

/** Keeps live weekly notes representable by the immutable weekly-history format. */
internal object WeeklyNotePolicy {
    const val MAX_MANUAL_NOTES_PER_WEEK = WeeklyReportHistoryCodec.MAX_NOTES

    fun canAdd(manualNoteCount: Int): Boolean =
        manualNoteCount in 0 until MAX_MANUAL_NOTES_PER_WEEK

    /**
     * True when [gameDay] lies in the Sunday-based week starting at [periodStart]. Weekly notes
     * and cell overrides outside their week would fail every later revision audit of that week.
     */
    fun isWithinWeek(periodStart: LocalDate, gameDay: LocalDate): Boolean =
        PlatoonPeriods.weekStart(periodStart) == periodStart &&
            gameDay in periodStart..periodStart.plusDays(6)
}

/** Signals the one user-correctable weekly-note capacity failure. */
internal class WeeklyNoteLimitException : IllegalArgumentException(
    "A weekly table cannot contain more than " +
        "${WeeklyNotePolicy.MAX_MANUAL_NOTES_PER_WEEK} manual notes",
)
