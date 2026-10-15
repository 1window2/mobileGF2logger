package dev.gf2log.app.management

import dev.gf2log.protocol.GuildMembersCsv

/**
 * Bounds user-entered text at the persistence boundary. Each value is trimmed exactly as it is
 * stored and then rejected, never silently truncated, when it exceeds its limit, so a caller
 * that bypasses the input fields cannot store unbounded text and a failed save keeps the user's
 * draft intact. Weekly history may still shorten stored text to its own byte budgets.
 */
internal object ManualTextPolicy {
    /** The roster member-name limit, which the nickname field also enforces. */
    const val MAX_MEMBER_NAME_CHARS = GuildMembersCsv.MAX_MEMBER_NAME_CHARS

    /** One limit for every manual note, matching the weekly-note input field. */
    const val MAX_NOTE_CHARS = 1_000

    /** The stored form of a custom member name. */
    fun memberName(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "A member name cannot be blank" }
        require(it.length <= MAX_MEMBER_NAME_CHARS) {
            "A member name cannot exceed $MAX_MEMBER_NAME_CHARS characters"
        }
    }

    /** The stored form of a private member, membership-period, or weekly note. */
    fun note(value: String): String = value.trim().also {
        require(it.length <= MAX_NOTE_CHARS) { "A note cannot exceed $MAX_NOTE_CHARS characters" }
    }
}
