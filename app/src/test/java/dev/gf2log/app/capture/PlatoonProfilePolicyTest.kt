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

    @Test
    fun boundFlowCannotSwitchToAnotherOrUnregisteredPlatoon() {
        assertFalse(PlatoonProfilePolicy.changesBoundIdentity(null, null))
        assertFalse(PlatoonProfilePolicy.changesBoundIdentity(null, "known"))
        assertFalse(PlatoonProfilePolicy.changesBoundIdentity("known", "known"))
        assertTrue(PlatoonProfilePolicy.changesBoundIdentity("known", "other"))
        // An unregistered identity must not start admission while the flow stays bound.
        assertTrue(PlatoonProfilePolicy.changesBoundIdentity("known", null))
    }

    @Test
    fun admissionGateAllowsOneNewProfilePerClientAndAlwaysAllowsKnownProfiles() {
        val gate = PlatoonProfilePolicy.AdmissionGate()

        assertTrue(gate.canAdmit("haoplay", alreadyRegistered = false))
        gate.markAdmitted("haoplay")
        assertFalse(gate.canAdmit("haoplay", alreadyRegistered = false))
        assertTrue(gate.canAdmit("haoplay", alreadyRegistered = true))
        assertTrue(gate.canAdmit("darkwinter", alreadyRegistered = false))

        gate.clear()
        assertTrue(gate.canAdmit("haoplay", alreadyRegistered = false))
    }

    private fun profile(id: UInt, name: String) =
        PlatoonProfileData(id, name, 0u, 0u)
}
