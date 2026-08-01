package dev.gf2log.app.management

object WeeklyMetricPresentation {
    fun format(
        value: Any?,
        certainty: MetricCertainty,
        missing: String = "?",
    ): String = when {
        value == null -> missing
        certainty == MetricCertainty.EXACT -> value.toString()
        certainty == MetricCertainty.LOWER_BOUND -> "\u2265$value"
        else -> "?"
    }

    // Function Name: warnsBelowCutline
    // Description:
    // - Marks a displayed exact value or confirmed lower bound when it remains below its cutoff.
    // - Leaves unknown values and lower bounds that already meet the cutoff unmarked.
    // Parameters:
    // - value: Numeric metric value rendered in the weekly table, or null when unavailable.
    // - certainty: Whether the value is exact, a lower bound, or unknown.
    // - belowCutline: Metric-specific cutoff comparison.
    // Returns:
    // - Returns true when the displayed value should use the cutoff warning color.
    fun <T : Any> warnsBelowCutline(
        value: T?,
        certainty: MetricCertainty,
        belowCutline: (T) -> Boolean,
    ): Boolean = value != null &&
        certainty != MetricCertainty.UNKNOWN &&
        belowCutline(value)
}
