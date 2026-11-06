package dev.gf2log.app.management

/**
 * The member-details nickname and note inputs compared in their stored form. The stored values
 * are trimmed and a nickname equal to the in-game name is no nickname, so whitespace or the
 * in-game name typed back in never counts as an unsaved change.
 */
internal object MemberProfileDraft {
    /** The nickname field's initial text: only a real override, never the in-game name. */
    fun nicknameInput(status: MemberStatus): String =
        storedNickname(status.customName.orEmpty(), status.currentName).orEmpty()

    fun isDirty(nicknameInput: String, noteInput: String, status: MemberStatus): Boolean =
        storedNickname(nicknameInput, status.currentName) !=
            storedNickname(status.customName.orEmpty(), status.currentName) ||
            noteInput.trim() != status.note.trim()

    /** [ManualTextPolicy.customName] without its length check, which the input field enforces. */
    private fun storedNickname(value: String, currentName: String): String? =
        value.trim().takeIf { it.isNotEmpty() && it != currentName }
}
