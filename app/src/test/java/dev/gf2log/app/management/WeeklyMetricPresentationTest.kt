package dev.gf2log.app.management

import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyMetricPresentationTest {
    @Test
    fun formatsExactLowerBoundAndUnknownWithoutLeakingValues() {
        assertEquals("3", WeeklyMetricPresentation.format(3, MetricCertainty.EXACT))
        assertEquals("\u22652", WeeklyMetricPresentation.format(2, MetricCertainty.LOWER_BOUND))
        assertEquals("?", WeeklyMetricPresentation.format(90, MetricCertainty.UNKNOWN))
        assertEquals("-", WeeklyMetricPresentation.format(null, MetricCertainty.UNKNOWN, "-"))
    }
}
