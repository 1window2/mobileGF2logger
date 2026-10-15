package dev.gf2log.app.management

import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences.ScopedZoneState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoredTimeZonePolicyTest {
    private val korea = GameServerRegion.HAOPLAY_KOREA
    private val global = GameServerRegion.HAOPLAY_GLOBAL
    private val automatic = ScopedZoneState(region = null, zone = null)
    private val explicitKorea = ScopedZoneState(region = korea.storedValue, zone = null)
    private val manual = ScopedZoneState(region = GameServerRegion.MANUAL.storedValue, zone = "Asia/Seoul")

    @Test
    fun `an automatic region never needs clearing`() {
        assertFalse(RestoredTimeZonePolicy.clearsOverride(automatic, korea, global, restoredSettings = false))
        assertFalse(RestoredTimeZonePolicy.clearsOverride(automatic, korea, korea, restoredSettings = true))
    }

    @Test
    fun `a complete restore turns the archived profile region back into automatic`() {
        assertTrue(RestoredTimeZonePolicy.clearsOverride(explicitKorea, null, korea, restoredSettings = true))
        assertTrue(RestoredTimeZonePolicy.clearsOverride(explicitKorea, global, korea, restoredSettings = true))
    }

    @Test
    fun `a complete restore keeps a deliberately different or manual archived choice`() {
        assertFalse(RestoredTimeZonePolicy.clearsOverride(explicitKorea, korea, global, restoredSettings = true))
        assertFalse(RestoredTimeZonePolicy.clearsOverride(manual, korea, korea, restoredSettings = true))
    }

    @Test
    fun `a platoon-only restore clears every override when it changes the profile region`() {
        assertTrue(RestoredTimeZonePolicy.clearsOverride(explicitKorea, korea, global, restoredSettings = false))
        assertTrue(RestoredTimeZonePolicy.clearsOverride(manual, korea, global, restoredSettings = false))
        // Keys left by an earlier scope with no registered profile cannot describe this one.
        assertTrue(RestoredTimeZonePolicy.clearsOverride(manual, null, global, restoredSettings = false))
    }

    @Test
    fun `a platoon-only restore leaves settings alone when the profile region is unchanged`() {
        assertFalse(RestoredTimeZonePolicy.clearsOverride(explicitKorea, korea, korea, restoredSettings = false))
        assertFalse(RestoredTimeZonePolicy.clearsOverride(manual, global, global, restoredSettings = false))
    }
}
