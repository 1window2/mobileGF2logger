package dev.gf2log.app.management

import java.time.LocalDate

/**
 * Owns weekly-screen navigation and asynchronous render validity independently
 * from Android views. A monotonically increasing generation makes a result
 * applicable only to the request that is still current while the screen is
 * resumed.
 */
class WeeklyReportStateHolder(initialReferenceDay: LocalDate) {
    var referenceDay: LocalDate = initialReferenceDay
        private set

    private var resumed = false
    private var generation = 0

    data class RenderRequest(
        val referenceDay: LocalDate,
        val generation: Int,
    )

    fun onResume() {
        resumed = true
    }

    fun onPause() {
        resumed = false
        generation += 1
    }

    fun newRenderRequest(): RenderRequest = RenderRequest(
        referenceDay = referenceDay,
        generation = ++generation,
    )

    fun canApply(generation: Int): Boolean = resumed && generation == this.generation

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
