package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bounds packet-triggered weekly-history work independently of parser behavior. */
internal object WeeklyHistoryWorkPolicy {
    const val MAX_CHANGED_WEEKS_PER_INGEST = 32

    fun changedPeriodStarts(
        instants: Sequence<Instant>,
        zoneId: ZoneId,
    ): List<LocalDate> = instants
        .map { PlatoonPeriods.weekStart(PlatoonPeriods.gameDay(it, zoneId)) }
        .distinct()
        .take(MAX_CHANGED_WEEKS_PER_INGEST)
        .toList()
}
