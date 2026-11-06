package dev.gf2log.app.standby

import dev.gf2log.app.capture.CaptureNotice

/**
 * When standby's blind taps at the title screen's Start position stop, decided from capture
 * notices alone; no Android dependencies.
 *
 * Every notice here follows a parsed Platoon profile, accepted or rejected, and the game sends one
 * only after login. Once any of them arrives the title screen is gone, so a further tap could land
 * inside the game.
 */
internal object StandbyTapPolicy {

    /** Notices that only a logged-in game produces, whether or not its data could be used. */
    val LOGGED_IN_NOTICES: Set<CaptureNotice> = setOf(
        CaptureNotice.PLATOON_DETECTED,
        CaptureNotice.PENDING_DETECTED,
        CaptureNotice.PLATOON_CONFIRMED,
        CaptureNotice.IDENTITY_CHANGED,
        CaptureNotice.PENDING_IDENTITY_CHANGED,
        CaptureNotice.ROSTER_SAVED,
        CaptureNotice.ROSTER_UPDATED,
        CaptureNotice.ROSTER_RECOVERED,
        // The profile arrived but could not be attributed or kept.
        CaptureNotice.CLIENT_UNVERIFIED,
        CaptureNotice.IDENTIFIED_FLOW_BUFFER_OVERFLOW,
        CaptureNotice.PROFILE_UPDATE_FAILED,
        CaptureNotice.PENDING_QUEUE_FULL,
        CaptureNotice.PENDING_OVERFLOW,
        CaptureNotice.ADMISSION_IN_PROGRESS,
        // Platoon data of an identified flow could not be saved.
        CaptureNotice.ROSTER_SAVE_FAILED,
        CaptureNotice.ACTIVITY_SAVE_FAILED,
        CaptureNotice.UPDATES_SAVE_FAILED,
    )

    fun stopsTaps(notice: CaptureNotice): Boolean = notice in LOGGED_IN_NOTICES

    /**
     * How a run ends at once after [notice], or null when it goes on. A client that cannot be
     * verified keeps every flow of this login quarantined, so waiting for a roster is pointless.
     */
    fun endsRun(notice: CaptureNotice): StandbyOutcome? =
        if (notice == CaptureNotice.CLIENT_UNVERIFIED) StandbyOutcome.CLIENT_UNVERIFIED else null

    /** A tap is due at [now] only inside the tap window and before any sign of login. */
    fun tapDue(now: Long, nextTapAt: Long, tapsUntil: Long, loggedIn: Boolean): Boolean =
        !loggedIn && now >= nextTapAt && now < tapsUntil
}
