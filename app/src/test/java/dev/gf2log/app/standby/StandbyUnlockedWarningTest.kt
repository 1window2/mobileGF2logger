package dev.gf2log.app.standby

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyUnlockedWarningTest {

    @Test
    fun `a phone with a screen lock is warned until the user agrees`() {
        assertTrue(StandbyUnlockedWarning.isRequired(secure = true, acknowledged = false))
        assertFalse(StandbyUnlockedWarning.isRequired(secure = true, acknowledged = true))
    }

    @Test
    fun `a phone without a screen lock has nothing to be warned about`() {
        assertFalse(StandbyUnlockedWarning.isRequired(secure = false, acknowledged = false))
        assertFalse(StandbyUnlockedWarning.isRequired(secure = false, acknowledged = true))
    }
}
