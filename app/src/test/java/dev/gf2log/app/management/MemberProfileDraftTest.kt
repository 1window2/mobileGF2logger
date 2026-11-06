package dev.gf2log.app.management

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberProfileDraftTest {
    @Test
    fun `a nickname equal to the in-game name or blank stores no override`() {
        assertNull(ManualTextPolicy.customName("Alice", currentName = "Alice"))
        assertNull(ManualTextPolicy.customName("  Alice \n", currentName = "Alice"))
        assertNull(ManualTextPolicy.customName("   ", currentName = "Alice"))
        assertEquals("Carol", ManualTextPolicy.customName(" Carol ", currentName = "Alice"))
        assertThrows(IllegalArgumentException::class.java) {
            ManualTextPolicy.customName("n".repeat(ManualTextPolicy.MAX_MEMBER_NAME_CHARS + 1), "Alice")
        }
    }

    @Test
    fun `the nickname field starts with the override only`() {
        assertEquals("", MemberProfileDraft.nicknameInput(status(customName = null)))
        assertEquals("Boss", MemberProfileDraft.nicknameInput(status(customName = "Boss")))
        // A nickname frozen to the in-game name by an older version is no override at all.
        assertEquals("", MemberProfileDraft.nicknameInput(status(customName = "Alice")))
    }

    @Test
    fun `whitespace around stored values is not an unsaved change`() {
        val stored = status(customName = "Boss", note = "x")

        assertFalse(MemberProfileDraft.isDirty("Boss ", "x\n", stored))
        assertTrue(MemberProfileDraft.isDirty("Boss", "y", stored))
        assertTrue(MemberProfileDraft.isDirty("Chief", "x", stored))
    }

    @Test
    fun `clearing a nickname or retyping the in-game name is a change only for a nicknamed member`() {
        val nicknamed = status(customName = "Boss")
        val plain = status(customName = null)

        assertTrue(MemberProfileDraft.isDirty("", "", nicknamed))
        assertTrue(MemberProfileDraft.isDirty("Alice", "", nicknamed))
        assertFalse(MemberProfileDraft.isDirty("", "", plain))
        assertFalse(MemberProfileDraft.isDirty("Alice", "", plain))
        assertFalse(MemberProfileDraft.isDirty("", "", status(customName = "Alice")))
    }

    @Test
    fun `a note-only edit is dirty while the nickname stays blank`() {
        // A blank nickname is valid, so a note edit can always be saved or confirmed on leaving.
        assertTrue(MemberProfileDraft.isDirty("", "late on Sunday", status(customName = null)))
    }

    private fun status(customName: String?, note: String = "") = MemberStatus(
        uid = 7,
        name = customName ?: "Alice",
        level = 60,
        isActive = true,
        firstSeenAt = Instant.EPOCH,
        lastSeenAt = Instant.EPOCH,
        note = note,
        membershipPeriods = emptyList(),
        currentName = "Alice",
        customName = customName,
    )
}
