package dev.gf2log.app.capture

import dev.gf2log.app.SupportedGamePackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureFlowOwnerPolicyTest {
    @Test
    fun resolvedSupportedOwnerWinsWhenBothClientsAreInstalled() {
        assertEquals(
            SupportedGamePackages.DARKWINTER,
            CaptureFlowOwnerPolicy.choose(
                SupportedGamePackages.DARKWINTER,
                SupportedGamePackages.all,
            ),
        )
    }

    @Test
    fun oneInstalledClientIsSafeFallback() {
        assertEquals(
            SupportedGamePackages.HAOPLAY,
            CaptureFlowOwnerPolicy.choose(null, listOf(SupportedGamePackages.HAOPLAY)),
        )
    }

    @Test
    fun twoInstalledClientsRemainUnattributedWithoutOsEvidence() {
        assertNull(CaptureFlowOwnerPolicy.choose(null, SupportedGamePackages.all))
    }
}
