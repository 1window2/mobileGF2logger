package dev.gf2log.app.settings

import dev.gf2log.app.SupportedGamePackages
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientServerRegionPolicyTest {
    @Test
    fun everyPublisherHasOnlyItsOwnRegions() {
        val haoPlay = ClientServerRegionPreferences
            .allowedFor(SupportedGamePackages.HAOPLAY)
            .toSet()
        val darkwinter = ClientServerRegionPreferences
            .allowedFor(SupportedGamePackages.DARKWINTER)
            .toSet()

        assertTrue(haoPlay.none(darkwinter::contains))
        assertTrue(haoPlay.all { it.name.startsWith("HAOPLAY_") })
        assertTrue(darkwinter.all { it.name.startsWith("DARKWINTER_") })
        assertTrue(SupportedGamePackages.HAOPLAY != SupportedGamePackages.DARKWINTER)
    }
}
