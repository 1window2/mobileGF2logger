package dev.gf2log.app.management

import java.time.Duration
import java.time.Instant

/**
 * Decides when a stored roster's capture time cannot be real. Capture times come from the phone
 * clock. Login and activity times come from the game server and only ever trail the real
 * present, so they can prove that a capture time had already passed, never that it had not:
 * only a later phone-clock reading can reveal a roster dated in the future.
 */
internal object SnapshotClockPolicy {
    /**
     * How far a capture time may lie past the time it is checked against, such as a phone clock
     * a little behind the PC that wrote an imported CSV, before it counts as ahead.
     */
    val MAX_CLOCK_SKEW: Duration = Duration.ofHours(1)

    /** The latest instant known to have passed: the phone clock now, or a later server time. */
    fun reference(deviceNow: Instant, newestServerTime: Instant?): Instant =
        if (newestServerTime != null && newestServerTime.isAfter(deviceNow)) newestServerTime else deviceNow

    /** Whether a roster dated [capturedAt] lies beyond [reference]: its phone clock ran ahead. */
    fun isAhead(capturedAt: Instant, reference: Instant): Boolean =
        capturedAt.isAfter(reference.plus(MAX_CLOCK_SKEW))

    /**
     * Whether a roster dated [capturedAt] shows a login after its own capture, so its phone clock
     * was behind and its date cannot order it against other rosters.
     */
    fun isBehind(capturedAt: Instant, newestLogin: Instant?): Boolean =
        newestLogin != null && newestLogin.isAfter(capturedAt.plus(MAX_CLOCK_SKEW))

    /**
     * Whether one member's login times prove a set-aside roster older than its date: a roster
     * dated earlier already showed that member's newer login [earlierDatedLogin] than this
     * roster's own [ownLogin]. Login times only move forward, so this roster was captured before
     * that one. Logins are epoch seconds; zero records none.
     */
    fun contradicts(ownLogin: Long, earlierDatedLogin: Long): Boolean =
        ownLogin > 0L && earlierDatedLogin > ownLogin

    /**
     * Whether a set-aside roster returns to the roster history: the clock has reached its date
     * and no roster dated earlier proves it older. A roster set aside because a later capture
     * ran on a clock set behind comes back once the clock is right again; one whose clock really
     * ran ahead stays aside even after its date passes, so it never lands among the rosters
     * captured meanwhile. [contradicted] is read only once the date has passed.
     */
    fun mayReturn(capturedAt: Instant, reference: Instant, contradicted: () -> Boolean): Boolean =
        !isAhead(capturedAt, reference) && !contradicted()
}
