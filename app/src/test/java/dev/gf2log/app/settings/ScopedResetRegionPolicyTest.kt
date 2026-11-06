package dev.gf2log.app.settings

import dev.gf2log.app.SupportedGamePackages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedResetRegionPolicyTest {
    private val haoPlay = ClientServerRegionPreferences.allowedFor(SupportedGamePackages.HAOPLAY)

    @Test
    fun `an exported automatic region replays as automatic`() {
        assertTrue(
            ScopedResetRegionPolicy.restoresAutomatic(
                GameServerRegion.HAOPLAY_KOREA,
                profileRegion = GameServerRegion.HAOPLAY_KOREA,
                allowed = haoPlay,
            ),
        )
    }

    @Test
    fun `a deliberate override of the same publisher stays explicit`() {
        assertFalse(
            ScopedResetRegionPolicy.restoresAutomatic(
                GameServerRegion.HAOPLAY_GLOBAL,
                profileRegion = GameServerRegion.HAOPLAY_KOREA,
                allowed = haoPlay,
            ),
        )
    }

    @Test
    fun `another publisher's region is never restored onto a Platoon`() {
        assertTrue(
            ScopedResetRegionPolicy.restoresAutomatic(
                GameServerRegion.DARKWINTER_GLOBAL,
                profileRegion = GameServerRegion.HAOPLAY_KOREA,
                allowed = haoPlay,
            ),
        )
    }

    @Test
    fun `manual and unknown-profile values keep the previous behaviour`() {
        assertFalse(
            ScopedResetRegionPolicy.restoresAutomatic(
                GameServerRegion.MANUAL,
                profileRegion = GameServerRegion.HAOPLAY_KOREA,
                allowed = haoPlay,
            ),
        )
        assertFalse(ScopedResetRegionPolicy.restoresAutomatic(GameServerRegion.DARKWINTER_GLOBAL, null, null))
    }

    @Test
    fun `the legacy global region seeds only its own publisher`() {
        assertTrue(
            ClientServerRegionPreferences.legacySeed(GameServerRegion.HAOPLAY_KOREA, SupportedGamePackages.HAOPLAY) ==
                GameServerRegion.HAOPLAY_KOREA,
        )
        assertTrue(
            ClientServerRegionPreferences.legacySeed(GameServerRegion.HAOPLAY_KOREA, SupportedGamePackages.DARKWINTER) == null,
        )
        assertTrue(
            ClientServerRegionPreferences.legacySeed(GameServerRegion.MANUAL, SupportedGamePackages.HAOPLAY) == null,
        )
    }
}
