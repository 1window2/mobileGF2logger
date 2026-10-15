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

    /**
     * A flow bound to [boundStorageId] may only keep reporting that scope. Any other identity,
     * including one that is not registered yet, is a change: admitting it while the flow stays
     * bound would route the new Platoon's payloads into the old scope.
     */
    fun changesBoundIdentity(boundStorageId: String?, observedStorageId: String?): Boolean =
        boundStorageId != null && boundStorageId != observedStorageId

    /** One user-started capture may persist at most one new profile per supported client. */
    internal class AdmissionGate {
        private val admittedClients = mutableSetOf<String>()

        @Synchronized
        fun canAdmit(ownerPackage: String, alreadyRegistered: Boolean): Boolean =
            alreadyRegistered || ownerPackage !in admittedClients

        @Synchronized
        fun markAdmitted(ownerPackage: String) {
            admittedClients += ownerPackage
        }

        @Synchronized
        fun clear() = admittedClients.clear()
    }
}
