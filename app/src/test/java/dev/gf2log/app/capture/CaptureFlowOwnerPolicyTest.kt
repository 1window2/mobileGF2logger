package dev.gf2log.app.capture

import dev.gf2log.app.SupportedClientTrust
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

    @Test
    fun resolvedOwnerOutsideTheVerifiedClientsIsNotTrusted() {
        assertEquals(
            SupportedGamePackages.HAOPLAY,
            CaptureFlowOwnerPolicy.choose(SupportedGamePackages.DARKWINTER, listOf(SupportedGamePackages.HAOPLAY)),
        )
        assertNull(CaptureFlowOwnerPolicy.choose(SupportedGamePackages.DARKWINTER, emptyList()))
    }

    @Test
    fun unverifiedInstalledClientMapsNoUidAndLeavesTheFallbackToTheOther() {
        val uids = mapOf(SupportedGamePackages.HAOPLAY to 10_100, SupportedGamePackages.DARKWINTER to 10_200)
        val trusted = CaptureFlowOwnerPolicy.trustedUids(
            SupportedGamePackages.all,
            uidOf = uids::get,
            isVerified = { it == SupportedGamePackages.HAOPLAY },
        )

        assertEquals(mapOf(10_100 to SupportedGamePackages.HAOPLAY), trusted)
        // Both are installed, but only the verified one counts, so the fallback attributes to it.
        assertEquals(SupportedGamePackages.HAOPLAY, CaptureFlowOwnerPolicy.choose(null, trusted.values))
    }

    @Test
    fun noVerifiedClientMapsNothing() {
        val trusted = CaptureFlowOwnerPolicy.trustedUids(
            SupportedGamePackages.all,
            uidOf = { 10_100 },
            isVerified = { false },
        )

        assertEquals(emptyMap<Int, String>(), trusted)
        assertNull(CaptureFlowOwnerPolicy.choose(null, trusted.values))
    }

    @Test
    fun captureWithoutRoutableClientNamesTheSignerProblemFirst() {
        assertEquals(
            CaptureNotice.CLIENT_SIGNER_CHANGED,
            CaptureTargetPolicy.noTargetNotice(listOf(SupportedClientTrust.UNPINNED, SupportedClientTrust.MISMATCH)),
        )
        assertEquals(
            CaptureNotice.CLIENT_NOT_TRUSTED,
            CaptureTargetPolicy.noTargetNotice(listOf(SupportedClientTrust.NOT_INSTALLED, SupportedClientTrust.UNPINNED)),
        )
        assertEquals(
            CaptureNotice.NO_SUPPORTED_CLIENT,
            CaptureTargetPolicy.noTargetNotice(listOf(SupportedClientTrust.NOT_INSTALLED, SupportedClientTrust.NOT_INSTALLED)),
        )
    }
}
