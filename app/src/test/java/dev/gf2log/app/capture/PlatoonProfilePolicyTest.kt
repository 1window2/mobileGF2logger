package dev.gf2log.app.capture

import dev.gf2log.protocol.model.PlatoonProfileData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonProfilePolicyTest {
    @Test
    fun onlyNonzeroNamedProfilesAreAuthoritative() {
        assertFalse(PlatoonProfilePolicy.isValid(null))
        assertFalse(PlatoonProfilePolicy.isValid(profile(0u, "Owls")))
        assertFalse(PlatoonProfilePolicy.isValid(profile(101817u, "   ")))
        assertFalse(PlatoonProfilePolicy.isValid(profile(101817u, "Owls\u0000")))
        assertFalse(PlatoonProfilePolicy.isValid(profile(101817u, "x".repeat(129))))
        assertTrue(PlatoonProfilePolicy.isValid(profile(101817u, "Owls")))
    }

    private fun profile(id: UInt, name: String) =
        PlatoonProfileData(id, name, emptyList(), emptyList())
}
