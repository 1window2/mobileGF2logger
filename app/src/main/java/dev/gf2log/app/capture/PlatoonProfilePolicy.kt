package dev.gf2log.app.capture

import dev.gf2log.protocol.model.PlatoonProfileData

/** Validates that payload 21905 contains an identity that is safe to use for capture routing. */
internal object PlatoonProfilePolicy {
    private const val MAX_NAME_LENGTH = 128

    fun isValid(profile: PlatoonProfileData?): Boolean = profile != null &&
        profile.platoonId != 0u &&
        profile.platoonName.isNotBlank() &&
        profile.platoonName.length <= MAX_NAME_LENGTH &&
        profile.platoonName.none(Char::isISOControl)

    /** One user-started capture may persist at most one new profile per supported client. */
    internal class AdmissionGate {
        private val admittedClients = mutableSetOf<String>()

        fun canAdmit(ownerPackage: String, alreadyRegistered: Boolean): Boolean =
            alreadyRegistered || ownerPackage !in admittedClients

        fun markAdmitted(ownerPackage: String) {
            admittedClients += ownerPackage
        }

        fun clear() = admittedClients.clear()
    }
}
