package dev.gf2log.app.management

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Names of roster CSV files, each stamped with its capture instant in UTC. */
internal object RosterFileNames {
    /** The capture instant as file names carry it, such as `20260803T123456Z`. */
    val CAPTURE_TIME: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    /** The name, without extension, of a roster captured at [capturedAt]. */
    fun capturedStem(capturedAt: Instant): String = "gf2log_platoonmembers_${CAPTURE_TIME.format(capturedAt)}"
}
