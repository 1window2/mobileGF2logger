package dev.gf2log.app.standby

import dev.gf2log.app.SupportedClientTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandbyClientTrustTest {

    @Test
    fun `only a client signed by its trusted signer is opened`() {
        assertNull(StandbyOutcome.forClient(SupportedClientTrust.VERIFIED))
    }

    @Test
    fun `a client under another signer is skipped with its own outcome`() {
        assertEquals(StandbyOutcome.CLIENT_SIGNER_CHANGED, StandbyOutcome.forClient(SupportedClientTrust.MISMATCH))
    }

    @Test
    fun `a client never trusted while the app was in use is skipped, not trusted by the run`() {
        assertEquals(StandbyOutcome.CLIENT_NOT_TRUSTED, StandbyOutcome.forClient(SupportedClientTrust.UNPINNED))
    }

    @Test
    fun `an absent client reads as not installed`() {
        assertEquals(StandbyOutcome.NOT_INSTALLED, StandbyOutcome.forClient(SupportedClientTrust.NOT_INSTALLED))
    }
}
