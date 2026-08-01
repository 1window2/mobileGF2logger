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
}
