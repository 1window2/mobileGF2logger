package dev.gf2log.app.management

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object WeeklyReportRange {
    fun periodStarts(evidenceDays: Collection<LocalDate>): List<LocalDate> {
        if (evidenceDays.isEmpty()) return emptyList()
        val first = PlatoonPeriods.weekStart(requireNotNull(evidenceDays.minOrNull()))
        val last = PlatoonPeriods.weekStart(requireNotNull(evidenceDays.maxOrNull()))
        val count = ChronoUnit.WEEKS.between(first, last) + 1L
        require(count in 1..MAX_PERIODS) { "Weekly history range is unreasonably large" }
        return List(count.toInt()) { offset -> first.plusWeeks(offset.toLong()) }
    }

    private const val MAX_PERIODS = 5_200L
}
