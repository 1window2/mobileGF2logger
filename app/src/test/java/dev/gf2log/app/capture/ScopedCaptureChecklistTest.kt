package dev.gf2log.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedCaptureChecklistTest {
    @Test
    fun payloadsFromDifferentProfilesDoNotCompleteOneGuidedCapture() {
        val checklist = ScopedCaptureChecklist(setOf(1, 2, 3, 4))

        checklist.mark("haoplay", 1, chooseTarget = true)
        checklist.mark("haoplay", 2, chooseTarget = true)
        checklist.mark("darkwinter", 3, chooseTarget = true)
        checklist.mark("darkwinter", 4, chooseTarget = true)

        assertEquals("haoplay", checklist.targetScopeId())
        assertFalse(checklist.targetComplete())

        checklist.mark("haoplay", 3, chooseTarget = true)
        checklist.mark("haoplay", 4, chooseTarget = true)

        assertTrue(checklist.targetComplete())
    }

    @Test
    fun clearRemovesTargetAndCapturedEvidence() {
        val checklist = ScopedCaptureChecklist(setOf(1))
        checklist.mark("profile", 1, chooseTarget = true)

        checklist.clear()

        assertEquals(null, checklist.targetScopeId())
        assertTrue(checklist.targetCaptured().isEmpty())
        assertFalse(checklist.targetComplete())
    }
}
