package dev.gf2log.protocol

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** UTC second-precision timestamp shared by roster `logTime` cells and history envelopes. */
object LogTime {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = FORMAT.format(instant)
}
