package dev.gf2log.app.management

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyNotePolicyTest {
    @Test
    fun acceptsOnlyCountsBelowTheHistoryBound() {
        assertTrue(WeeklyNotePolicy.canAdd(0))
        assertTrue(WeeklyNotePolicy.canAdd(127))
        assertFalse(WeeklyNotePolicy.canAdd(128))
        assertFalse(WeeklyNotePolicy.canAdd(-1))
    }
}
