package dev.gf2log.app

import dev.gf2log.app.settings.GameServerRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class ResetRegionChoicesTest {
    @Test
    fun `a Platoon is offered only its own publisher's servers`() {
        assertEquals(
            listOf(
                GameServerRegion.HAOPLAY_GLOBAL,
                GameServerRegion.HAOPLAY_JAPAN,
                GameServerRegion.HAOPLAY_KOREA,
                GameServerRegion.HAOPLAY_ASIA,
            ),
            resetRegionChoices(SupportedGamePackages.HAOPLAY),
        )
        assertEquals(
            listOf(GameServerRegion.DARKWINTER_GLOBAL, GameServerRegion.DARKWINTER_CHINA),
            resetRegionChoices(SupportedGamePackages.DARKWINTER),
        )
    }

    @Test
    fun `an unknown profile keeps every non-manual region`() {
        assertEquals(GameServerRegion.entries.filterNot { it == GameServerRegion.MANUAL }, resetRegionChoices(null))
    }
}
