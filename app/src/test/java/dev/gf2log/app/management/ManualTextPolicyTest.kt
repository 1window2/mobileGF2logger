package dev.gf2log.app.management

import dev.gf2log.protocol.GuildMembersCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTextPolicyTest {
    @Test
    fun `member names keep the roster limit that the nickname field enforces`() {
        assertEquals(GuildMembersCsv.MAX_MEMBER_NAME_CHARS, ManualTextPolicy.MAX_MEMBER_NAME_CHARS)
        val maximum = "n".repeat(ManualTextPolicy.MAX_MEMBER_NAME_CHARS)

        assertEquals(maximum, ManualTextPolicy.memberName("  $maximum\t"))
        assertThrows(IllegalArgumentException::class.java) {
            ManualTextPolicy.memberName(maximum + "n")
        }
        assertThrows(IllegalArgumentException::class.java) { ManualTextPolicy.memberName(" \n ") }
    }

    @Test
    fun `notes are trimmed before the shared limit is checked`() {
        val maximum = "x".repeat(ManualTextPolicy.MAX_NOTE_CHARS)

        assertEquals(maximum, ManualTextPolicy.note("\n$maximum  "))
        assertEquals("", ManualTextPolicy.note("   "))
        assertThrows(IllegalArgumentException::class.java) { ManualTextPolicy.note(maximum + "x") }
    }

    @Test
    fun `the limit counts UTF-16 units like the Android input filters`() {
        val emoji = "😀"
        val atLimit = emoji.repeat(ManualTextPolicy.MAX_NOTE_CHARS / 2)

        assertEquals(atLimit, ManualTextPolicy.note(atLimit))
        assertThrows(IllegalArgumentException::class.java) { ManualTextPolicy.note(atLimit + emoji) }
    }

    @Test
    fun `a note at the limit fits the weekly history note budget`() {
        // Weekly notes are stored in history up to MAX_NOTE_BYTES; one UTF-16 unit needs at most
        // three UTF-8 bytes, so a stored weekly note is never shortened there.
        assertTrue(ManualTextPolicy.MAX_NOTE_CHARS * 3 <= WeeklyReportHistoryCodec.MAX_NOTE_BYTES)
    }
}
