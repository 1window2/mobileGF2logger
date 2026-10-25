package dev.gf2log.app.management

import java.time.LocalDate

/**
 * Owns weekly-screen navigation independently from Android views. Stale renders need no guard
 * here: the screen's background work replaces an older render with the newest one.
 */
class WeeklyReportStateHolder(initialReferenceDay: LocalDate) {
    var referenceDay: LocalDate = initialReferenceDay
        private set

    fun showPreviousWeek(periodStart: LocalDate) {
        referenceDay = periodStart.minusDays(1)
    }

    fun showNextWeek(periodEnd: LocalDate) {
        referenceDay = periodEnd.plusDays(1)
    }

    fun selectDate(date: LocalDate) {
        referenceDay = date
    }
}
