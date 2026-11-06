package dev.gf2log.app.management

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberSearchTest {
    private val nicknamed = MemberStatus(
        uid = 123_456_789,
        name = "Boss",
        level = 60,
        isActive = true,
        firstSeenAt = Instant.EPOCH,
        lastSeenAt = Instant.EPOCH,
        note = "",
        membershipPeriods = emptyList(),
        currentName = "Kai",
        customName = "Boss",
    )

    @Test
    fun `a nicknamed member is found by the in-game name too`() {
        assertTrue(nicknamed.matchesSearch("boss"))
        assertTrue(nicknamed.matchesSearch("kai"))
        assertFalse(nicknamed.matchesSearch("zed"))
    }

    @Test
    fun `a UID matches as shown with its hash and surrounding whitespace`() {
        assertTrue(nicknamed.matchesSearch("#123456789"))
        assertTrue(nicknamed.matchesSearch(" #1234 "))
        assertTrue(nicknamed.matchesSearch("# 1234"))
        assertTrue(nicknamed.matchesSearch("6789"))
    }

    @Test
    fun `a bare hash or a non-numeric query never matches through the UID`() {
        assertFalse(nicknamed.matchesSearch("#"))
        assertFalse(nicknamed.matchesSearch("#12a"))
    }

    @Test
    fun `an empty query lists every member`() {
        assertTrue(nicknamed.matchesSearch("  "))
    }
}
