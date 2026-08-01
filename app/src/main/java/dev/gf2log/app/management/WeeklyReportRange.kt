package dev.gf2log.app.management

import java.time.LocalDate

object WeeklyReportRange {
    fun periodStarts(evidenceDays: Collection<LocalDate>): List<LocalDate> {
        val starts = evidenceDays.map(PlatoonPeriods::weekStart).distinct().sorted()
        require(starts.size <= MAX_PERIODS) { "Weekly history contains too many periods" }
        return starts
    }

    private const val MAX_PERIODS = 5_200
}
