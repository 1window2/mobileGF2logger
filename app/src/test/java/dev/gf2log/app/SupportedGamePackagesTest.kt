package dev.gf2log.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SupportedGamePackagesTest {
    @Test
    fun `routes both verified GF2 Android clients`() {
        assertEquals(
            listOf(
                "com.haoplay.game.and.exilium",
                "com.Sunborn.SnqxExilium.Glo",
            ),
            SupportedGamePackages.all,
        )
    }
}
