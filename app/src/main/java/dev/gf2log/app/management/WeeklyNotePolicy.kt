package dev.gf2log.app.management

/** Keeps live weekly notes representable by the immutable weekly-history format. */
internal object WeeklyNotePolicy {
    const val MAX_MANUAL_NOTES_PER_WEEK = 128

    fun canAdd(manualNoteCount: Int): Boolean =
        manualNoteCount in 0 until MAX_MANUAL_NOTES_PER_WEEK
}

/** Signals the one user-correctable weekly-note capacity failure. */
internal class WeeklyNoteLimitException : IllegalArgumentException(
    "A weekly table cannot contain more than " +
        "${WeeklyNotePolicy.MAX_MANUAL_NOTES_PER_WEEK} manual notes",
)
