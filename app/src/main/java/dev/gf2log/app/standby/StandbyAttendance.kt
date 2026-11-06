package dev.gf2log.app.standby

/**
 * Whether the person is at the phone when Home shows. Home trusts clients installed since it last
 * showed ([dev.gf2log.app.SupportedClientSigners]), which must never happen with nobody present.
 *
 * Standby and scheduled recording end on their own (finished, cancelled by the screen turning off,
 * or stood down), and closing the standby screen then resumes Home underneath it while the phone
 * sits unattended. From that point Home pins nothing on resume; the person's first touch on Home
 * pins instead. A standby screen the person taps away, and a new arm, clear it.
 */
internal object StandbyAttendance {
    @Volatile
    var endedUnattended: Boolean = false
        private set

    /** Standby or scheduled recording closed itself; nobody may be looking at the phone. */
    fun automationEnded() {
        endedUnattended = true
    }

    /** The person tapped the standby screen away, or armed standby again; no pin happens here. */
    fun attended() {
        endedUnattended = false
    }

    /** Whether a Home resume may pin first-seen clients: only one the person is known to see. */
    fun mayPinOnResume(armed: Boolean): Boolean = !armed && !endedUnattended

    /**
     * The person touched Home. Returns true once after an unattended end, when the pin skipped on
     * resume is due now.
     */
    fun takeUserReturn(armed: Boolean): Boolean {
        if (armed || !endedUnattended) return false
        endedUnattended = false
        return true
    }
}
